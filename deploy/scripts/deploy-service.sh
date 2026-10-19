#!/bin/sh
# Despliega un servicio (backend | web) con una etiqueta inmutable (sha-...) y espera a que quede sano.
# Lo ejecutan el CD de web directamente y deploy-backend.sh para el backend:
#   sh scripts/deploy-service.sh <servicio> <etiqueta>
#
# Garantias:
# - La etiqueta desplegada queda en .env (BACKEND_TAG / WEB_TAG): un `docker compose up -d` manual, un
#   reinicio del VPS o el CD del otro repo levantan siempre la misma version, nunca un `latest` movil.
# - La imagen nueva se descarga ANTES de tocar el .env: si el pull falla, el .env queda intacto.
# - Si el contenedor nuevo no queda sano, vuelve a la imagen que estaba corriendo y restaura la etiqueta previa.
# - Cualquier otro fallo inesperado con el .env ya modificado restaura la etiqueta previa (trap EXIT).
# - Con DEPLOY_REPO + DEPLOY_SHA + GITHUB_API_TOKEN (los pasa el CD), ya con el lock tomado comprueba que el
#   commit siga siendo la punta de main; si main avanzo, el despliegue es obsoleto y se omite sin tocar nada.
# - Todo ocurre bajo el lock unico del VPS; deploy-backend.sh lo comparte (DEPLOY_LOCK_HELD=1).
# - Si hay una recuperacion manual pendiente en .recovery/, no hace nada y sale con 1.
# - Al terminar con exito limpia imagenes huerfanas, salvo DEPLOY_PRUNE=0 (deploy-backend.sh lo hace
#   recien cuando termina el despliegue completo).
#
# Codigos de salida: 0 desplegado (u obsoleto omitido) | 1 fallo, el servicio quedo como estaba antes (version
# anterior, detenido si lo estaba, o sin contenedor si no existia) | 2 uso invalido | 3 fallo y la version
# anterior TAMPOCO quedo sana.
set -eu

SERVICE=${1:?Uso: deploy-service.sh <backend|web> <etiqueta>}
TAG=${2:?Uso: deploy-service.sh <backend|web> <etiqueta>}
case "$SERVICE" in
    backend) TAG_VAR=BACKEND_TAG ;;
    web) TAG_VAR=WEB_TAG ;;
    *) echo "Servicio no soportado: $SERVICE" >&2; exit 2 ;;
esac
case "$TAG" in
    '' | *[!A-Za-z0-9._-]*) echo "Etiqueta invalida: $TAG" >&2; exit 2 ;;
esac

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/.."
# shellcheck source=deploy/scripts/lib/deploy-common.sh
. "$SCRIPT_DIR/lib/deploy-common.sh"

# Si lo llama deploy-backend.sh (DEPLOY_LOCK_HELD=1), ya tiene el lock y ya reviso las recuperaciones
# pendientes: la marca de .recovery/ que ve es la de SU propio despliegue en curso, no un bloqueo.
CALLED_FROM_BACKEND_DEPLOY=${DEPLOY_LOCK_HELD:-}
# Lock unico del VPS (lo comparten los CD de backend y web).
acquire_deploy_lock
if [ "$CALLED_FROM_BACKEND_DEPLOY" != 1 ]; then
    require_no_pending_recovery
fi

if [ -n "${DEPLOY_SHA:-}" ] && ! deploy_is_current; then
    exit 0
fi

PREV_TAG=$(env_tag "$TAG_VAR")
TAG_WRITTEN=false
# Se invoca desde `trap ... EXIT`; shellcheck no lo detecta.
# shellcheck disable=SC2317,SC2329
restore_on_failure() {
    status=$?
    if [ "$TAG_WRITTEN" = true ]; then
        write_env_tag "$TAG_VAR" "$PREV_TAG"
        echo "::error::Fallo inesperado (codigo $status): $TAG_VAR restaurada a '${PREV_TAG:-<sin definir>}'." >&2
    fi
}
trap restore_on_failure EXIT

# Estado previo del contenedor (running | stopped | absent) y su imagen ("<id> <repo>", vacio si no existia).
PREV_STATE=$(container_state "$SERVICE")
PREV=$(container_image "$SERVICE")
PREV_IMAGE_ID=${PREV%% *}
PREV_REPO=${PREV#* }

# 1. Descargar con la etiqueta nueva SIN escribirla en el .env (la variable del entorno manda sobre el .env).
if ! (export "$TAG_VAR=$TAG"; compose pull "$SERVICE"); then
    echo "::error::No se pudo descargar la imagen $TAG de $SERVICE. No se cambio nada en el VPS." >&2
    exit 1
fi

# 2. Activar la version nueva.
write_env_tag "$TAG_VAR" "$TAG"
TAG_WRITTEN=true
if compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"; then
    TAG_WRITTEN=false
    echo "$SERVICE desplegado con la etiqueta $TAG."
    if [ "${DEPLOY_PRUNE:-1}" = 1 ]; then
        # Dentro del lock: no puede borrar capas que otro despliegue este descargando.
        docker image prune -f > /dev/null || true
    fi
    exit 0
fi

echo "::error::$SERVICE no quedo sano con $TAG en ${WAIT_TIMEOUT}s. Ultimos logs:" >&2
compose logs --no-color --tail 80 "$SERVICE" >&2 || true

# 3. Rollback al estado previo. Primer despliegue (no existia el contenedor): se elimina el contenedor nuevo
# y se restaura la etiqueta previa, sin dejar nada residual.
if [ "$PREV_STATE" = absent ] || [ -z "$PREV_IMAGE_ID" ]; then
    write_env_tag "$TAG_VAR" "$PREV_TAG"
    TAG_WRITTEN=false
    compose rm -sf "$SERVICE" || true
    echo "::error::No habia una version anterior; se elimino el contenedor nuevo y $TAG_VAR quedo en '${PREV_TAG:-<sin definir>}'." >&2
    exit 1
fi

echo "Volviendo a la imagen anterior ($PREV_IMAGE_ID)..." >&2
if restore_image "$SERVICE" "$TAG_VAR" "$PREV_TAG" "$PREV_IMAGE_ID" "$PREV_REPO" "$PREV_STATE"; then
    TAG_WRITTEN=false
    echo "::error::Despliegue fallido: $SERVICE quedo en la version anterior ($TAG_VAR=$(env_tag "$TAG_VAR"))." >&2
    exit 1
fi
# Si restore_image alcanzo a fijar la etiqueta previa, se deja (es la version a la que se intento volver);
# si fallo antes (p. ej. en `docker tag`), el trap restaura la etiqueta previa.
if [ "$(env_tag "$TAG_VAR")" = "${PREV_TAG:-rollback}" ]; then TAG_WRITTEN=false; fi
echo "::error::Despliegue fallido y la version anterior tampoco quedo sana. Revisar el VPS a mano." >&2
exit 3
