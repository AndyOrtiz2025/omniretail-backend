# Despliegue en VPS

Stack de producción: **Caddy** (reverse proxy, HTTPS automático con dominio) → **web** (Next.js) y **backend** (Spring Boot) → **PostgreSQL 16**.
Las imágenes se construyen en GitHub Actions (`cd.yml` de cada repo) y se publican en GHCR. El VPS solo hace `docker compose pull && up`.

### Flujo del despliegue automático

```
push a main ──► CD ─► CI completa (tests, Sonar, gitleaks, SCA) ──verde──► imagen sha-xxxxxxx en GHCR ──► VPS
                                                                └──rojo──► no se despliega nada
```

- **Solo despliega lo que pasó la CI.** El CD llama a `ci.yml` como workflow reutilizable (`workflow_call`) y solo si queda en verde construye y despliega. Siempre construye el commit del push, nunca código de otro evento o de un fork.
- **Etiquetas inmutables.** Cada despliegue usa `sha-<commit>` y la deja escrita en el `.env` del VPS (`BACKEND_TAG` / `WEB_TAG`), así un reinicio o un `up -d` manual levantan siempre la misma versión.
- **Healthcheck y rollback automático.** `scripts/deploy-service.sh` descarga la imagen **antes** de tocar el `.env` (si el pull falla no cambia nada), espera a que el contenedor quede sano y, si no lo logra, vuelve a la imagen que estaba corriendo y restaura la etiqueta previa. Cualquier otro fallo con el `.env` ya modificado también la restaura. El job queda en rojo.
- **Sin despliegues obsoletos.** Justo antes de desplegar, el CD comprueba que el commit siga siendo la punta de `main`; el VPS repite la comprobación con el lock tomado. Un run viejo nunca pisa uno más nuevo.
- **Configuración por staging, todo o nada.** El CD del backend copia `docker-compose.prod.yml`, `caddy/`, `monitoring/` y `scripts/` a `.staging/<sha>/` y ejecuta `scripts/deploy-backend.sh` desde ahí. Con el lock tomado: revalida la vigencia (si es obsoleto, sale **sin tocar nada**); registra el estado previo (cada contenedor corriendo, detenido o ausente; imagen y etiqueta del backend; qué archivos de configuración existían); crea la **marca durable** (ver abajo); promueve la configuración nueva archivo por archivo (sin reemplazar carpetas, para no romper los bind mounts) y la **valida antes de tocar contenedores** (`compose config`, `caddy validate`, `promtool check config`). Luego despliega el backend y la fase final (Caddy, Prometheus, Grafana y exporters, con healthcheck). Si **cualquier** paso falla, deja el VPS **exactamente como estaba**: elimina los contenedores que no existían (los volúmenes se conservan), restaura la configuración y borra los archivos que no existían, **recrea con la configuración anterior** Postgres, el backend (imagen previa) y los servicios de borde, y vuelve a detener lo que estaba detenido. En un primer despliegue fallido no queda nada nuevo. `docker image prune` corre solo cuando todo terminó en verde.
- **Un solo lock para todo.** Backend y web comparten `/opt/omniretail/.deploy.lock`: la promoción de configuración, los pulls, healthchecks, rollbacks, recargas y `docker image prune` nunca se cruzan entre repos.
- **Credenciales aisladas.** Cada despliegue hace `docker login` en un `DOCKER_CONFIG` temporal propio que se borra al terminar: los CD de backend y web no se pisan las credenciales de GHCR.
- **Probado en CI.** `deploy/scripts/tests/deploy-service.test.sh` (182 verificaciones, sin Docker) cubre pull fallido, rollback por healthcheck y su propio fallo, config inválida, fallos en cada paso de la fase final, reconciliación del backend con el compose anterior, primeros despliegues fallidos (sin residuos), servicios que estaban detenidos, vuelta atrás y restauración fallidas, interrupción por `SIGTERM` y por `SIGKILL`, recuperaciones pendientes, commits obsoletos y el orden del prune (`sh deploy/scripts/tests/deploy-service.test.sh`).
- **Marca durable y recuperación manual.** Antes de promover nada, el respaldo de la configuración y un `LEEME.txt` (estado previo y comandos exactos para restaurarlo) se escriben en `/opt/omniretail/.recovery/<fecha>-<sha>/` y se confirman en disco (`sync` + rename atómico). La marca se borra solo si el despliegue termina bien o la vuelta atrás se completa. Si la vuelta atrás falla o el proceso muere a mitad —incluso sin traps: `SIGKILL`, OOM, caída del VPS—, **la marca queda y ningún CD (backend ni web) despliega nada** hasta que alguien siga el `LEEME.txt` y la borre. Una carpeta `*.tmp` es un respaldo a medio escribir de un despliegue que murió antes de promover nada: no bloquea y se limpia sola.
- **Límite conocido: migraciones.** La vuelta atrás no revierte migraciones de Liquibase. Toda migración debe ser compatible con la versión anterior del backend (patrón *expand/contract*: agregar primero, quitar en un despliegue posterior).
- **SSH verificado.** El CD exige la huella del servidor (`VPS_SSH_FINGERPRINT`); sin ella el despliegue falla en vez de conectarse a ciegas.
- Redesplegar a mano: *Actions → CD → Run workflow* sobre `main`.


