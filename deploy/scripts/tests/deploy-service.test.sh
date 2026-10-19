#!/bin/sh
# Pruebas de deploy/scripts/deploy-service.sh y deploy/scripts/deploy-backend.sh sin Docker real: docker,
# flock y curl se reemplazan por dobles que registran cada paso y permiten inyectar fallos en cualquiera.
# Uso:  sh deploy/scripts/tests/deploy-service.test.sh
#
# Inyeccion de fallos en el doble de docker (por nombre de evento, ver `emit`):
#   FAKE_FAIL="evento ..."       el evento falla siempre.
#   FAKE_FAIL_ONCE="evento ..."  falla solo la primera vez (p. ej. la fase final, y no su reintento al volver atras).
#   FAKE_TERM_ON=evento          al llegar a ese evento manda SIGTERM al script que lo llamo (corte del SSH).
#   FAKE_KILL_ON=evento          igual, pero SIGKILL: no corre ningun trap (caida del VPS, OOM killer).
#   FAKE_UNHEALTHY="sha-x ..."   el servicio desplegado con esas etiquetas no queda sano.
# Estado de los contenedores: FAKE_PS_ID=<id> hace que todos los servicios existan y esten corriendo;
# FAKE_STOPPED="svc ..." los deja creados pero detenidos. Sin FAKE_PS_ID no existe ninguno (primer despliegue).
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
    # cp que falla cuando el ORIGEN contiene FAKE_CP_FAIL_FROM (p. ej. al restaurar desde el respaldo, pero
    # no al crearlo). Los scripts siempre llaman `cp <origen> <destino>`.
    cat > "$WORK/bin/cp" <<'EOF'
#!/bin/sh
case "$1" in *"${FAKE_CP_FAIL_FROM:-<nunca>}"*) echo "cp-fail" >> "$FAKE_LOG"; exit 1 ;; esac
exec /bin/cp "$@"
EOF
    cat > "$WORK/bin/docker" <<'EOF'
#!/bin/sh
# Registra el evento y aplica la inyeccion de fallos configurada.
emit() {
    echo "$1" >> "$FAKE_LOG"
    if [ "${FAKE_TERM_ON:-}" = "$1" ]; then kill -TERM "$PPID"; fi
    if [ "${FAKE_KILL_ON:-}" = "$1" ]; then kill -KILL "$PPID"; sleep 1; fi
    for f in ${FAKE_FAIL:-}; do [ "$f" = "$1" ] && exit 1; done
    for f in ${FAKE_FAIL_ONCE:-}; do
        if [ "$f" = "$1" ] && [ ! -e "$FAKE_STATE/once-$1" ]; then : > "$FAKE_STATE/once-$1"; exit 1; fi
    done
    return 0
}
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
            ps)
                all=false
                for a in "$@"; do [ "$a" = -a ] && all=true; done
                for svc in "$@"; do :; done # ultimo argumento: el servicio
                [ -n "${FAKE_PS_ID:-}" ] || exit 0
                for st in ${FAKE_STOPPED:-}; do
                    if [ "$st" = "$svc" ]; then
                        if [ "$all" = true ]; then echo "$FAKE_PS_ID"; fi
                        exit 0
                    fi
                done
                echo "$FAKE_PS_ID"
                exit 0 ;;
            config) emit config ;;
            run)
                case "$*" in
                    *promtool*) emit validate-prometheus ;;
                    *) emit validate-caddy ;;
                esac ;;
            pull)
                emit "pull-$(active_tag)" ;;
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
                    emit "up-$tag"
                    # Con que definicion de compose quedo creado el contenedor (para probar la reconciliacion).
                    cat docker-compose.prod.yml > "$FAKE_STATE/service-compose" 2>/dev/null || true
                    for bad in ${FAKE_UNHEALTHY:-}; do [ "$bad" = "$tag" ] && exit 1; done
                elif [ "$services" = postgres ]; then
                    emit up-postgres
                else
                    emit up-edge
                fi ;;
            exec) emit reload-caddy ;;
            kill) emit sighup-prometheus ;;
            restart) emit restart-grafana ;;
            stop) emit "stop-$1" ;;
            rm) for a in "$@"; do case "$a" in -*) ;; *) emit "rm-$a" ;; esac; done ;;
            logs) ;;
        esac
        exit 0 ;;
    inspect)
        case "$3" in
            '{{.Image}}') echo "sha256:previmage" ;;
            '{{.Config.Image}}') echo "ghcr.io/acme/app:${FAKE_PREV_REF_TAG:-sha-old}" ;;
        esac
        exit 0 ;;
    tag) emit "tag-$3"; exit 0 ;;
    image) emit prune; exit 0 ;;
