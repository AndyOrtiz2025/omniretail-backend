#!/bin/sh
# Despliegue completo del backend en el VPS, todo o nada. El CD copia los archivos de despliegue del commit
# a .staging/<sha>/ (inofensivo: nada lee de ahi) y ejecuta ESTE script desde ese staging:
#
#   cd /opt/omniretail && sh .staging/<sha>/scripts/deploy-backend.sh <sha> <etiqueta>
#
# Todo ocurre con el lock unico del VPS tomado (el mismo que usa el CD de web):
#   1. Si hay una recuperacion manual pendiente (.recovery/), no hace nada. Revalida que <sha> siga siendo la
#      punta de main; si es obsoleto, sale con 0 sin tocar nada.
#   2. Registra el estado previo (cada contenedor: corriendo, detenido o ausente; imagen y etiqueta del
#      backend; que archivos de configuracion existian) y crea una MARCA DURABLE en .recovery/<id>/ con el
#      respaldo de la configuracion y un LEEME.txt, escrita en disco (sync) ANTES de promover nada.
#   3. Promueve la configuracion del staging y la VALIDA (compose, Caddyfile, prometheus.yml) antes de tocar
#      cualquier contenedor.
#   4. Postgres + usuario de monitoreo, y backend con healthcheck (deploy-service.sh).
#   5. Fase final: levanta Caddy, Prometheus, Grafana y exporters, recarga su configuracion y espera a que
#      queden sanos.
#   6. Solo con todo en verde: limpia imagenes huerfanas (prune) y borra la marca.
#
# Si cualquier paso de 3 a 5 falla, VUELVE AL ESTADO PREVIO solo lo que alcanzo a cambiar: elimina los
# contenedores que no existian, restaura la configuracion (y borra los archivos que no existian), recrea con
# la configuracion anterior Postgres, el backend (imagen previa) y los servicios de borde, y deja detenido lo
# que estaba detenido. Si lo logra, borra la marca y sale con 1.
# Si la vuelta atras falla, o el proceso muere a mitad (senal, corte del SSH, SIGKILL, caida del VPS), la marca
# QUEDA: ningun despliegue (backend ni web) avanza hasta que alguien siga el LEEME.txt y borre la carpeta.
#
# Limite conocido: la vuelta atras no revierte migraciones de base de datos (Liquibase); por eso las
# migraciones deben ser compatibles con la version anterior del backend (expand/contract).
# Requiere en el entorno: DEPLOY_REPO y GITHUB_API_TOKEN (para validar la vigencia).
set -eu

SHA=${1:?Uso: deploy-backend.sh <sha> <etiqueta>}
TAG=${2:?Uso: deploy-backend.sh <sha> <etiqueta>}
case "$SHA" in
    *[!0-9a-f]* | '') echo "SHA invalido: $SHA" >&2; exit 2 ;;
esac
[ "${#SHA}" -eq 40 ] || { echo "SHA invalido: $SHA" >&2; exit 2; }
case "$TAG" in
    '' | *[!A-Za-z0-9._-]*) echo "Etiqueta invalida: $TAG" >&2; exit 2 ;;
esac

STAGING=$(cd "$(dirname "$0")/.." && pwd)
APP_DIR=$(cd "$STAGING/../.." && pwd)
[ "$STAGING" = "$APP_DIR/.staging/$SHA" ] || { echo "El script debe ejecutarse desde .staging/$SHA" >&2; exit 2; }
cd "$APP_DIR"

# shellcheck source=deploy/scripts/lib/deploy-common.sh
. "$STAGING/scripts/lib/deploy-common.sh"

# Espera de Postgres y de los servicios de borde (arrancan en segundos; el backend usa WAIT_TIMEOUT, 300 s).
# Peor caso total con vuelta atras completa: ~23 min, dentro del command_timeout del CD (35 min).
SHORT_WAIT=${DEPLOY_SHORT_WAIT_TIMEOUT:-120}

# Archivos y carpetas que vienen del repo; .env, volumenes, .staging y .recovery nunca se tocan.
CONFIG_ITEMS="docker-compose.prod.yml caddy monitoring scripts"
EDGE_SERVICES="caddy prometheus grafana node-exporter postgres-exporter"
SERVICES="postgres backend $EDGE_SERVICES"

