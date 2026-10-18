#!/bin/sh
# Pruebas de deploy/scripts/deploy-service.sh sin Docker real: docker, flock y curl se reemplazan por dobles
# que registran las llamadas y simulan fallos. Uso:  sh deploy/scripts/tests/deploy-service.test.sh
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../deploy-service.sh"
FAILURES=0
WORK=""

# ---------- dobles ----------
make_fakes() {
    mkdir -p "$WORK/bin"
    cat > "$WORK/bin/flock" <<'EOF'
#!/bin/sh
exit 0
EOF
    cat > "$WORK/bin/curl" <<'EOF'
#!/bin/sh
cat > /dev/null # El token llega por stdin: se descarta.
echo "curl" >> "$FAKE_LOG"
[ "${FAKE_CURL_FAIL:-0}" = 1 ] && exit 22
printf '%s' "$FAKE_MAIN_SHA"
EOF
    cat > "$WORK/bin/docker" <<'EOF'
#!/bin/sh
# Etiqueta activa: la variable de entorno manda sobre el .env, igual que en docker compose.
active_tag() {
    eval "value=\${$FAKE_TAG_VAR:-}"
    if [ -z "$value" ]; then value=$(grep -E "^$FAKE_TAG_VAR=" .env | tail -n 1 | cut -d= -f2-); fi
    echo "${value:-latest}"
}
case "$1" in
    compose)
        shift 3 # compose -f docker-compose.prod.yml
        case "$1" in
            ps) [ -n "${FAKE_PS_ID:-}" ] && echo "$FAKE_PS_ID"; exit 0 ;;
            pull)
                echo "pull $(active_tag)" >> "$FAKE_LOG"
                [ "${FAKE_PULL_FAIL:-0}" = 1 ] && exit 1
                exit 0 ;;
            up)
                tag=$(active_tag)
                echo "up $tag" >> "$FAKE_LOG"
                for bad in ${FAKE_UNHEALTHY:-}; do [ "$bad" = "$tag" ] && exit 1; done
                exit 0 ;;
            logs) exit 0 ;;
        esac ;;
    inspect)
        case "$3" in
            '{{.Image}}') echo "sha256:previmage" ;;
            '{{.Config.Image}}') echo "ghcr.io/acme/app:${FAKE_PREV_REF_TAG:-sha-old}" ;;
        esac
        exit 0 ;;
    tag)
        echo "tag $2 $3" >> "$FAKE_LOG"
        exit "${FAKE_TAG_FAIL:-0}" ;;
esac
echo "docker inesperado: $*" >&2
exit 99
EOF
    chmod +x "$WORK/bin/flock" "$WORK/bin/curl" "$WORK/bin/docker"
}

# Prepara un /opt/omniretail falso. $1 = contenido inicial del .env.
setup() {
    WORK=$(mktemp -d)
    mkdir -p "$WORK/app/scripts"
    cp "$SCRIPT" "$WORK/app/scripts/deploy-service.sh"
    : > "$WORK/app/docker-compose.prod.yml"
    printf '%s\n' "$1" > "$WORK/app/.env"
    : > "$WORK/calls.log"
    make_fakes
}

# Ejecuta el script con un entorno limpio. Variables FAKE_* y DEPLOY_* se pasan como "NOMBRE=valor".
run() {
    env -i PATH="$WORK/bin:/usr/bin:/bin" HOME="$WORK" FAKE_LOG="$WORK/calls.log" FAKE_TAG_VAR=WEB_TAG \
        DEPLOY_WAIT_TIMEOUT=5 "$@" sh "$WORK/app/scripts/deploy-service.sh" web "${TAG:-sha-new}" \
        > "$WORK/out.log" 2>&1
    echo $?
}

env_tag() { grep -E '^WEB_TAG=' "$WORK/app/.env" | tail -n 1 | cut -d= -f2-; }
calls() { tr '\n' '|' < "$WORK/calls.log"; }