esac
echo "docker inesperado: $*" >&2
exit 99
EOF
    chmod +x "$WORK/bin/flock" "$WORK/bin/curl" "$WORK/bin/cp" "$WORK/bin/docker"
}

install_scripts() { # install_scripts <destino>
    mkdir -p "$1/lib"
    /bin/cp "$SCRIPTS/deploy-service.sh" "$SCRIPTS/deploy-backend.sh" "$1/"
    /bin/cp "$SCRIPTS/lib/deploy-common.sh" "$1/lib/"
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
    mkdir -p "$WORK/app" "$WORK/state"
    install_scripts "$WORK/app/scripts"
    write_config "$WORK/app" old
    echo "solo-en-la-version-vieja" > "$WORK/app/caddy/obsoleto.conf"
    printf '%s\n' "$1" > "$WORK/app/.env"
    : > "$WORK/calls.log"
    make_fakes
}

# Primer despliegue: el VPS solo tiene el .env.
setup_empty() {
    WORK=$(mktemp -d)
    mkdir -p "$WORK/app" "$WORK/state"
    printf '%s\n' "$1" > "$WORK/app/.env"
    : > "$WORK/calls.log"
    make_fakes
}

# Staging del commit $SHA con la configuracion nueva (lo que copia el CD por SCP).
setup_staging() {
    write_config "$WORK/app/.staging/$SHA" new
    install_scripts "$WORK/app/.staging/$SHA/scripts"
}

base_env() {
    echo PATH="$WORK/bin:/usr/bin:/bin" HOME="$WORK" FAKE_LOG="$WORK/calls.log" FAKE_STATE="$WORK/state" \
        DEPLOY_WAIT_TIMEOUT=5
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
        DEPLOY_REPO=acme/app GITHUB_API_TOKEN=t FAKE_MAIN_SHA=$SHA "$@" \
        sh ".staging/$SHA/scripts/deploy-backend.sh" "$SHA" sha-new) > "$WORK/out.log" 2>&1
    echo $?
}

env_tag() { grep -E "^$1=" "$WORK/app/.env" | tail -n 1 | cut -d= -f2-; }
calls() { tr '\n' '|' < "$WORK/calls.log"; }
count() { grep -c "^$1\$" "$WORK/calls.log"; }
file() { cat "$WORK/app/$1" 2>/dev/null || echo "<no existe>"; }
dir_id() { stat -c %i "$WORK/app/$1"; } # Inodo: un bind mount de carpeta apunta a el.
config_is() { echo "$(file docker-compose.prod.yml) $(file caddy/Caddyfile) $(file monitoring/prometheus/prometheus.yml)"; }
recovery_dirs() { find "$WORK/app/.recovery" -mindepth 1 -maxdepth 1 -type d ! -name '*.tmp' 2>/dev/null | wc -l | tr -d ' '; }
recovery_dir() { find "$WORK/app/.recovery" -mindepth 1 -maxdepth 1 -type d ! -name '*.tmp' 2>/dev/null | head -n 1; }
service_compose() { cat "$WORK/state/service-compose" 2>/dev/null || echo "<ninguno>"; }
config_left() { # archivos de configuracion que quedaron en el VPS (primer despliegue fallido: ninguno)
    for item in docker-compose.prod.yml caddy monitoring scripts; do
        if [ -e "$WORK/app/$item" ]; then printf '%s ' "$item"; fi
    done
    echo
}
rm_calls() { grep '^rm-' "$WORK/calls.log" | sort -u | tr '\n' ' '; }
staging_left() { find "$WORK/app/.staging" -mindepth 1 2>/dev/null | wc -l | tr -d ' '; }

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
VALIDATE="config|validate-caddy|validate-prometheus"
EDGE="up-edge|reload-caddy|sighup-prometheus|restart-grafana|up-edge"

