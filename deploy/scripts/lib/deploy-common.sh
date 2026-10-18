# shellcheck shell=sh
# Funciones compartidas por deploy-backend.sh y deploy-service.sh. Se carga con `.`; no se ejecuta sola.
# Todas asumen que el directorio actual es el de la aplicacion en el VPS (/opt/omniretail).

compose() { docker compose -f docker-compose.prod.yml "$@"; }

# Lock unico del VPS: serializa TODA la secuencia de despliegue de backend y web (promocion de config,
# pulls, healthchecks, recargas y prune). Un script llamado por otro que ya lo tiene recibe
# DEPLOY_LOCK_HELD=1 y no lo vuelve a pedir (abrir el archivo otra vez se bloquearia contra si mismo).
acquire_deploy_lock() {
    if [ "${DEPLOY_LOCK_HELD:-}" = 1 ]; then return 0; fi
    exec 9> .deploy.lock
    flock 9
    DEPLOY_LOCK_HELD=1
    export DEPLOY_LOCK_HELD
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
