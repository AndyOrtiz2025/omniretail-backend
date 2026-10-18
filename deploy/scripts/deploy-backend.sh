#!/bin/sh
# Despliegue completo del backend en el VPS. El CD copia los archivos de despliegue del commit a
# .staging/<sha>/ (inofensivo: nada lee de ahi) y ejecuta ESTE script desde ese staging:
#
#   cd /opt/omniretail && sh .staging/<sha>/scripts/deploy-backend.sh <sha> <etiqueta>
#
# Con el lock unico del VPS tomado (el mismo que usa el CD de web):
#   1. Revalida que <sha> siga siendo la punta de main. Si es obsoleto: borra su staging y sale con 0
#      sin promover configuracion, sin tocar contenedores y sin recargar nada.
#   2. Respalda la configuracion vigente.
#   3. Promueve la del staging (compose, caddy/, monitoring/, scripts/), levanta Postgres + usuario de
#      monitoreo y el backend con healthcheck/rollback (deploy-service.sh). Si algo falla, restaura la
#      configuracion respaldada y NO recarga nada.
#   4. Levanta y recarga Caddy, Prometheus y Grafana con la configuracion nueva.
# Requiere en el entorno: DEPLOY_REPO y GITHUB_API_TOKEN (para validar la vigencia).
set -eu

SHA=${1:?Uso: deploy-backend.sh <sha> <etiqueta>}
TAG=${2:?Uso: deploy-backend.sh <sha> <etiqueta>}
case "$SHA" in
    *[!0-9a-f]* | '') echo "SHA invalido: $SHA" >&2; exit 2 ;;
esac
[ "${#SHA}" -eq 40 ] || { echo "SHA invalido: $SHA" >&2; exit 2; }

STAGING=$(cd "$(dirname "$0")/.." && pwd)
APP_DIR=$(cd "$STAGING/../.." && pwd)
[ "$STAGING" = "$APP_DIR/.staging/$SHA" ] || { echo "El script debe ejecutarse desde .staging/$SHA" >&2; exit 2; }
cd "$APP_DIR"

# shellcheck source=deploy/scripts/lib/deploy-common.sh
. "$STAGING/scripts/lib/deploy-common.sh"

# Archivos y carpetas que viven en el repo; .env, volumenes y .staging nunca se tocan.
CONFIG_ITEMS="docker-compose.prod.yml caddy monitoring scripts"
BACKUP=""
cleanup() {
    rm -rf "$STAGING"
    if [ -n "$BACKUP" ]; then rm -rf "$BACKUP"; fi
}
trap cleanup EXIT

acquire_deploy_lock

DEPLOY_SHA=$SHA
if ! deploy_is_current; then
    exit 0
fi

# Staging de despliegues interrumpidos (mas de un dia): se limpia aqui, con el lock tomado.
find .staging -mindepth 1 -maxdepth 1 -type d -mtime +1 -exec rm -rf {} + 2>/dev/null || true

# 2. Respaldo de la configuracion vigente (puede no existir en el primer despliegue).
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

# 3. Promocion + backend. Cualquier fallo deja la configuracion como estaba y no recarga nada.
# deploy-service.sh hereda el lock (DEPLOY_LOCK_HELD) y no repite la validacion de vigencia.
if ! {
    promote "$STAGING" &&
        compose up -d --no-deps --wait postgres &&
        sh monitoring/postgres/ensure-monitoring-role.sh &&
        DEPLOY_SHA="" sh scripts/deploy-service.sh backend "$TAG"
}; then
    promote "$BACKUP"
    echo "::error::Despliegue del backend fallido: se restauro la configuracion anterior y no se recargo nada." >&2
    exit 1
fi

# 4. Servicios de borde y monitoreo con la configuracion ya promovida. La config llega por carpetas
# montadas: Caddy y Prometheus se recargan sin cortar el trafico; Grafana relee el provisioning al reiniciar.
compose up -d --no-deps caddy prometheus grafana node-exporter postgres-exporter
compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
compose kill -s SIGHUP prometheus
compose restart grafana
echo "Backend y configuracion desplegados ($TAG)."