# =====================================================================
echo "== deploy-service.sh (web) =="

echo "despliegue exitoso"
setup "SECRET=s3cr3t
WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1)
check "termina en 0" "$code" 0
check "fija la etiqueta nueva" "$(env_tag WEB_TAG)" sha-new
check "conserva el resto del .env" "$(grep -c '^SECRET=s3cr3t$' "$WORK/app/.env")" 1
check "lock -> pull -> up -> prune (prune dentro del lock)" "$(calls)" "flock|pull-sha-new|up-sha-new|prune|"
teardown

echo "DEPLOY_PRUNE=0 no limpia imagenes"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 DEPLOY_PRUNE=0)
check "termina en 0" "$code" 0
check "sin prune" "$(calls)" "flock|pull-sha-new|up-sha-new|"
teardown

echo "primer despliegue (sin etiqueta ni contenedor previos)"
setup "SECRET=s3cr3t"
code=$(run_web)
check "termina en 0" "$code" 0
check "agrega la etiqueta" "$(env_tag WEB_TAG)" sha-new
teardown

echo "pull fallido"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_FAIL=pull-sha-new)
check "termina en 1" "$code" 1
check ".env intacto" "$(env_tag WEB_TAG)" sha-old
check "no levanta ni limpia nada" "$(calls)" "flock|pull-sha-new|"
teardown

echo "healthcheck fallido con version anterior: rollback"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la etiqueta previa" "$(env_tag WEB_TAG)" sha-old
check "reetiqueta la imagen previa y la levanta, sin prune" "$(calls)" \
    "flock|pull-sha-new|up-sha-new|tag-ghcr.io/acme/app:sha-old|up-sha-old|"
teardown

echo "fallan la imagen nueva Y su rollback"
setup "WEB_TAG=sha-old"
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY="sha-new sha-old")
check "termina en 3 (sin version sana)" "$code" 3
check "deja la etiqueta previa" "$(env_tag WEB_TAG)" sha-old
check "intenta la nueva y luego la previa" "$(calls)" \
    "flock|pull-sha-new|up-sha-new|tag-ghcr.io/acme/app:sha-old|up-sha-old|"
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
code=$(run_web FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new FAKE_FAIL=tag-ghcr.io/acme/app:sha-old)
check "termina en 3" "$code" 3
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
echo "== deploy-backend.sh: camino feliz y obsolescencia =="

echo "commit vigente: valida, promueve, despliega, fase final y prune al final"
setup "SECRET=s3cr3t
BACKEND_TAG=sha-old"
setup_staging
caddy_dir=$(dir_id caddy)
prom_dir=$(dir_id monitoring/prometheus)
code=$(run_backend FAKE_PS_ID=c1)
check "termina en 0" "$code" 0
check "configuracion nueva promovida" "$(config_is)" "$NEW_CONFIG"
check "borra lo que ya no existe en el repo" "$(file caddy/obsoleto.conf)" "<no existe>"
check "no reemplaza la carpeta de caddy (bind mount)" "$(dir_id caddy)" "$caddy_dir"
check "no reemplaza la carpeta de prometheus (bind mount)" "$(dir_id monitoring/prometheus)" "$prom_dir"
check "etiqueta nueva" "$(env_tag BACKEND_TAG)" sha-new
check "un solo lock, valida antes de tocar contenedores, prune solo al final" "$(calls)" \
    "flock|curl|$VALIDATE|up-postgres|ensure-role-new|pull-sha-new|up-sha-new|$EDGE|prune|"
check "no deja staging ni respaldos" "$(staging_left)" 0
check "no deja recuperacion" "$(recovery_dirs)" 0
teardown

echo "primer despliegue (VPS solo con .env)"
setup_empty "SECRET=s3cr3t"
setup_staging
code=$(run_backend)
check "termina en 0" "$code" 0
check "configuracion promovida" "$(config_is)" "$NEW_CONFIG"
check "etiqueta nueva" "$(env_tag BACKEND_TAG)" sha-new
teardown

