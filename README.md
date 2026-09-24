# OmniRetail Backend

Backend de OmniRetail (MARJYM): monolito modular en Spring Boot 4.1.1, Java 21 y PostgreSQL 16.
Reemplaza a los `Mock*Repository` del frontend
([omniretail-web](https://github.com/Alexander3150/omniretail-web)), cuyos contratos en
`src/core/` y documentos en `docs/` son la especificación funcional.

## Requisitos

- JDK 21 o superior
- Docker Desktop (con Docker Compose)
- No hace falta instalar Maven: se usa `./mvnw` (en Windows, `mvnw.cmd`).

## Primer arranque

```bash
cp .env.example .env          # ajustar solo si algún puerto está ocupado
docker compose up -d          # PostgreSQL 16 + Mailpit
./mvnw spring-boot:run        # perfil dev por defecto
```

| Qué | URL (puertos por defecto) |
| :--- | :--- |
| Health check | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/api-docs |
| Mailpit (correos de dev) | http://localhost:8025 |

Si un puerto está ocupado en tu máquina, cámbialo **solo en tu `.env`** (`POSTGRES_PORT`,
`SERVER_PORT`, ...). El `.env` no se sube a git y los defaults del equipo no cambian.

## Tests

```bash
./mvnw verify
```

Los tests de integración levantan un PostgreSQL real con Testcontainers: **Docker debe estar corriendo**.
Para tus tests de integración, usa `@SpringBootTest`, `@ActiveProfiles("test")` e
`@Import(TestcontainersConfiguration.class)` (ver `BackendApplicationTests`).

## Estructura

```
src/main/java/com/omniretail/backend/
├── shared/           Andy     config, security (JWT), exception, persistence, dto
├── auth/             Andy     login, logout, sesiones, /me
├── administration/   José     tenants, sucursales, usuarios, roles
├── catalog/          Melbyn   productos, categorías, unidades
├── inventory/        Melbyn   balances, movimientos, ajustes, reservas
├── pos/              Riquelme turnos de caja, ventas, pagos
├── ecommerce/        María    API pública del storefront, checkout, tracking
└── logistics/        Riquelme despacho y entrega
```

Cada módulo tiene las capas `controller/`, `service/`, `repository/`, `entity/` y `dto/`.
Un módulo solo usa otro a través de sus **services**, nunca de sus repositories ni entities.

## Convenciones

**API**
- Los controllers declaran solo el recurso: `@RequestMapping("/catalog/products")`. El prefijo
  `/api/v1` se agrega automáticamente (`ApiPathConfig`).
- `/api/v1/auth/**` y `/api/v1/public/**` son públicas; el resto exige `Authorization: Bearer <jwt>`.
- `tenantId`, usuario y permisos **siempre** salen del JWT, nunca del body, query o URL.
- DTOs como `record` con Bean Validation (`@NotBlank`, `@Size`, `@Valid`, ...).
- Errores de negocio: `throw new BusinessException(status, "CODIGO", "mensaje")`. Todos los errores
  salen con el mismo formato: `{ status, error, code, message, path, fields?, timestamp }`.
- Listados paginados: `PageResponse.from(page, mapper)`, con la misma forma que el `PaginatedResult` del frontend.

**Base de datos**
- Tablas `snake_case` en plural; PK `UUID`; timestamps `TIMESTAMPTZ`.
- Dinero `NUMERIC(12,2)` ↔ `BigDecimal` (nunca `double`); cantidades `NUMERIC(12,3)`.
- Enums como `VARCHAR` + `CHECK`, con **los mismos valores que `src/core/enums` del frontend**.
- Borrado lógico con `status = 'archived'`. `inventory_movements`, `cash_movements` y `audit_logs`
  son append-only.
- Entidades mutables extienden `BaseEntity` (o `TenantScopedEntity` si tienen `tenant_id`).
- `ddl-auto: validate`: Hibernate no crea tablas; **cada tabla nace en un changelog de Liquibase**.

**Liquibase**
- Cada módulo agrega su archivo en `src/main/resources/db/changelog/changes/`, numerado:
  `001-tenants-branches.yaml`, `002-users-roles-auth.yaml`, ... No se edita el master.
- Un changeset que ya está en `development` nunca se modifica: se corrige con uno nuevo.
- La semilla demo va en `999-seed-data.yaml` con `context: dev` (nunca se carga en `prod`).
- Quien crea una tabla crea también su entidad JPA, para que no se desfasen.

## Git

Mismo flujo que el frontend: `main` ← `development` ← `feature/<modulo>-<tarea>`.
Los PR van siempre a `development`, con revisión antes del merge.

## Perfiles

| Perfil | Uso | Notas |
| :--- | :--- | :--- |
| `dev` (default) | Desarrollo local | JWT secret de desarrollo, semilla demo, SQL en log |
| `test` | Tests | Base de datos de Testcontainers |
| `prod` | Producción | `JWT_SECRET` obligatorio, Swagger desactivado, sin semilla |
