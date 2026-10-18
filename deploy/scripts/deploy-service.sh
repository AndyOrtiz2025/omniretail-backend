#!/bin/sh
# Despliega un servicio (backend | web) con una etiqueta inmutable (sha-xxxxxxx) y espera a que quede sano.
# Si no queda sano, vuelve a la imagen que estaba corriendo y termina con error.
# Lo ejecutan los CD de backend y web en el VPS:  sh scripts/deploy-service.sh <servicio> <etiqueta>
# La etiqueta desplegada queda en .env (BACKEND_TAG / WEB_TAG): un `docker compose up -d` manual, un
# reinicio del VPS o el CD del otro repo levantan siempre la misma version, nunca un `latest` movil.
set -eu

SERVICE=${1:?Uso: deploy-service.sh <backend|web> <etiqueta>}
TAG=${2:?Uso: deploy-service.sh <backend|web> <etiqueta>}
case "$SERVICE" in
    backend) TAG_VAR=BACKEND_TAG ;;
    web) TAG_VAR=WEB_TAG ;;
    *) echo "Servicio no soportado: $SERVICE" >&2; exit 2 ;;
esac
case "$TAG" in
    *[!A-Za-z0-9._-]*) echo "Etiqueta invalida: $TAG" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
# Los CD de backend y web pueden coincidir: se serializan para no pisarse el .env ni el compose.
exec 9> .deploy.lock
flock 9

COMPOSE="docker compose -f docker-compose.prod.yml"
# Backend: Liquibase + arranque de la JVM. Web: Next.js arranca en segundos.
WAIT_TIMEOUT=300

# Reescribe solo la linea de la etiqueta; el resto del .env (secretos) no se toca.
set_tag() {
    tmp=$(mktemp .env.XXXXXX)
    grep -v "^$TAG_VAR=" .env > "$tmp" || true
    echo "$TAG_VAR=$1" >> "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" .env
}

# Imagen que corre ahora (por ID, no por etiqueta: `latest` pudo moverse). Vacio en el primer despliegue.
PREV_IMAGE_ID=""
PREV_REPO=""
PREV_CONTAINER=$($COMPOSE ps -q "$SERVICE" 2>/dev/null || true)
if [ -n "$PREV_CONTAINER" ]; then
    PREV_IMAGE_ID=$(docker inspect --format '{{.Image}}' "$PREV_CONTAINER")
    PREV_REF=$(docker inspect --format '{{.Config.Image}}' "$PREV_CONTAINER")
    PREV_REPO=${PREV_REF%:*}
fi

set_tag "$TAG"
$COMPOSE pull "$SERVICE"
if $COMPOSE up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"; then
    echo "$SERVICE desplegado con la etiqueta $TAG."
    exit 0
fi

echo "::error::$SERVICE no quedo sano con $TAG en ${WAIT_TIMEOUT}s. Ultimos logs:" >&2
$COMPOSE logs --no-color --tail 80 "$SERVICE" >&2 || true

if [ -z "$PREV_IMAGE_ID" ]; then
    echo "::error::No habia una version anterior corriendo; no hay a donde volver." >&2
    exit 1
fi

# La imagen anterior se reetiqueta como `rollback` para que compose la use sin descargar nada.
echo "Volviendo a la imagen anterior ($PREV_IMAGE_ID)..." >&2
docker tag "$PREV_IMAGE_ID" "$PREV_REPO:rollback"
set_tag rollback
if $COMPOSE up -d --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$SERVICE"; then
    echo "::error::Despliegue fallido: $SERVICE quedo en la version anterior ($TAG_VAR=rollback)." >&2
else
    echo "::error::Despliegue fallido y la version anterior tampoco quedo sana. Revisar el VPS a mano." >&2
fi
exit 1