# Estado de la ejecucion (lo usa el trap de salida):
#   idle        nada promovido todavia (o despliegue obsoleto): la marca, si llego a crearse, se borra.
#   deploying   configuracion promovida y despliegue en curso: si el script termina asi, fue interrumpido.
#   completed   exito completo.
#   rolled_back fallo y se volvio al estado previo.
#   stuck       fallo y la vuelta atras tambien fallo.
STATE=idle
MARKER=""
MARKER_TMP=""
POSTGRES_TOUCHED=false
BACKEND_TOUCHED=false
EDGE_TOUCHED=false
PREV_BACKEND_TAG=""
PREV_BACKEND_IMAGE=""
PREV_BACKEND_REPO=""

# Estado previo de un servicio (running | stopped | absent), registrado en el paso 2.
prev_state() {
    var="PREV_STATE_$(echo "$1" | tr '-' '_')"
    eval "echo \"\${$var:-absent}\""
}

# El LEEME.txt de la marca: estado previo y como restaurarlo a mano. Se escribe ANTES de promover nada.
write_leeme() {
    {
        echo "Despliegue del backend $SHA ($TAG) SIN TERMINAR."
        echo "Esta carpeta se crea antes de tocar el VPS y se borra al terminar (con exito o con la vuelta atras"
        echo "completa). Si existe, ese despliegue fallo sin poder volver atras o el proceso murio a mitad (corte del"
        echo "SSH, SIGKILL, caida del VPS). Mientras exista, los CD de backend y web no despliegan nada."
        echo "Creada (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo
        echo "Estado previo al despliegue:"
        echo "  BACKEND_TAG: ${PREV_BACKEND_TAG:-<sin definir>}"
        echo "  Imagen del backend: ${PREV_BACKEND_IMAGE:-<ninguna>} (${PREV_BACKEND_REPO:-<sin repo>})"
        for svc in $SERVICES; do echo "  Servicio $svc: $(prev_state "$svc")"; done
        echo "  Configuracion que existia (copia en config/): $(tr '\n' ' ' < "$1/items")"
        echo
        echo "Recuperacion manual (como usuario deploy, dentro de $APP_DIR):"
        echo "  exec 9> .deploy.lock && flock 9    # que ningun CD corra mientras tanto"
        echo "  C=\"docker compose -f docker-compose.prod.yml\""
        for svc in $SERVICES; do
            if [ "$(prev_state "$svc")" = absent ]; then echo "  \$C rm -sf $svc    # no existia antes"; fi
        done
        echo "  cp -R \"$APP_DIR/.recovery/$(basename "$1" .tmp)/config/.\" ./"
        for item in $CONFIG_ITEMS; do
            if ! grep -qx "$item" "$1/items"; then echo "  rm -rf ./$item    # no existia antes"; fi
        done
        if [ -n "$PREV_BACKEND_IMAGE" ]; then
            echo "  docker tag $PREV_BACKEND_IMAGE $PREV_BACKEND_REPO:${PREV_BACKEND_TAG:-rollback}"
            echo "  # en .env: BACKEND_TAG=${PREV_BACKEND_TAG:-rollback}"
        fi
        running=""
        stopped=""
        for svc in $SERVICES; do
            case "$(prev_state "$svc")" in
                running) running="$running $svc" ;;
                stopped) stopped="$stopped $svc" ;;
            esac
        done
        if [ -n "$running" ]; then echo "  \$C up -d --no-deps --wait$running"; fi
        if [ -n "$stopped" ]; then echo "  \$C up --no-start --no-deps$stopped && \$C stop$stopped"; fi
        echo "  \$C exec -T caddy caddy reload --config /etc/caddy/Caddyfile   # si caddy corria"
        echo "  \$C kill -s SIGHUP prometheus; \$C restart grafana             # si corrian"
        echo "Al terminar, borrar esta carpeta: recien entonces los CD vuelven a desplegar."
    } > "$1/LEEME.txt"
}

# Paso 2: respaldo + LEEME en .recovery/<id>.tmp, a disco, y rename atomico a .recovery/<id>. Si el proceso
# muere antes del rename, solo queda un *.tmp que no bloquea (todavia no se promovio nada).
create_marker() {
    id="$(date -u +%Y%m%dT%H%M%SZ)-$SHA"
    MARKER_TMP=".recovery/$id.tmp"
    mkdir -p "$MARKER_TMP/config"
    : > "$MARKER_TMP/items"
    for item in $CONFIG_ITEMS; do
        if [ -d "$item" ]; then
            sync_tree "$item" "$MARKER_TMP/config/$item"
            echo "$item" >> "$MARKER_TMP/items"
        elif [ -f "$item" ]; then
            cp "$item" "$MARKER_TMP/config/$item"
            echo "$item" >> "$MARKER_TMP/items"
        fi
    done
    write_leeme "$MARKER_TMP"
    sync
    mv "$MARKER_TMP" ".recovery/$id"
    MARKER=".recovery/$id"
    MARKER_TMP=""
    sync
}

