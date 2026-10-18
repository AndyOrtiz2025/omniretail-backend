#!/bin/sh
# Pruebas de deploy/scripts/deploy-service.sh y deploy/scripts/deploy-backend.sh sin Docker real: docker,
# flock y curl se reemplazan por dobles que registran las llamadas y simulan fallos.
# Uso:  sh deploy/scripts/tests/deploy-service.test.sh
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPTS="$HERE/.."
FAILURES=0
WORK=""
SHA=0123456789abcdef0123456789abcdef01234567

# ---------- dobles ----------
make_fakes() {
    mkdir -p "$WORK/bin"
    cat > "$WORK/bin/flock" <<'EOF'
#!/bin/sh
echo "flock" >> "$FAKE_LOG"
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
        sub=$1
        shift
        case "$sub" in
            ps) [ -n "${FAKE_PS_ID:-}" ] && echo "$FAKE_PS_ID"; exit 0 ;;
            pull)
                echo "pull $(active_tag)" >> "$FAKE_LOG"
                [ "${FAKE_PULL_FAIL:-0}" = 1 ] && exit 1
                exit 0 ;;
            up)
                services=""
                for a in "$@"; do
                    case "$a" in postgres | backend | web | caddy | prometheus | grafana | node-exporter | postgres-exporter)
                        services="$services $a" ;;
                    esac
                done
                services=${services# }
                if [ "$services" = "$FAKE_SERVICE" ]; then
                    tag=$(active_tag)
                    echo "up $tag" >> "$FAKE_LOG"
                    for bad in ${FAKE_UNHEALTHY:-}; do [ "$bad" = "$tag" ] && exit 1; done
                else
                    echo "up $services" >> "$FAKE_LOG"
                fi
                exit 0 ;;
            exec) echo "reload-caddy" >> "$FAKE_LOG"; exit 0 ;;
            kill) echo "sighup-prometheus" >> "$FAKE_LOG"; exit 0 ;;
            restart) echo "restart $*" >> "$FAKE_LOG"; exit 0 ;;
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
    image) echo "prune" >> "$FAKE_LOG"; exit 0 ;;
esac
echo "docker inesperado: $*" >&2
exit 99
EOF
    chmod +x "$WORK/bin/flock" "$WORK/bin/curl" "$WORK/bin/docker"
}

install_scripts() { # install_scripts <destino>
    mkdir -p "$1/lib"
    cp "$SCRIPTS/deploy-service.sh" "$SCRIPTS/deploy-backend.sh" "$1/"
    cp "$SCRIPTS/lib/deploy-common.sh" "$1/lib/"
}

# Arbol de configuracion de despliegue con la "version" $2 (old | new) en $1.
write_config() {
    mkdir -p "$1/caddy" "$1/monitoring/prometheus" "$1/monitoring/postgres"
    echo "compose-$2" > "$1/docker-compose.prod.yml"
    echo "caddy-$2" > "$1/caddy/Caddyfile"
    echo "prom-$2" > "$1/monitoring/prometheus/prometheus.yml"
    cat > "$1/monitoring/postgres/ensure-monitoring-role.sh" <<EOF
echo "ensure-role-$2" >> "\$FAKE_LOG"
EOF
}

# Prepara un /opt/omniretail falso. $1 = contenido inicial del .env.
setup() {
    WORK=$(mktemp -d)
    mkdir -p "$WORK/app"
    install_scripts "$WORK/app/scripts"
    write_config "$WORK/app" old
    printf '%s\n' "$1" > "$WORK/app/.env"
    : > "$WORK/calls.log"
    make_fakes
}

# Staging del commit $SHA con la configuracion nueva (lo que copia el CD por SCP).
setup_staging() {
    write_config "$WORK/app/.staging/$SHA" new
    echo "solo-en-la-version-vieja" > "$WORK/app/caddy/obsoleto.conf"
    install_scripts "$WORK/app/.staging/$SHA/scripts"
}

base_env() {
    echo PATH="$WORK/bin:/usr/bin:/bin" HOME="$WORK" FAKE_LOG="$WORK/calls.log" DEPLOY_WAIT_TIMEOUT=5
}

# deploy-service.sh web <TAG>. Variables FAKE_* y DEPLOY_* se pasan como "NOMBRE=valor".
run_web() {
    # shellcheck disable=SC2046 # base_env devuelve asignaciones separadas a proposito.
    env -i $(base_env) FAKE_TAG_VAR=WEB_TAG FAKE_SERVICE=web "$@" \
        sh "$WORK/app/scripts/deploy-service.sh" web "${TAG:-sha-new}" > "$WORK/out.log" 2>&1
    echo $?
}

