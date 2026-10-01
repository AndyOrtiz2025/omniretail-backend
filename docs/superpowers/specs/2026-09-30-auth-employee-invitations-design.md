# Diseño: invitaciones y activación de empleados

## Contexto y objetivo

Hoy `administration` crea empleados, pero `auth` no aprovisiona su `auth_accounts`. Se incorporará un flujo de invitación de 48 horas, reinvitación, activación pública y consulta administrativa por lote, preservando el aislamiento por tenant y evitando dependencias directas de `administration` hacia implementaciones de `auth`.

## Alternativas y decisión

| Tema | Alternativas | Decisión y razón |
|---|---|---|
| Integración modular | Inyectar servicio `auth`; mover endpoints; puerto compartido | Puerto `EmployeeInvitationPort` en `shared.security`; conserva límites como `SessionRevoker`/`SessionValidator`. |
| Persistencia del token | `BaseEntity`; entidad puntual | `EmployeeInvitation` declara `id`/`createdAt` y no extiende `BaseEntity`, igual que `EmailVerification` y `PasswordResetChallenge`; no existe `updated_at`. |
| Resúmenes | GET con query; POST con body | Solo POST: evita URLs largas, permite máximo 100 IDs y validación estable. |

## Límites modulares

`UserController` depende únicamente de `EmployeeInvitationPort`, `EmployeeInviteResult` y `EmployeeAuthSummary`. `EmployeeInvitationService` vive en `auth`, implementa el puerto y puede usar repositorios de usuarios/cuentas. La activación y sus DTO HTTP permanecen en `auth`. Los records compartidos no exponen entidades ni repositorios; `EmployeeAuthSummary.status` será `String` con el nombre de `AccountStatus` (o `null`), evitando una dependencia `shared` → `auth`.

## Modelo y migración

`030-auth-employee-invitations.yaml` crea `employee_invitations(id UUID PK DEFAULT gen_random_uuid(), user_id UUID NOT NULL, tenant_id UUID NOT NULL, token_hash VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL, accepted_at TIMESTAMPTZ NULL, superseded_at TIMESTAMPTZ NULL)`, FKs con `ON DELETE CASCADE`, `uk_employee_invitations_token_hash` e índice `idx_employee_invitations_user(user_id)`. `db.changelog-master.yaml` usa `includeAll`; NO se modifica.

La entidad asigna `createdAt` explícitamente para consistencia de dominio. El repositorio ofrece búsqueda de token con `PESSIMISTIC_WRITE`, invitaciones vigentes por usuario y última invitación.

## Flujos, transacciones y concurrencia

**Invitar/reinvitar:** en una transacción, bloquear `User` por `(tenantId,id)`; si no existe o pertenece a otro tenant responder 404 sin filtrar datos. Exigir `UserType.employee` (el `UserStatus` queda fuera de alcance). Invalidar invitaciones vigentes con el mismo `now`. Crear la cuenta si falta con estado `password_reset_required`, contraseña aleatoria BCrypt y cero intentos. Si existe, sincronizar `AuthAccount.email` desde `User.email` (fuente administrativa de verdad); `active` produce `409 ACCOUNT_ALREADY_ACTIVE`, y cualquier estado distinto de `password_reset_required` produce `400 INVITATION_NOT_ALLOWED` mediante `new BusinessException(HttpStatus.BAD_REQUEST, ...)`. Generar `AuthTokens.generate()`, persistir solo SHA-256 y devolver el token claro. Orden de bloqueos: usuario → invitaciones → cuenta.

**Activar:** bloquear invitación por hash y después cuenta por usuario. Token ausente, aceptado, reemplazado, vencido o cuenta no pendiente produce el mismo `400 INVALID_OR_EXPIRED_TOKEN`. Verificar usuario empleado y tenant coincidente; validar `PasswordPolicy.EMPLOYEE`, convirtiendo el primer error en `FieldValidationException.of("newPassword", message)`. Actualizar hash, estado `active`, `passwordChangedAt`, limpiar intentos/bloqueo, marcar `acceptedAt` y revocar sesiones mediante `SessionRevoker`. Todo confirma o revierte junto.

**Resúmenes:** si la lista está vacía, retornar inmediatamente `200 OK` con `[]`, sin consultar la base de datos. Para listas no vacías, deduplicar IDs preservando orden y rechazar únicamente cantidades mayores de 100. Ejecutar exactamente dos consultas (`users` por tenant/tipo/IDs y `auth_accounts` por `userId IN`), sin N+1. Si cualquier ID falta/no pertenece/no es empleado, responder `400 INVALID_EMPLOYEE_SELECTION` sin identificar el ID. Recomponer en orden; sin cuenta: `status=null`, `lastLoginAt=null`, `mfaEnabled=false`.

## API y errores

- `POST /api/v1/administration/users/{id}/invite` y `/resend-invite`, permiso `admin.users.manage`: `200` con `{userId, invitationToken, expiresAt}`. Ambos reutilizan la misma operación; cada llamada reemplaza el token vigente por uno nuevo.
- `POST /api/v1/administration/users/auth-summaries`, permiso `admin.users.read`: body `{userIds:[...]}`; `200` lista `{userId,status,mfaEnabled,lastLoginAt}`.
- `POST /api/v1/auth/activate-employee`, público en `SecurityConfig.PUBLIC_PATHS`: body `@NotBlank {token,newPassword}`; `200 {message:"Cuenta activada correctamente."}`.
- Errores estructurales usan `VALIDATION_ERROR`; contraseña débil incluye `fields.newPassword`. Los errores de negocio anteriores conservan código/mensaje español.

## Seguridad y correo

El token son 32 bytes CSPRNG en Base64URL; jamás se persiste ni registra en claro. Su retorno HTTP es una excepción administrativa explícita al Javadoc de `AuthTokens`, que se actualizará. El correo se publica con `EmailRequestedEvent` y enlace `FrontendProperties.link("/activar-cuenta/" + token)`, asunto **“Activa tu cuenta de empleado”**; el listener existente lo entrega a Mailpit solo tras commit. No se agregan secretos a logs.

## Archivos previstos

Crear migración, entidad/repositorio/servicio/controlador y DTOs de activación; crear puerto y records en `shared.security`; modificar `UserController`, `UserRepository`, `AuthAccountRepository`, `SecurityConfig` y comentario de `AuthTokens`; agregar pruebas de servicio y controladores.

## Estrategia de pruebas

Se escribirán, pero NO se ejecutarán builds ni tests. Unitarias: alta de cuenta/evento, reinvitación y supersesión, sincronización de email, activación, token vencido/reutilizado, política de contraseña, lista batch vacía sin consultas y aislamiento/batch sin N+1. Controller tests: permisos, contratos invite/resend/batch, lista vacía, límite 100 y activación pública. Integración de repositorio: locks y constraints del changeset.

## Riesgos y no alcance

Riesgos: exposición accidental del token, carreras y listas abusivas; se mitigan con hash-only, bloqueos/constraints y límite 100. No se valida `UserStatus`, no se implementa MFA (`false`), rate limiting, aceptación múltiple, cambios a login ni edición del changelog maestro. No quedan decisiones abiertas.
