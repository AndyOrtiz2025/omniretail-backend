#!/bin/sh
# Crea o actualiza el usuario de monitoreo de PostgreSQL (rol pg_monitor, sin acceso a datos).
# Lo ejecuta el CD en el VPS desde /opt/omniretail, con postgres ya levantado. Idempotente.
set -eu

# Lee una variable del .env sin hacer `source` (otros valores, como MAIL_FROM, tienen < > y espacios).
env_value() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- || true; }

MON_USER=$(env_value POSTGRES_EXPORTER_USER)
MON_USER=${MON_USER:-omniretail_monitor}
MON_PASSWORD=$(env_value POSTGRES_EXPORTER_PASSWORD)
if [ -z "$MON_PASSWORD" ]; then
    echo "Define POSTGRES_EXPORTER_PASSWORD en .env" >&2
    exit 1
fi

docker compose -f docker-compose.prod.yml exec -T \
    -e MON_USER="$MON_USER" -e MON_PASSWORD="$MON_PASSWORD" postgres \
    sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v mon_user="$MON_USER" -v mon_password="$MON_PASSWORD" -v db_name="$POSTGRES_DB"' \
    < monitoring/postgres/ensure-monitoring-role.sql

echo "Usuario de monitoreo '$MON_USER' listo."
