# Despliegue en VPS

Stack de producción: **Caddy** (reverse proxy, HTTPS automático con dominio) → **web** (Next.js) y **backend** (Spring Boot) → **PostgreSQL 16**.
Las imágenes se construyen en GitHub Actions (`cd.yml` de cada repo) y se publican en GHCR. El VPS solo hace `docker compose pull && up`.

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

Orden del primer despliegue: **backend primero** (copia `docker-compose.prod.yml` y `Caddyfile` al VPS y levanta postgres, backend y Caddy), luego web. Cada CD levanta solo sus servicios (`--no-deps`), así un repo nunca necesita credenciales de la imagen del otro.

## 4. Archivo de secretos

```bash
# como usuario deploy, en /opt/omniretail
nano .env    # copiar el contenido de env.prod.example y completarlo
chmod 600 .env
```

Define `SITE_ADDRESS`, `FRONTEND_BASE_URL` y `CORS_ALLOWED_ORIGINS` con el mismo dominio y esquema `https://`.
Antes del primer arranque, confirma que el DNS ya resuelve al VPS y que los puertos 80 y 443 están abiertos.

`docker-compose.prod.yml` y `Caddyfile` los copia el CD del backend en cada despliegue. Para el primer arranque manual, cópialos tú con `scp`.

## 5. Activar el despliegue automático

En GitHub → *Settings → Secrets and variables → Actions* de **backend** y **web**:

| Nombre | Tipo | Valor |
|---|---|---|
| `VPS_HOST` | Variable | IP pública del VPS |
| `VPS_USER` | Variable | `deploy` (por defecto) |
| `VPS_PORT` | Variable | `22` (por defecto) |
| `VPS_APP_DIR` | Variable | `/opt/omniretail` (por defecto) |
| `VPS_SSH_KEY` | Secret | clave privada del paso 2 |

Mientras `VPS_HOST` esté vacío, el CD publica la imagen y **omite** el despliegue sin fallar.

Opcional: en *Settings → Environments → production* agrega *Required reviewers* para aprobar cada despliegue a mano.

## Operación

```bash
cd /opt/omniretail
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend

# Rollback: fijar una versión anterior (las etiquetas sha-xxxxxxx están en GHCR)
echo "BACKEND_TAG=sha-abc1234" >> .env
docker compose -f docker-compose.prod.yml up -d backend

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
| `cadvisor` | Consumo por contenedor | 128 MB |
| `postgres-exporter` | Conexiones, tamaño y actividad de PostgreSQL | 64 MB |

Consumo real ~500–700 MB: con el stack de monitoreo, **4 GB de RAM pasa a ser el mínimo práctico**.
El backend expone `/actuator/prometheus` en el puerto interno **8081** (perfil `prod`), que Caddy no enruta.

Requiere en `.env`: `GRAFANA_ADMIN_PASSWORD` y `ALERT_EMAIL_TO` (el SMTP reutiliza `MAIL_*`).

**Entrar a Grafana** (no está publicado a internet; solo escucha en `127.0.0.1` del VPS):

```bash
ssh -L 3001:127.0.0.1:3001 deploy@<IP-del-VPS>
# luego abrir http://localhost:3001  (usuario admin / GRAFANA_ADMIN_PASSWORD)
```

- Dashboards: carpeta **OmniRetail** (JVM, Spring Boot, VPS, contenedores, PostgreSQL). Se versionan en
  `deploy/monitoring/grafana/provisioning/dashboards/json`; los cambios hechos desde la UI no se guardan.
- Alertas: `deploy/monitoring/grafana/provisioning/alerting/rules.yml` (backend caído, memoria JVM, 5xx, latencia p95,
  pool Hikari, RAM/disco del VPS, conexiones de PostgreSQL).
- Probar el correo: *Alerting → Contact points → email-equipo → Test*.

En local: `docker compose --profile monitoring up -d` con el backend corriendo en el host; Grafana en
http://localhost:3001 (admin/admin) y las alertas llegan a Mailpit.

> **Pendiente recomendado:** automatizar backups diarios de PostgreSQL (cron + `pg_dump`) y sacarlos del VPS.