# deploy-backend.sh ejecutado desde el staging, como lo hace el CD.
run_backend() {
    # shellcheck disable=SC2046
    (cd "$WORK/app" && env -i $(base_env) FAKE_TAG_VAR=BACKEND_TAG FAKE_SERVICE=backend \
        DEPLOY_REPO=acme/app GITHUB_API_TOKEN=t "$@" \
        sh ".staging/$SHA/scripts/deploy-backend.sh" "$SHA" sha-new) > "$WORK/out.log" 2>&1
    echo $?
}

env_tag() { grep -E "^$1=" "$WORK/app/.env" | tail -n 1 | cut -d= -f2-; }
calls() { tr '\n' '|' < "$WORK/calls.log"; }
file() { cat "$WORK/app/$1" 2>/dev/null || echo "<no existe>"; }
dir_id() { stat -c %i "$WORK/app/$1"; } # Inodo: un bind mount de carpeta apunta a el.
config_is() { # config_is <old|new>: los tres archivos de configuracion son de esa version
    echo "$(file docker-compose.prod.yml) $(file caddy/Caddyfile) $(file monitoring/prometheus/prometheus.yml)"
}

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

OLD_CONFIG="compose-old caddy-old prom-old"
NEW_CONFIG="compose-new caddy-new prom-new"

# =====================================================================
echo "== deploy-service.sh (web) =="

echo "despliegue exitoso"
setup "SECRET=s3cr3t
WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1)
check "termina en 0" "$code" 0
check "fija la etiqueta nueva" "$(env_tag WEB_TAG)" sha-new
check "conserva el resto del .env" "$(grep -c '^SECRET=s3cr3t$' "$WORK/app/.env")" 1
check "lock -> pull -> up -> prune (prune dentro del lock)" "$(calls)" "flock|pull sha-new|up sha-new|prune|"
teardown

echo "primer despliegue (sin etiqueta ni contenedor previos)"
setup "SECRET=s3cr3t"
code=$(run_web)
check "termina en 0" "$code" 0
check "agrega la etiqueta" "$(env_tag WEB_TAG)" sha-new
teardown

echo "pull fallido"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_PULL_FAIL=1)
check "termina en 1" "$code" 1
check ".env intacto" "$(env_tag WEB_TAG)" sha-old
check "no levanta ni limpia nada" "$(calls)" "flock|pull sha-new|"
teardown

echo "healthcheck fallido con version anterior: rollback"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la etiqueta previa" "$(env_tag WEB_TAG)" sha-old
check "reetiqueta la imagen previa y la levanta" "$(calls)" \
    "flock|pull sha-new|up sha-new|tag sha256:previmage ghcr.io/acme/app:sha-old|up sha-old|"
teardown

echo "fallan la imagen nueva Y su rollback"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY="sha-new sha-old")
check "termina en 1" "$code" 1
check "deja la etiqueta previa" "$(env_tag WEB_TAG)" sha-old
check "intenta la nueva y luego la previa" "$(calls)" \
    "flock|pull sha-new|up sha-new|tag sha256:previmage ghcr.io/acme/app:sha-old|up sha-old|"
check "lo reporta" "$(grep -c 'tampoco quedo sana' "$WORK/out.log")" 1
teardown

echo "healthcheck fallido sin etiqueta previa (corria latest): rollback a 'rollback'"
setup "SECRET=s3cr3t"
code=$(run_web FAKE_PS_ID=c1 FAKE_PREV_REF_TAG=latest FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "etiqueta rollback" "$(env_tag WEB_TAG)" rollback
teardown

echo "healthcheck fallido sin contenedor previo"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la etiqueta previa" "$(env_tag WEB_TAG)" sha-old
teardown

echo "fallo inesperado durante el rollback (docker tag)"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new FAKE_TAG_FAIL=1)
check "termina con error" "$([ "$code" -ne 0 ] && echo error)" error
check "restaura la etiqueta previa (trap)" "$(env_tag WEB_TAG)" sha-old
teardown

echo "commit obsoleto al tomar el lock"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 DEPLOY_REPO=acme/app DEPLOY_SHA=aaa GITHUB_API_TOKEN=t FAKE_MAIN_SHA=bbb)
check "termina en 0" "$code" 0
check ".env intacto" "$(env_tag WEB_TAG)" sha-old
check "valida bajo el lock y no toca nada" "$(calls)" "flock|curl|"
teardown