echo "commit que se vuelve obsoleto tras la comprobacion del runner (main avanzo)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_MAIN_SHA=ffffffffffffffffffffffffffffffffffffffff)
check "termina en 0" "$code" 0
check "NO promueve configuracion" "$(config_is)" "$OLD_CONFIG"
check "conserva los archivos viejos" "$(file caddy/obsoleto.conf)" "solo-en-la-version-vieja"
check "NO toca contenedores, NO recarga, NO prune" "$(calls)" "flock|curl|"
check ".env intacto" "$(env_tag BACKEND_TAG)" sha-old
check "borra su staging" "$(staging_left)" 0
teardown

echo "GitHub no responde"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_CURL_FAIL=1)
check "termina en 1" "$code" 1
check "NO promueve configuracion" "$(config_is)" "$OLD_CONFIG"
check "no toca contenedores" "$(calls)" "flock|curl|"
teardown

# =====================================================================
echo "== deploy-backend.sh: fallos antes de la fase final =="

echo "configuracion nueva invalida (Caddyfile)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL=validate-caddy)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "restaura archivos borrados" "$(file caddy/obsoleto.conf)" "solo-en-la-version-vieja"
check "NO toca ningun contenedor" "$(calls)" "flock|curl|config|validate-caddy|"
check ".env intacto" "$(env_tag BACKEND_TAG)" sha-old
check "no deja recuperacion (se restauro)" "$(recovery_dirs)" 0
teardown

echo "pull del backend fallido"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL=pull-sha-new)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check ".env intacto" "$(env_tag BACKEND_TAG)" sha-old
check "reconcilia postgres y el backend con la config anterior; no recarga ni limpia" "$(calls)" \
    "flock|curl|$VALIDATE|up-postgres|ensure-role-new|pull-sha-new|up-postgres|tag-ghcr.io/acme/app:sha-old|up-sha-old|"
teardown

echo "backend no queda sano (deploy-service vuelve a la imagen anterior)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "etiqueta previa" "$(env_tag BACKEND_TAG)" sha-old
check "NO toca la fase final ni hace prune" "$(calls | grep -c 'edge\|reload\|sighup\|restart\|prune')" 0
check "no deja recuperacion" "$(recovery_dirs)" 0
teardown

echo "fallan la imagen nueva del backend Y su rollback"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_UNHEALTHY="sha-new sha-old")
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "NO toca la fase final ni hace prune" "$(calls | grep -c 'edge\|reload\|sighup\|restart\|prune')" 0
check "conserva el respaldo para recuperacion manual" "$(recovery_dirs)" 1
check "informa la ubicacion" "$(grep -c '.recovery/' "$WORK/out.log")" 1
teardown

# =====================================================================
echo "== deploy-backend.sh: fallos en la fase final (Caddy/Prometheus/Grafana) =="

for failing in up-edge reload-caddy sighup-prometheus restart-grafana; do
    echo "falla '$failing': vuelta atras completa"
    setup "BACKEND_TAG=sha-old"
    setup_staging
    code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL_ONCE=$failing)
    check "termina en 1" "$code" 1
    check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
    check "vuelve el backend a la imagen anterior" "$(env_tag BACKEND_TAG)" sha-old
    check "reetiqueta la imagen previa y la levanta" "$(count tag-ghcr.io/acme/app:sha-old)|$(count up-sha-old)" "1|1"
    check "reaplica la fase final con la config anterior" "$(calls | sed 's/.*up-sha-old|//')" "$EDGE|"
    check "no hace prune" "$(count prune)" 0
    check "no deja recuperacion (se restauro)" "$(recovery_dirs)" 0
    teardown
done

echo "falla la fase final Y la imagen anterior del backend no queda sana"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL_ONCE=reload-caddy FAKE_UNHEALTHY=sha-old)
check "termina en 1" "$code" 1
check "restaura la configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "conserva el respaldo" "$(recovery_dirs)" 1
check "no hace prune" "$(count prune)" 0
teardown

echo "falla la fase final y tambien su reintento con la config anterior"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL=reload-caddy)
check "termina en 1" "$code" 1
check "conserva el respaldo" "$(recovery_dirs)" 1
teardown