# Deja constancia del motivo en la marca e informa donde esta. Solo la llama on_exit (trap).
# shellcheck disable=SC2317,SC2329
report_marker() {
    { echo; echo "Motivo: $1."; } >> "$MARKER/LEEME.txt" 2>/dev/null || true
    echo "::error::El VPS necesita recuperacion manual: ver $APP_DIR/$MARKER/LEEME.txt (respaldo de la configuracion en config/)." >&2
}

# Se invoca desde `trap ... EXIT`; shellcheck no lo detecta.
# shellcheck disable=SC2317,SC2329
on_exit() {
    status=$?
    case "$STATE" in
        deploying) report_marker "interrumpido a mitad (codigo $status)" ;;
        stuck) report_marker "la vuelta atras no se completo" ;;
        *)
            if [ -n "$MARKER" ]; then rm -rf "$MARKER"; fi
            if [ -n "$MARKER_TMP" ]; then rm -rf "$MARKER_TMP"; fi
            ;;
    esac
    rm -rf "$STAGING"
}
trap on_exit EXIT
# Una senal (p. ej. el corte del SSH) termina el script por el trap de salida, que deja la marca.
# SIGKILL o una caida no corren ningun trap: la marca ya esta en disco desde el paso 2.
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

acquire_deploy_lock
require_no_pending_recovery

DEPLOY_SHA=$SHA
if ! deploy_is_current; then
    exit 0
fi

# Restos de despliegues que murieron antes de promover nada (*.tmp) o de staging viejo: se limpian con el lock.
find .recovery -mindepth 1 -maxdepth 1 -type d -name '*.tmp' -exec rm -rf {} + 2>/dev/null || true
find .staging -mindepth 1 -maxdepth 1 -type d -mtime +1 ! -name "$SHA" -exec rm -rf {} + 2>/dev/null || true

# 2. Estado previo + marca durable.
PREV_BACKEND_TAG=$(env_tag BACKEND_TAG)
PREV=$(container_image backend)
PREV_BACKEND_IMAGE=${PREV%% *}
PREV_BACKEND_REPO=${PREV#* }
for svc in $SERVICES; do
    eval "PREV_STATE_$(echo "$svc" | tr '-' '_')=$(container_state "$svc")"
done
create_marker

# promote <origen>: deja la configuracion del VPS igual a la de <origen>. Distinto de 0 ante cualquier fallo.
promote() {
    for item in $CONFIG_ITEMS; do
        if [ -d "$1/$item" ]; then
            sync_tree "$1/$item" "$item" || return 1
        elif [ -f "$1/$item" ]; then
            { cp "$1/$item" "$item.sync-tmp" && mv "$item.sync-tmp" "$item"; } || return 1
        fi
    done
}

# Restaura la configuracion de la marca y borra lo que no existia antes de este despliegue.
restore_config() {
    for item in $CONFIG_ITEMS; do
        if grep -qx "$item" "$MARKER/items"; then
            if [ -d "$MARKER/config/$item" ]; then
                sync_tree "$MARKER/config/$item" "$item" || return 1
            else
                { cp "$MARKER/config/$item" "$item.sync-tmp" && mv "$item.sync-tmp" "$item"; } || return 1
            fi
        else
            rm -rf "./$item" || return 1
        fi
    done
}

# Valida la configuracion promovida sin tocar los contenedores que estan corriendo (contenedores efimeros).
validate_config() {
    compose config -q &&
        compose run --rm --no-deps -T --entrypoint caddy caddy \
            validate --config /etc/caddy/Caddyfile --adapter caddyfile &&
        compose run --rm --no-deps -T --entrypoint promtool prometheus \
            check config /etc/prometheus/config/prometheus.yml
}

# edge_reload <servicios...>: levanta, recarga la config montada (Caddy y Prometheus sin cortar el trafico;
# Grafana relee el provisioning al reiniciar) y confirma con un ultimo `up --wait` que todo quedo sano.
edge_reload() {
    compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" "$@" || return 1
    for svc in "$@"; do
        case "$svc" in
            caddy) compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile || return 1 ;;
            prometheus) compose kill -s SIGHUP prometheus || return 1 ;;
            grafana) compose restart grafana || return 1 ;;
        esac
    done
    compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" "$@"
}

