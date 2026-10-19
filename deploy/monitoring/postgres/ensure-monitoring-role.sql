-- Usuario de solo lectura para postgres-exporter. Idempotente: el CD lo ejecuta en cada despliegue.
-- Variables psql: mon_user, mon_password, db_name.
-- pg_monitor es el rol predefinido de PostgreSQL para monitoreo: lee estadisticas y configuracion,
-- pero no puede leer ni modificar datos de las tablas de la aplicacion.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN', :'mon_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'mon_user') \gexec

ALTER ROLE :"mon_user" WITH LOGIN PASSWORD :'mon_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;

GRANT pg_monitor TO :"mon_user";
GRANT CONNECT ON DATABASE :"db_name" TO :"mon_user";