# =====================================================================
echo "== deploy-backend.sh: restauracion fallida e interrupciones =="

echo "falla la restauracion de la configuracion (promote del respaldo)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL_ONCE=reload-caddy FAKE_CP_FAIL_FROM=.recovery/)
check "termina en 1" "$code" 1
check "conserva el respaldo" "$(recovery_dirs)" 1
rec=$(recovery_dir)
check "el respaldo tiene la configuracion anterior completa" \
    "$(cat "$rec/config/docker-compose.prod.yml") $(cat "$rec/config/caddy/Caddyfile") $(cat "$rec/config/caddy/obsoleto.conf")" \
    "compose-old caddy-old solo-en-la-version-vieja"
check "deja instrucciones con la imagen previa" "$(grep -c 'docker tag sha256:previmage ghcr.io/acme/app:sha-old' "$rec/LEEME.txt")" 1
check "informa la ubicacion" "$(grep -c "$rec" "$WORK/out.log")" 1
check "no intenta recargar con config a medias" "$(calls | sed 's/.*cp-fail|//')" ""
check "no hace prune" "$(count prune)" 0
teardown

echo "interrumpido a mitad (SIGTERM, p. ej. corte del SSH)"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_TERM_ON=up-postgres)
check "termina en 143" "$code" 143
check "conserva el respaldo" "$(recovery_dirs)" 1
check "registra la causa" "$(grep -c 'interrumpido a mitad' "$(recovery_dir)/LEEME.txt")" 1
check "no sigue desplegando" "$(count pull-sha-new)" 0
teardown

echo "recuperacion pendiente de un despliegue anterior: el backend no avanza"
setup "BACKEND_TAG=sha-old"
setup_staging
mkdir -p "$WORK/app/.recovery/20261018T000000Z-$SHA/config"
code=$(run_backend FAKE_PS_ID=c1)
check "termina en 1" "$code" 1
check "NO promueve configuracion" "$(config_is)" "$OLD_CONFIG"
check "no consulta GitHub ni toca contenedores" "$(calls)" "flock|"
check "lo informa" "$(grep -c 'recuperacion manual pendiente' "$WORK/out.log")" 1
check "no toca la recuperacion pendiente" "$(recovery_dirs)" 1
teardown

echo "recuperacion pendiente: el CD de web tampoco avanza"
setup "WEB_TAG=sha-old"
mkdir -p "$WORK/app/.recovery/20261018T000000Z-$SHA/config"
code=$(run_web FAKE_PS_ID=c1)
check "termina en 1" "$code" 1
check ".env intacto" "$(env_tag WEB_TAG)" sha-old
check "no toca contenedores" "$(calls)" "flock|"
teardown

echo "despues de una interrupcion, el siguiente despliegue queda bloqueado hasta la intervencion manual"
setup "BACKEND_TAG=sha-old"
setup_staging
run_backend FAKE_PS_ID=c1 FAKE_TERM_ON=up-postgres > /dev/null
: > "$WORK/calls.log"
setup_staging
code=$(run_backend FAKE_PS_ID=c1)
check "el segundo intento termina en 1" "$code" 1
check "y no toca nada" "$(calls)" "flock|"
teardown

# =====================================================================
echo "== reconciliacion, primer despliegue y marca durable =="

echo "escenario 1: deploy-service ya volvio a la imagen anterior; el backend se reconcilia con el compose anterior"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "configuracion anterior" "$(config_is)" "$OLD_CONFIG"
check "etiqueta anterior" "$(env_tag BACKEND_TAG)" sha-old
check "el contenedor del backend quedo creado con el compose ANTERIOR" "$(service_compose)" compose-old
check "no deja marca (vuelta atras completa)" "$(recovery_dirs)" 0
teardown

echo "escenario 1b: falla la fase final; el backend tambien queda con el compose anterior"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_FAIL_ONCE=reload-caddy)
check "termina en 1" "$code" 1
check "el contenedor del backend quedo creado con el compose ANTERIOR" "$(service_compose)" compose-old
teardown

