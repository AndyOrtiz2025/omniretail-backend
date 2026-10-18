#!/bin/sh
# Despliegue completo del backend en el VPS, todo o nada. El CD copia los archivos de despliegue del commit
# a .staging/<sha>/ (inofensivo: nada lee de ahi) y ejecuta ESTE script desde ese staging:
#
#   cd /opt/omniretail && sh .staging/<sha>/scripts/deploy-backend.sh <sha> <etiqueta>
#
# Todo ocurre con el lock unico del VPS tomado (el mismo que usa el CD de web):
#   1. Revalida que <sha> siga siendo la punta de main. Si es obsoleto: sale con 0 sin tocar nada.
#   2. Respalda la configuracion vigente y recuerda la imagen del backend que esta corriendo.
#   3. Promueve la configuracion del staging y la VALIDA (compose, Caddyfile, prometheus.yml) antes de
#      tocar cualquier contenedor.
#   4. Postgres + usuario de monitoreo, y backend con healthcheck (deploy-service.sh).
#   5. Fase final: levanta Caddy, Prometheus, Grafana y exporters, recarga su configuracion y espera a que
#      queden sanos.
#   6. Solo con todo en verde: limpia imagenes huerfanas (prune).
#
# Si cualquier paso de 3 a 5 falla, VUELVE ATRAS lo que se haya alcanzado a cambiar: configuracion
# respaldada, Postgres, imagen previa del backend y servicios de borde con la configuracion anterior. Sale
# con 1.
# Si la vuelta atras tambien falla, o el script se interrumpe a mitad (senal, corte del SSH), conserva el
# respaldo en .recovery/<fecha>-<sha>/ con un LEEME.txt para recuperarlo a mano, y sale con error. Mientras esa
# carpeta exista, ningun despliegue (backend ni web) avanza: el VPS queda esperando la intervencion manual.
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

# Estado para el trap de salida:
#   idle        nada promovido todavia (o despliegue obsoleto): limpieza normal.
#   deploying   configuracion promovida y despliegue en curso: si el script termina asi, fue interrumpido.
#   completed   exito completo.
#   rolled_back fallo y se volvio al estado anterior.
#   stuck       fallo y la vuelta atras tambien fallo.
STATE=idle
BACKUP=""
PROMOTED=false
POSTGRES_TOUCHED=false
BACKEND_DEPLOYED=false
BACKEND_STUCK=false
EDGE_TOUCHED=false
PREV_BACKEND_TAG=""
PREV_BACKEND_IMAGE=""
PREV_BACKEND_REPO=""

# Conserva el respaldo para recuperacion manual y deja instrucciones junto a el. Solo la llama on_exit (trap).
# shellcheck disable=SC2317,SC2329
preserve_recovery() {
    recovery="$APP_DIR/.recovery/$(date -u +%Y%m%dT%H%M%SZ)-$SHA"
    if ! { mkdir -p "$recovery" && mv "$BACKUP" "$recovery/config"; }; then
        echo "::error::No se pudo mover el respaldo; sigue en $APP_DIR/$BACKUP. NO borrarlo." >&2
        return 0
    fi
    cat > "$recovery/LEEME.txt" <<EOF
Despliegue del backend $SHA ($TAG) sin terminar: $1.
Fecha (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)

Estado previo al despliegue:
  BACKEND_TAG anterior: ${PREV_BACKEND_TAG:-<sin definir>}
  Imagen del backend que corria: ${PREV_BACKEND_IMAGE:-<ninguna>} (${PREV_BACKEND_REPO:-<sin repo>})
  Configuracion anterior: carpeta config/ junto a este archivo.

Recuperacion manual (como usuario deploy):
  cd $APP_DIR
  exec 9> .deploy.lock && flock 9          # evita que un CD corra mientras tanto
  cp -R "$recovery/config/." ./            # configuracion anterior (reemplaza archivo por archivo)
EOF
    if [ -n "$PREV_BACKEND_IMAGE" ]; then
        cat >> "$recovery/LEEME.txt" <<EOF
  docker tag $PREV_BACKEND_IMAGE $PREV_BACKEND_REPO:${PREV_BACKEND_TAG:-rollback}
  # en .env: BACKEND_TAG=${PREV_BACKEND_TAG:-rollback}
  docker compose -f docker-compose.prod.yml up -d --no-deps --wait backend
EOF
    fi
    cat >> "$recovery/LEEME.txt" <<EOF
  docker compose -f docker-compose.prod.yml up -d --no-deps --wait postgres caddy prometheus grafana node-exporter postgres-exporter
  docker compose -f docker-compose.prod.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile
  docker compose -f docker-compose.prod.yml kill -s SIGHUP prometheus
  docker compose -f docker-compose.prod.yml restart grafana
Al terminar, borrar esta carpeta: mientras exista, los CD de backend y web no despliegan nada.
EOF
    echo "::error::Respaldo de la configuracion anterior conservado en $recovery (ver LEEME.txt)." >&2
}

