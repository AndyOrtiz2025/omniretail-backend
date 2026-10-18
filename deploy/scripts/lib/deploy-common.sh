# shellcheck shell=sh
# Funciones compartidas por deploy-backend.sh y deploy-service.sh. Se carga con `.`; no se ejecuta sola.
# Todas asumen que el directorio actual es el de la aplicacion en el VPS (/opt/omniretail).

# Espera maxima de un healthcheck. Backend: Liquibase + arranque de la JVM.
WAIT_TIMEOUT=${DEPLOY_WAIT_TIMEOUT:-300}

compose() { docker compose -f docker-compose.prod.yml "$@"; }

# Lock unico del VPS: serializa TODA la secuencia de despliegue de backend y web (promocion de config,
# pulls, healthchecks, rollbacks, recargas y prune). Un script llamado por otro que ya lo tiene recibe
# DEPLOY_LOCK_HELD=1 y no lo vuelve a pedir (abrir el archivo otra vez se bloquearia contra si mismo).
acquire_deploy_lock() {
    if [ "${DEPLOY_LOCK_HELD:-}" = 1 ]; then return 0; fi
    exec 9> .deploy.lock
    flock 9
    DEPLOY_LOCK_HELD=1
    export DEPLOY_LOCK_HELD
}

# Con el lock tomado: si un despliegue anterior quedo a medias (vuelta atras fallida, interrumpido o muerto
# sin traps: SIGKILL, caida del VPS), su marca durable con el respaldo esta en .recovery/<id>/ y el VPS
# necesita intervencion manual. Ningun despliegue (backend ni web) avanza sobre ese estado hasta que alguien
# lo resuelva y borre la carpeta (ver su LEEME.txt). Las carpetas *.tmp son respaldos a medio escribir de un
# despliegue que murio ANTES de promover nada: no bloquean y se limpian.
require_no_pending_recovery() {
    pending=$(find .recovery -mindepth 1 -maxdepth 1 -type d ! -name '*.tmp' 2>/dev/null | head -n 1)
    if [ -n "$pending" ]; then
        echo "::error::Hay una recuperacion manual pendiente en $(pwd)/$pending (ver LEEME.txt). No se despliega hasta resolverla y borrar esa carpeta." >&2
        exit 1
    fi
}

# Punta actual de main segun GitHub. El token va por stdin, nunca en la linea de comandos (visible en `ps`).
main_sha() {
    printf 'Authorization: Bearer %s\n' "$GITHUB_API_TOKEN" |
        curl -fsS --max-time 15 -H @- -H 'Accept: application/vnd.github.sha' \
            "https://api.github.com/repos/$DEPLOY_REPO/commits/main"
}

# Con el lock tomado: 0 si DEPLOY_SHA sigue siendo la punta de main, 1 si es obsoleto.
# Si GitHub no responde, termina el script: no se despliega a ciegas.
deploy_is_current() {
    : "${DEPLOY_SHA:?Falta DEPLOY_SHA}" "${DEPLOY_REPO:?Falta DEPLOY_REPO}" "${GITHUB_API_TOKEN:?Falta GITHUB_API_TOKEN}"
    if ! current=$(main_sha); then
        echo "::error::No se pudo consultar la punta de main en GitHub; no se despliega a ciegas." >&2
        exit 1
    fi
    if [ "$current" != "$DEPLOY_SHA" ]; then
        echo "::notice::$DEPLOY_SHA ya no es la punta de main ($current). Despliegue obsoleto omitido; no se toco nada."
        return 1
    fi
    return 0
}

# Etiqueta desplegada de un servicio en .env (BACKEND_TAG / WEB_TAG). Vacio si no esta definida.
env_tag() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- || true; }

# write_env_tag <VAR> <valor>: reescribe solo esa linea del .env (valor vacio = quitarla) de forma atomica;
# el resto del .env (secretos) no se toca.
write_env_tag() {
    tmp=$(mktemp .env.XXXXXX) || return 1
    grep -v "^$1=" .env > "$tmp" || true
    if [ -n "$2" ]; then echo "$1=$2" >> "$tmp"; fi
    chmod 600 "$tmp" && mv "$tmp" .env
}

# Estado del contenedor de un servicio: running | stopped (creado pero detenido) | absent.
container_state() {
    if [ -n "$(compose ps -q "$1" 2>/dev/null || true)" ]; then
        echo running
    elif [ -n "$(compose ps -a -q "$1" 2>/dev/null || true)" ]; then
        echo stopped
    else
        echo absent
    fi
}

# Imagen del contenedor de un servicio (corriendo o detenido), como "<image_id> <repositorio>" (por ID y no
# por etiqueta: la etiqueta pudo moverse). Vacio si el servicio no tiene contenedor.
container_image() {
    cid=$(compose ps -a -q "$1" 2>/dev/null || true)
    [ -n "$cid" ] || return 0
    id=$(docker inspect --format '{{.Image}}' "$cid") || return 1
    ref=$(docker inspect --format '{{.Config.Image}}' "$cid") || return 1
    echo "$id ${ref%:*}"
}

# restore_image <servicio> <VAR> <etiqueta_previa> <image_id> <repositorio> [stopped]: vuelve a la imagen previa
# con la configuracion vigente. La reetiqueta (con su etiqueta previa, o `rollback` si no habia) para que
# compose la use sin descargar nada y fija esa etiqueta en .env. Si el servicio corria, lo levanta y espera
# el healthcheck; si estaba detenido ("stopped"), solo recrea el contenedor y lo deja detenido.
# Distinto de 0 si algo falla.
restore_image() {
    rollback_tag=${3:-rollback}
    docker tag "$4" "$5:$rollback_tag" && write_env_tag "$2" "$rollback_tag" || return 1
    if [ "${6:-}" = stopped ]; then
        compose up --no-start --no-deps "$1" && compose stop "$1"
    else
        compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$1"
    fi
}

# Deja el arbol $2 identico a $1 SIN reemplazar directorios: los bind mounts de carpeta (caddy/,
# monitoring/prometheus, monitoring/grafana/provisioning) apuntan al inodo del directorio, y si se
# reemplazara el contenedor seguiria viendo el viejo. Cada archivo se escribe con tmp + mv (atomico).
# Devuelve distinto de 0 ante cualquier fallo (para poder restaurar el respaldo).
sync_tree() {
    src=$1
    dst=$2
    mkdir -p "$dst" || return 1
    (cd "$src" && find . -type d) | while IFS= read -r d; do
        mkdir -p "$dst/$d" || exit 1
    done || return 1
    (cd "$src" && find . -type f) | while IFS= read -r f; do
        { cp "$src/$f" "$dst/$f.sync-tmp" && mv "$dst/$f.sync-tmp" "$dst/$f"; } || exit 1
    done || return 1
    # Lo que ya no existe en el origen se borra (los mas profundos primero).
    (cd "$dst" && find . -depth -mindepth 1) | while IFS= read -r p; do
        if [ ! -e "$src/$p" ]; then rm -rf "${dst:?}/$p" || exit 1; fi
    done
}