for failing in validate-caddy pull-sha-new up-sha-new reload-caddy restart-grafana; do
    echo "escenario 2: primer despliegue fallido en '$failing': no queda nada nuevo"
    setup_empty "SECRET=s3cr3t"
    setup_staging
    if [ "$failing" = up-sha-new ]; then
        code=$(run_backend FAKE_UNHEALTHY=sha-new)
    else
        code=$(run_backend FAKE_FAIL_ONCE=$failing)
    fi
    check "termina en 1" "$code" 1
    check "no quedan archivos de configuracion nuevos" "$(config_left)" ""
    check "no queda etiqueta" "$(env_tag BACKEND_TAG)" ""
    check "el .env sigue intacto" "$(grep -c '^SECRET=s3cr3t$' "$WORK/app/.env")" 1
    # Cada servicio que este despliegue alcanzo a crear se elimina (sin borrar volumenes).
    case "$failing" in
        validate-caddy) expected="" ;;
        pull-sha-new | up-sha-new) expected="rm-backend rm-postgres " ;;
        *) expected="rm-backend rm-caddy rm-grafana rm-node-exporter rm-postgres rm-postgres-exporter rm-prometheus " ;;
    esac
    check "elimina los contenedores que creo" "$(rm_calls)" "$expected"
    check "no deja marca" "$(recovery_dirs)" 0
    teardown
done

echo "servicio que estaba DETENIDO antes del despliegue: la vuelta atras lo deja detenido"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_STOPPED=grafana FAKE_FAIL_ONCE=reload-caddy)
check "termina en 1" "$code" 1
check "vuelve a detener grafana al final" "$(tail -n 1 "$WORK/calls.log")" stop-grafana
check "no lo elimina" "$(count rm-grafana)" 0
teardown

echo "escenario 3: SIGKILL a mitad (sin traps): la marca durable queda y bloquea"
setup "BACKEND_TAG=sha-old"
setup_staging
code=$(run_backend FAKE_PS_ID=c1 FAKE_KILL_ON=up-postgres)
check "muere por SIGKILL" "$code" 137
check "deja una marca en .recovery" "$(recovery_dirs)" 1
rec=$(recovery_dir)
check "la marca contiene la configuracion anterior completa" \
    "$(cat "$rec/config/docker-compose.prod.yml" 2>/dev/null) $(cat "$rec/config/caddy/Caddyfile" 2>/dev/null) $(cat "$rec/config/caddy/obsoleto.conf" 2>/dev/null)" \
    "compose-old caddy-old solo-en-la-version-vieja"
check "la marca explica como volver a la imagen previa" "$(grep -c 'docker tag sha256:previmage' "$rec/LEEME.txt" 2>/dev/null)" 1
check "no queda respaldo suelto en .staging" "$(find "$WORK/app/.staging" -name 'backup.*' 2>/dev/null | wc -l | tr -d ' ')" 0
: > "$WORK/calls.log"
setup_staging
code=$(run_backend FAKE_PS_ID=c1)
check "el siguiente despliegue queda bloqueado" "$code" 1
check "y no toca nada" "$(calls)" "flock|"
: > "$WORK/calls.log"
code=$(run_web FAKE_PS_ID=c1)
check "el CD de web tambien queda bloqueado" "$code" 1
teardown

echo "escenario 3b: SIGKILL mientras se escribia el respaldo (nada promovido): no bloquea y se limpia"
setup "BACKEND_TAG=sha-old"
setup_staging
mkdir -p "$WORK/app/.recovery/20261018T000000Z-$SHA.tmp/config"
code=$(run_backend FAKE_PS_ID=c1)
check "despliega" "$code" 0
check "limpia el respaldo a medio escribir" "$(find "$WORK/app/.recovery" -name '*.tmp' | wc -l | tr -d ' ')" 0
teardown

echo "web: primer despliegue fallido no deja el contenedor nuevo"
setup "SECRET=s3cr3t"
code=$(run_web FAKE_UNHEALTHY=sha-new)
check "termina en 1" "$code" 1
check "elimina el contenedor nuevo" "$(count rm-web)" 1
check "sin etiqueta" "$(env_tag WEB_TAG)" ""
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