echo "GitHub no responde"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 DEPLOY_REPO=acme/app DEPLOY_SHA=aaa GITHUB_API_TOKEN=t FAKE_CURL_FAIL=1)
check "termina en 1" "$code" 1
check "no descarga" "$(calls)" "flock|curl|"
teardown

echo "etiqueta invalida"
setup "WEB_TAG=sha-old"
code=$(TAG='sha-new;rm' run_web FAKE_PS_ID=c1)
check "termina en 2" "$code" 2
check ".env intacto" "$(env_tag WEB_TAG)" sha-old
teardown

# =====================================================================
echo "== deploy-backend.sh (staging + promocion bajo el lock) =="

echo "commit vigente: promueve, despliega y recarga"
setup "SECRET=s3cr3t
BACKEND_TAG=sha-old"
setup_staging
caddy_dir=$(dir_id caddy)
prom_dir=$(dir_id monitoring/prometheus)
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=$SHA)
check "termina en 0" "$code" 0
check "configuracion nueva promovida" "$(config_is)" "$NEW_CONFIG"
check "borra lo que ya no existe en el repo" "$(file caddy/obsoleto.conf)" "<no existe>"
check "no reemplaza la carpeta de caddy (bind mount)" "$(dir_id caddy)" "$caddy_dir"
check "no reemplaza la carpeta de prometheus (bind mount)" "$(dir_id monitoring/prometheus)" "$prom_dir"
check "etiqueta nueva" "$(env_tag BACKEND_TAG)" sha-new
check "un solo lock para toda la secuencia, en orden" "$(calls)" \
    "flock|curl|up postgres|ensure-role-new|pull sha-new|up sha-new|prune|up caddy prometheus grafana node-exporter postgres-exporter|reload-caddy|sighup-prometheus|restart grafana|"
check "borra el staging" "$(file .staging/$SHA/docker-compose.prod.yml)" "<no existe>"
check "no deja respaldos" "$(find "$WORK/app/.staging" -mindepth 1 | wc -l | tr -d ' ')" 0
teardown

echo "commit que se vuelve obsoleto tras la comprobacion del runner (main avanzo)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=ffffffffffffffffffffffffffffffffffffffff)
check "termina en 0" "$code" 0
check "NO promueve configuracion" "$(config_is)" "$OLD_CONFIG"
check "conserva los archivos viejos" "$(file caddy/obsoleto.conf)" "solo-en-la-version-vieja"
check "NO toca contenedores ni recarga nada" "$(calls)" "flock|curl|"
check ".env intacto" "$(env_tag BACKEND_TAG)" sha-old
check "borra su staging" "$(file .staging/$SHA/docker-compose.prod.yml)" "<no existe>"
teardown

echo "backend no queda sano (rollback de la imagen)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=$SHA FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "restaura archivos borrados" "$(file caddy/obsoleto.conf)" "solo-en-la-version-vieja"
check "etiqueta previa" "$(env_tag BACKEND_TAG)" sha-old
check "NO recarga Caddy/Prometheus/Grafana" "$(calls | grep -c 'reload\|sighup\|restart')" 0
teardown

echo "fallan la imagen nueva del backend Y su rollback"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=$SHA FAKE_UNHEALTHY="sha-new sha-old")
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "etiqueta previa" "$(env_tag BACKEND_TAG)" sha-old
check "NO recarga nada" "$(calls | grep -c 'reload\|sighup\|restart')" 0
teardown

echo "pull del backend fallido"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=$SHA FAKE_PULL_FAIL=1)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check ".env intacto" "$(env_tag BACKEND_TAG)" sha-old
teardown

echo "GitHub no responde"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_CURL_FAIL=1)
check "termina en 1" "$code" 1
check "NO promueve configuracion" "$(config_is)" "$OLD_CONFIG"
check "no toca contenedores" "$(calls)" "flock|curl|"
teardown

echo "script ejecutado fuera de su staging"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$( (cd "$WORK/app" && env -i PATH="$WORK/bin:/usr/bin:/bin" FAKE_LOG="$WORK/calls.log" \
    sh ".staging/$SHA/scripts/deploy-backend.sh" ffffffffffffffffffffffffffffffffffffffff sha-new) \
    > "$WORK/out.log" 2>&1; echo $?)
check "termina en 2" "$code" 2
check "no toca nada" "$(calls)" ""
teardown

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES verificacion(es) fallida(s)."
    exit 1
fi
echo "Todas las pruebas pasaron."