check() { # check <descripcion> <obtenido> <esperado>
    if [ "$2" = "$3" ]; then
        echo "  ok   $1"
    else
        echo "  FAIL $1: esperado '$3', obtenido '$2'"
        FAILURES=$((FAILURES + 1))
        sed 's/^/       | /' "$WORK/out.log"
    fi
}

teardown() { rm -rf "$WORK"; }

# ---------- casos ----------
echo "despliegue exitoso"
setup "SECRET=s3cr3t
WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1)
check "termina en 0" "$code" 0
check "fija la etiqueta nueva" "$(env_tag)" sha-new
check "conserva el resto del .env" "$(grep -c '^SECRET=s3cr3t$' "$WORK/app/.env")" 1
check "descarga y levanta la nueva" "$(calls)" "pull sha-new|up sha-new|"
teardown

echo "primer despliegue (sin etiqueta ni contenedor previos)"
setup "SECRET=s3cr3t"
code=$(run)
check "termina en 0" "$code" 0
check "agrega la etiqueta" "$(env_tag)" sha-new
teardown

echo "pull fallido"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 FAKE_PULL_FAIL=1)
check "termina en 1" "$code" 1
check ".env intacto" "$(env_tag)" sha-old
check "no levanta nada" "$(calls)" "pull sha-new|"
teardown

echo "healthcheck fallido con version anterior: rollback"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la etiqueta previa" "$(env_tag)" sha-old
check "reetiqueta la imagen previa y la levanta" "$(calls)" \
    "pull sha-new|up sha-new|tag sha256:previmage ghcr.io/acme/app:sha-old|up sha-old|"
teardown

echo "healthcheck fallido sin etiqueta previa (corria latest): rollback a 'rollback'"
setup "SECRET=s3cr3t"
code=$(run FAKE_PS_ID=c1 FAKE_PREV_REF_TAG=latest FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "etiqueta rollback" "$(env_tag)" rollback
check "levanta la imagen previa" "$(calls)" \
    "pull sha-new|up sha-new|tag sha256:previmage ghcr.io/acme/app:rollback|up rollback|"
teardown

echo "healthcheck fallido sin contenedor previo"
setup "WEB_TAG=sha-old"
code=$(run FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la etiqueta previa" "$(env_tag)" sha-old
teardown

echo "fallo inesperado durante el rollback (docker tag)"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new FAKE_TAG_FAIL=1)
check "termina con error" "$([ "$code" -ne 0 ] && echo error)" error
check "restaura la etiqueta previa (trap)" "$(env_tag)" sha-old
teardown

echo "despliegue obsoleto (main avanzo)"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 DEPLOY_REPO=acme/app DEPLOY_SHA=aaa GITHUB_API_TOKEN=t FAKE_MAIN_SHA=bbb)
check "termina en 0" "$code" 0
check ".env intacto" "$(env_tag)" sha-old
check "no descarga ni levanta" "$(calls)" "curl|"
teardown

echo "commit vigente"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 DEPLOY_REPO=acme/app DEPLOY_SHA=aaa GITHUB_API_TOKEN=t FAKE_MAIN_SHA=aaa)
check "termina en 0" "$code" 0
check "despliega" "$(env_tag)" sha-new
teardown

echo "GitHub no responde"
setup "WEB_TAG=sha-old"
code=$(run FAKE_PS_ID=c1 DEPLOY_REPO=acme/app DEPLOY_SHA=aaa GITHUB_API_TOKEN=t FAKE_CURL_FAIL=1)
check "termina en 1" "$code" 1
check ".env intacto" "$(env_tag)" sha-old
check "no descarga" "$(calls)" "curl|"
teardown

echo "etiqueta invalida"
setup "WEB_TAG=sha-old"
code=$(TAG='sha-new;rm' run FAKE_PS_ID=c1)
check "termina en 2" "$code" 2
check ".env intacto" "$(env_tag)" sha-old
teardown

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES verificacion(es) fallida(s)."
    exit 1
fi
echo "Todas las pruebas pasaron."