# Se invoca desde `trap ... EXIT`; shellcheck no lo detecta.
# shellcheck disable=SC2317,SC2329
on_exit() {
    status=$?
    case "$STATE" in
        deploying) preserve_recovery "interrumpido a mitad (codigo $status)" ;;
        stuck) preserve_recovery "la vuelta atras fallo" ;;
        *) if [ -n "$BACKUP" ]; then rm -rf "$BACKUP"; fi ;;
    esac
    rm -rf "$STAGING"
}
trap on_exit EXIT
# Una senal (p. ej. el corte del SSH) termina el script por el trap de salida, que conserva el respaldo.
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

acquire_deploy_lock
require_no_pending_recovery

DEPLOY_SHA=$SHA
if ! deploy_is_current; then
    exit 0
fi

# Staging de despliegues viejos interrumpidos (mas de un dia): se limpia aqui, con el lock tomado.
find .staging -mindepth 1 -maxdepth 1 -type d -mtime +1 -exec rm -rf {} + 2>/dev/null || true

# 2. Estado previo: imagen del backend que corre y respaldo de la configuracion vigente.
PREV_BACKEND_TAG=$(env_tag BACKEND_TAG)
PREV=$(running_image backend)
PREV_BACKEND_IMAGE=${PREV%% *}
PREV_BACKEND_REPO=${PREV#* }
BACKUP=$(mktemp -d .staging/backup.XXXXXX)
for item in $CONFIG_ITEMS; do
    if [ -d "$item" ]; then sync_tree "$item" "$BACKUP/$item"
    elif [ -f "$item" ]; then cp "$item" "$BACKUP/$item"
    fi
done

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

# Valida la configuracion promovida sin tocar los contenedores que estan corriendo (contenedores efimeros).
validate_config() {
    compose config -q &&
        compose run --rm --no-deps -T --entrypoint caddy caddy \
            validate --config /etc/caddy/Caddyfile --adapter caddyfile &&
        compose run --rm --no-deps -T --entrypoint promtool prometheus \
            check config /etc/prometheus/config/prometheus.yml
}

edge_up() {
    compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" \
        caddy prometheus grafana node-exporter postgres-exporter
}

# Fase final: la config llega por carpetas montadas. Caddy y Prometheus se recargan sin cortar el trafico;
# Grafana relee el provisioning al reiniciar. El ultimo `up --wait` confirma que todo quedo sano.
apply_edge() {
    edge_up &&
        compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile &&
        compose kill -s SIGHUP prometheus &&
        compose restart grafana &&
        edge_up
}

# Pasos 3 a 5. Cada bandera registra hasta donde se llego, para que la vuelta atras deshaga solo eso.
deploy_all() {
    PROMOTED=true
    promote "$STAGING" || return 1
    validate_config || { echo "::error::La configuracion nueva no es valida." >&2; return 1; }
    POSTGRES_TOUCHED=true
    compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" postgres || return 1
    sh monitoring/postgres/ensure-monitoring-role.sh || return 1
    # deploy-service.sh hereda el lock, no repite la vigencia y no hace prune (se hace al final).
    rc=0
    DEPLOY_SHA="" DEPLOY_PRUNE=0 sh scripts/deploy-service.sh backend "$TAG" || rc=$?
    case "$rc" in
        0) BACKEND_DEPLOYED=true ;;
        3) BACKEND_STUCK=true; return 1 ;; # ni la nueva ni la anterior quedaron sanas
        *) return 1 ;;                     # deploy-service ya volvio a la imagen anterior
    esac
    EDGE_TOUCHED=true
    apply_edge || { echo "::error::Fallo la fase final (Caddy/Prometheus/Grafana)." >&2; return 1; }
}

# Deshace en orden inverso solo lo que deploy_all alcanzo a cambiar. Distinto de 0 si algo no se pudo.
rollback_all() {
    if [ "$PROMOTED" = true ]; then
        promote "$BACKUP" || { echo "::error::No se pudo restaurar la configuracion anterior." >&2; return 1; }
    fi
    if [ "$POSTGRES_TOUCHED" = true ]; then
        compose up -d --no-deps --wait --wait-timeout "$SHORT_WAIT" postgres || return 1
    fi
    if [ "$BACKEND_STUCK" = true ]; then
        echo "::error::El backend no quedo sano ni con la version nueva ni con la anterior." >&2
        return 1
    fi
    if [ "$BACKEND_DEPLOYED" = true ]; then
        if [ -n "$PREV_BACKEND_IMAGE" ]; then
            restore_image backend BACKEND_TAG "$PREV_BACKEND_TAG" "$PREV_BACKEND_IMAGE" "$PREV_BACKEND_REPO" ||
                { echo "::error::No se pudo volver a la imagen anterior del backend." >&2; return 1; }
        else
            # Primer despliegue: el estado anterior es "sin backend".
            { write_env_tag BACKEND_TAG "$PREV_BACKEND_TAG" && compose stop backend; } || return 1
        fi
    fi
    if [ "$EDGE_TOUCHED" = true ]; then
        apply_edge || { echo "::error::Los servicios de borde no quedaron sanos con la configuracion anterior." >&2; return 1; }
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
    echo "::error::Despliegue fallido: se restauro la configuracion y la version anteriores." >&2
    exit 1
fi
STATE=stuck
echo "::error::Despliegue fallido y la vuelta atras no se completo. Revisar el VPS a mano." >&2
exit 1