```
Internet ──► Caddy :80/:443 ──┬─ /api/v1/*, /media/* ──► backend:8080 ──► postgres:5432
                              └─ todo lo demás ────────► web:3000 ──(red interna)──► backend
```

## 1. Preparar el VPS (una sola vez)

Requisitos: Ubuntu 22.04/24.04, 4 GB de RAM (JVM + Next.js + PostgreSQL + monitoreo; ver [Monitoreo](#monitoreo-prometheus--grafana)) y un
dominio público cuyo registro DNS apunte a la IP del VPS. Caddy obtiene el certificado TLS y redirige HTTP a HTTPS;
no se permite producción solo con IP ni HTTP plano.

```bash
# Docker + Compose
curl -fsSL https://get.docker.com | sh

# Usuario sin privilegios para los despliegues
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /opt/omniretail && sudo chown deploy:deploy /opt/omniretail

# Firewall: solo SSH y web
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable
```

## 2. Clave SSH para GitHub Actions

En tu máquina (no en el VPS):

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f omniretail_deploy -N ""
```

- Contenido de `omniretail_deploy.pub` → agregarlo en el VPS a `/home/deploy/.ssh/authorized_keys`.
- Contenido de `omniretail_deploy` (privada) → secret `VPS_SSH_KEY` en los repos **backend** y **web**. Después borra el archivo local.

## 3. Acceso a las imágenes de GHCR

Los paquetes de GHCR nacen **privados** y pueden quedarse así:

- **Despliegues automáticos:** no requieren nada. Cada job `deploy` hace `docker login ghcr.io` en el VPS con el `GITHUB_TOKEN` del propio run (permiso `packages: read`, expira al terminar el job), descarga **solo su imagen** y hace `docker logout` al final. El VPS no guarda credenciales.
- **Operación manual** (rollback, primer arranque a mano): inicia sesión temporalmente con un PAT (classic) con solo `read:packages`:
  ```bash
  echo "<PAT>" | docker login ghcr.io -u <usuario> --password-stdin
  docker compose -f docker-compose.prod.yml pull
  docker logout ghcr.io
  ```

Orden del primer despliegue: **backend primero** (copia `docker-compose.prod.yml`, `caddy/`, `monitoring/` y `scripts/` al VPS y levanta postgres, backend y Caddy), luego web. Cada CD levanta solo sus servicios (`--no-deps`), así un repo nunca necesita credenciales de la imagen del otro.

## 4. Archivo de secretos

```bash
# como usuario deploy, en /opt/omniretail
nano .env    # copiar el contenido de env.prod.example y completarlo
chmod 600 .env
```

Define `SITE_ADDRESS`, `FRONTEND_BASE_URL` y `CORS_ALLOWED_ORIGINS` con el mismo dominio y esquema `https://`.
Antes del primer arranque, confirma que el DNS ya resuelve al VPS y que los puertos 80 y 443 están abiertos.

`docker-compose.prod.yml`, `caddy/`, `monitoring/` y `scripts/` los copia el CD del backend en cada despliegue. Para el primer arranque manual, cópialos tú con `scp`.

## 5. Activar el despliegue automático

En GitHub → *Settings → Secrets and variables → Actions* de **backend** y **web**:

| Nombre | Tipo | Valor |
|---|---|---|
| `VPS_HOST` | Variable | IP pública del VPS |
| `VPS_USER` | Variable | `deploy` (por defecto) |
| `VPS_PORT` | Variable | `22` (por defecto) |
| `VPS_APP_DIR` | Variable | `/opt/omniretail` (por defecto) |
| `VPS_SSH_FINGERPRINT` | Variable | huella SHA256 del servidor (ver abajo). **Obligatoria** |
| `VPS_SSH_KEY` | Secret | clave privada del paso 2 |

Mientras `VPS_HOST` esté vacío, el CD publica la imagen y **omite** el despliegue sin fallar.

Huella del servidor (en el VPS):

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub   # copia el valor SHA256:... (sin el "256" ni el "(ED25519)")
```

Compárala con la que te muestra tu propia terminal la primera vez que entras por SSH: si coinciden, nadie está en medio.

Recomendado: proteger `main` (*Settings → Branches*) exigiendo PR y los checks de CI, para que nada llegue a producción sin revisión.

Opcional: en *Settings → Environments → production* agrega *Required reviewers* para aprobar cada despliegue a mano.

## Operación

```bash
cd /opt/omniretail
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend

# Versión desplegada de cada servicio
grep -E '^(BACKEND|WEB)_TAG=' .env

# Rollback manual a una versión anterior (las etiquetas sha-xxxxxxx están en GHCR). Requiere login en GHCR (paso 3).
sh scripts/deploy-service.sh backend sha-abc1234

# Backup manual de la base de datos
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U omniretail omniretail | gzip > backup-$(date +%F).sql.gz
```

## Monitoreo (Prometheus + Grafana)

El CD copia `deploy/monitoring/` al VPS y levanta junto al backend:

| Servicio | Qué mide | Tope de RAM |
|----------|----------|-------------|
| `prometheus` | Guarda las métricas 15 días (máx. 2 GB en disco) | 512 MB |
| `grafana` | Dashboards y alertas por correo | 256 MB |
| `node-exporter` | CPU, RAM, disco y red del VPS | 64 MB |
| `postgres-exporter` | Conexiones, tamaño y actividad de PostgreSQL (usuario `pg_monitor`, sin acceso a datos) | 64 MB |

Consumo medido en una réplica local del stack: **~180 MB** al arrancar (Grafana ~115, Prometheus ~50, exporters ~20);
Prometheus crece con el historial hasta su tope. Los topes suman ~900 MB, así que el monitoreo nunca puede pasar de ahí.
Con 2 GB de RAM el VPS queda justo (JVM + Next.js + PostgreSQL); **4 GB es lo recomendado**.

El backend expone Actuator (`/actuator/health`, `/actuator/prometheus`) en el puerto interno **8081**
(`MANAGEMENT_SERVER_PORT` en la imagen), que Caddy no enruta; el `HEALTHCHECK` del contenedor usa ese mismo puerto.

Requiere en `.env`: `GRAFANA_ADMIN_PASSWORD`, `ALERT_EMAIL_TO` y `POSTGRES_EXPORTER_PASSWORD` (el SMTP reutiliza `MAIL_*`).
En cada despliegue el CD ejecuta `monitoring/postgres/ensure-monitoring-role.sh`, que crea o actualiza el usuario
`omniretail_monitor` con el rol `pg_monitor` (lee estadísticas, no datos) antes de levantar postgres-exporter.

**¿Por qué no hay métricas por contenedor (cAdvisor)?** Con Docker 29 (lo que instala `get.docker.com`), cAdvisor solo
puede identificar contenedores montando el socket de containerd, lo que equivale a control total del VPS; un proxy de solo
lectura del socket de Docker no alcanza. Se priorizó el mínimo privilegio: node-exporter cubre el VPS completo y backend y
PostgreSQL exponen sus propias métricas. Para ver el consumo puntual de cada contenedor: `docker stats --no-stream`.

**Entrar a Grafana** (no está publicado a internet; solo escucha en `127.0.0.1` del VPS):

```bash
ssh -L 3001:127.0.0.1:3001 deploy@<IP-del-VPS>
# luego abrir http://localhost:3001  (usuario admin / GRAFANA_ADMIN_PASSWORD)
```

- Dashboards: carpeta **OmniRetail** (JVM, Spring Boot, VPS, PostgreSQL). Se versionan en
  `deploy/monitoring/grafana/provisioning/dashboards/json`; los cambios hechos desde la UI no se guardan.
- Alertas: `deploy/monitoring/grafana/provisioning/alerting/rules.yml` (backend caído, memoria JVM, 5xx, latencia p95,
  pool Hikari, RAM/disco del VPS, conexiones de PostgreSQL).
- Probar el correo: *Alerting → Contact points → email-equipo → Test*.

En local: `docker compose --profile monitoring up -d` con el backend corriendo en el host; Grafana en
http://localhost:3001 (admin/admin) y las alertas llegan a Mailpit.

> **Pendiente recomendado:** automatizar backups diarios de PostgreSQL (cron + `pg_dump`) y sacarlos del VPS.