# Pasos 3 a 5. Cada bandera registra hasta donde se llego, para que la vuelta atras deshaga solo eso.
deploy_all() {
    promote "$STAGING" || return 1
    validate_config || { echo "::error::La configuracion nueva no es valida." >&2; return 1; }
    POSTGRES_TOUCHED=true
    compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" postgres || return 1
    sh monitoring/postgres/ensure-monitoring-role.sh || return 1
    # deploy-service.sh hereda el lock, no repite la vigencia y no hace prune (se hace al final).
    BACKEND_TOUCHED=true
    DEPLOY_SHA="" DEPLOY_PRUNE=0 sh scripts/deploy-service.sh backend "$TAG" || return 1
    EDGE_TOUCHED=true
    # shellcheck disable=SC2086 # lista de servicios a proposito
    edge_reload $EDGE_SERVICES || { echo "::error::Fallo la fase final (Caddy/Prometheus/Grafana)." >&2; return 1; }
}

# reconcile <servicio>: deja un servicio de soporte (Postgres) como estaba, con la configuracion ya restaurada.
reconcile() {
    case "$(prev_state "$1")" in
        running) compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" "$1" ;;
        stopped) compose up --no-start --no-deps "$1" && compose stop "$1" ;;
        absent) return 0 ;; # ya se elimino antes de restaurar la configuracion
    esac
}

# El backend vuelve a su imagen y etiqueta previas y se RECREA con la configuracion anterior (aunque
# deploy-service.sh ya haya vuelto a la imagen previa, lo hizo con el compose nuevo).
reconcile_backend() {
    case "$(prev_state backend)" in
        absent) write_env_tag BACKEND_TAG "$PREV_BACKEND_TAG" ;;
        *)
            [ -n "$PREV_BACKEND_IMAGE" ] || return 1
            restore_image backend BACKEND_TAG "$PREV_BACKEND_TAG" "$PREV_BACKEND_IMAGE" "$PREV_BACKEND_REPO" \
                "$(prev_state backend)"
            ;;
    esac
}

# Servicios de borde: los que existian se recrean/recargan con la config anterior; los detenidos se detienen.
reconcile_edge() {
    existing=""
    for svc in $EDGE_SERVICES; do
        if [ "$(prev_state "$svc")" != absent ]; then existing="$existing $svc"; fi
    done
    if [ -n "$existing" ]; then
        # shellcheck disable=SC2086 # lista de servicios a proposito
        edge_reload $existing || return 1
    fi
    for svc in $EDGE_SERVICES; do
        if [ "$(prev_state "$svc")" = stopped ]; then compose stop "$svc" || return 1; fi
    done
}

# Deshace solo lo que deploy_all alcanzo a cambiar. Distinto de 0 si algo no se pudo.
rollback_all() {
    # 1. Contenedores que este despliegue creo y que no existian: se eliminan (los volumenes se conservan).
    #    Va antes de restaurar la configuracion, porque compose necesita el archivo para identificarlos.
    touched=""
    if [ "$POSTGRES_TOUCHED" = true ]; then touched="$touched postgres"; fi
    if [ "$BACKEND_TOUCHED" = true ]; then touched="$touched backend"; fi
    if [ "$EDGE_TOUCHED" = true ]; then touched="$touched $EDGE_SERVICES"; fi
    for svc in $touched; do
        if [ "$(prev_state "$svc")" = absent ]; then
            if [ -f docker-compose.prod.yml ]; then compose rm -sf "$svc" || return 1; fi
        fi
    done
    # 2. Configuracion anterior (y fuera lo que no existia).
    restore_config || { echo "::error::No se pudo restaurar la configuracion anterior." >&2; return 1; }
    # 3. Lo que existia se recrea con la configuracion anterior, en el orden de dependencias.
    if [ "$POSTGRES_TOUCHED" = true ]; then reconcile postgres || return 1; fi
    if [ "$BACKEND_TOUCHED" = true ]; then
        reconcile_backend || { echo "::error::No se pudo volver al backend anterior." >&2; return 1; }
    fi
    if [ "$EDGE_TOUCHED" = true ]; then
        reconcile_edge || { echo "::error::Los servicios de borde no quedaron como estaban." >&2; return 1; }
    fi
}

STATE=deploying
if deploy_all; then
    STATE=completed
    # 6. Recien ahora, con todo el despliegue confirmado.
    docker image prune -f > /dev/null || true
    echo "Backend y configuracion desplegados ($TAG)."
    exit 0
fi

echo "::error::Despliegue del backend fallido; volviendo al estado anterior..." >&2
if rollback_all; then
    STATE=rolled_back
    echo "::error::Despliegue fallido: el VPS quedo como estaba antes del despliegue." >&2
    exit 1
fi
STATE=stuck
echo "::error::Despliegue fallido y la vuelta atras no se completo." >&2
exit 1
