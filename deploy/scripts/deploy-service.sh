#!/bin/sh
# Despliega un servicio (backend | web) con una etiqueta inmutable (sha-...) y espera a que quede sano.
# Lo ejecutan los CD de backend y web en el VPS:  sh scripts/deploy-service.sh <servicio> <etiqueta>
#
# Garantias:
# - La etiqueta desplegada queda en .env (BACKEND_TAG / WEB_TAG): un `docker compose up -d` manual, un
#   reinicio del VPS o el CD del otro repo levantan siempre la misma version, nunca un `latest` movil.
# - La imagen nueva se descarga ANTES de tocar el .env: si el pull falla, el .env queda intacto.
# - Si el contenedor nuevo no queda sano, vuelve a la imagen que estaba corriendo y restaura la etiqueta previa.
# - Cualquier otro fallo inesperado con el .env ya modificado restaura la etiqueta previa (trap EXIT).
# - Con DEPLOY_REPO + DEPLOY_SHA + GITHUB_API_TOKEN (los pasa el CD), ya con el lock tomado comprueba que el
#   commit siga siendo la punta de main; si main avanzo, el despliegue es obsoleto y se omite sin tocar nada.
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

cd "$(dirname "$0")/.."
# Los CD de backend y web pueden coincidir: se serializan para no pisarse el .env ni el compose.
exec 9> .deploy.lock
flock 9

compose() { docker compose -f docker-compose.prod.yml "$@"; }
# Backend: Liquibase + arranque de la JVM. Web: Next.js arranca en segundos.
WAIT_TIMEOUT=${DEPLOY_WAIT_TIMEOUT:-300}

# Punta actual de main segun GitHub. El token va por stdin, nunca en la linea de comandos (visible en `ps`).
main_sha() {
    printf 'Authorization: Bearer %s\n' "$GITHUB_API_TOKEN" |
        curl -fsS --max-time 15 -H @- -H 'Accept: application/vnd.github.sha' \
            "https://api.github.com/repos/$DEPLOY_REPO/commits/main"
}

if [ -n "${DEPLOY_SHA:-}" ]; then
    : "${DEPLOY_REPO:?Falta DEPLOY_REPO para validar la vigencia}" "${GITHUB_API_TOKEN:?Falta GITHUB_API_TOKEN}"
    if ! CURRENT_SHA=$(main_sha); then
        echo "::error::No se pudo consultar la punta de main en GitHub; no se despliega a ciegas." >&2
        exit 1
    fi
    if [ "$CURRENT_SHA" != "$DEPLOY_SHA" ]; then
        echo "::notice::$SERVICE: $DEPLOY_SHA ya no es la punta de main ($CURRENT_SHA). Despliegue obsoleto omitido."
        exit 0
    fi
fi

current_tag() { grep -E "^$TAG_VAR=" .env | tail -n 1 | cut -d= -f2- || true; }

# Reescribe solo la linea de la etiqueta (valor vacio = quitarla); el resto del .env (secretos) no se toca.
write_tag() {
    tmp=$(mktemp .env.XXXXXX)
    grep -v "^$TAG_VAR=" .env > "$tmp" || true
    if [ -n "$1" ]; then echo "$TAG_VAR=$1" >> "$tmp"; fi
    chmod 600 "$tmp"
    mv "$tmp" .env
}

PREV_TAG=$(current_tag)
TAG_WRITTEN=false
# Se invoca desde `trap ... EXIT`; shellcheck no lo detecta.
# shellcheck disable=SC2317,SC2329
restore_on_failure() {
    status=$?
    if [ "$TAG_WRITTEN" = true ]; then
        write_tag "$PREV_TAG"
        echo "::error::Fallo inesperado (codigo $status): $TAG_VAR restaurada a '${PREV_TAG:-<sin definir>}'." >&2
    fi
}
trap restore_on_failure EXIT

# Imagen que corre ahora (por ID, no por etiqueta: `latest` pudo moverse). Vacio en el primer despliegue.
PREV_IMAGE_ID=""
PREV_REPO=""
PREV_CONTAINER=$(compose ps -q "$SERVICE" 2>/dev/null || true)
if [ -n "$PREV_CONTAINER" ]; then
    PREV_IMAGE_ID=$(docker inspect --format '{{.Image}}' "$PREV_CONTAINER")
    PREV_REF=$(docker inspect --format '{{.Config.Image}}' "$PREV_CONTAINER")
    PREV_REPO=${PREV_REF%:*}
fi

# 1. Descargar con la etiqueta nueva SIN escribirla en el .env (la variable del entorno manda sobre el .env).
if ! (export "$TAG_VAR=$TAG"; compose pull "$SERVICE"); then
    echo "::error::No se pudo descargar la imagen $TAG de $SERVICE. No se cambio nada en el VPS." >&2
    exit 1
fi

# 2. Activar la version nueva.
write_tag "$TAG"
TAG_WRITTEN=true
if compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"; then
    TAG_WRITTEN=false
    echo "$SERVICE desplegado con la etiqueta $TAG."
    exit 0
fi

echo "::error::$SERVICE no quedo sano con $TAG en ${WAIT_TIMEOUT}s. Ultimos logs:" >&2
compose logs --no-color --tail 80 "$SERVICE" >&2 || true

# 3. Rollback. Sin version anterior corriendo no hay a donde volver: solo se restaura la etiqueta previa.
if [ -z "$PREV_IMAGE_ID" ]; then
    write_tag "$PREV_TAG"
    TAG_WRITTEN=false
    echo "::error::No habia una version anterior corriendo; $TAG_VAR restaurada a '${PREV_TAG:-<sin definir>}'." >&2
    exit 1
fi

# La imagen anterior se reetiqueta (con su etiqueta previa, o `rollback` si no habia) para que compose la
# use sin descargar nada, aunque la etiqueta remota haya cambiado.
ROLLBACK_TAG=${PREV_TAG:-rollback}
echo "Volviendo a la imagen anterior ($PREV_IMAGE_ID) como $ROLLBACK_TAG..." >&2
docker tag "$PREV_IMAGE_ID" "$PREV_REPO:$ROLLBACK_TAG"
write_tag "$ROLLBACK_TAG"
TAG_WRITTEN=false
if compose up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"; then
    echo "::error::Despliegue fallido: $SERVICE quedo en la version anterior ($TAG_VAR=$ROLLBACK_TAG)." >&2
else
    echo "::error::Despliegue fallido y la version anterior tampoco quedo sana. Revisar el VPS a mano." >&2
fi
exit 1
