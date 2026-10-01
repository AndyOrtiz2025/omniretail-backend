# Employee Invitations and Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aprovisionar cuentas de empleados mediante invitaciones reemplazables de 48 horas, activación pública segura y consulta administrativa de estado por lote.

**Architecture:** `administration` consumirá exclusivamente un puerto pequeño en `shared.security`; `EmployeeInvitationService`, dentro de `auth`, implementará ese puerto y será dueño de persistencia, tokens, correo y activación. La consistencia concurrente se protegerá con transacciones y bloqueos pesimistas en el orden usuario → invitaciones → cuenta, mientras los resúmenes usarán exactamente dos consultas y recompondrán el orden solicitado en memoria.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring Security, Spring Data JPA/Hibernate, Jakarta Validation, PostgreSQL, Liquibase, JUnit 5, Mockito, MockMvc y Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-30-auth-employee-invitations-design.md`

## Global Constraints

- No ejecutar Maven, Gradle, builds ni tests en esta sesión; todos los comandos de prueba del plan quedan como instrucciones para una sesión posterior.
- Escribir las pruebas antes que el código productivo en cada tarea.
- `administration` no depende de clases de `auth`; solo consume `EmployeeInvitationPort`, `EmployeeInviteResult` y `EmployeeAuthSummary` desde `com.omniretail.backend.shared.security`.
- `EmployeeInvitation` NO extiende `BaseEntity`, declara `id` y `createdAt` directamente y no tiene `updated_at`.
- El changeset nuevo se llama `030-auth-employee-invitations.yaml`; `db.changelog-master.yaml` permanece intacto porque ya usa `includeAll`.
- `030` es deliberadamente el siguiente feature solicitado aunque `origin/development` todavía no contenga el fix `029` de otra rama/PR; no crear, renumerar ni asumir un archivo `029` en este cambio.
- Persistir únicamente SHA-256 del token; el token claro solo puede aparecer en la respuesta administrativa y en el enlace del correo, nunca en BD ni logs.
- La invitación dura exactamente 48 horas; cada invite/reinvite invalida invitaciones vigentes anteriores con el mismo `Instant now`.
- No validar `UserStatus`, no implementar MFA (el resumen siempre usa `false`), rate limiting, cambios al login ni otros endpoints.
- Commits Conventional Commits, sin `Co-Authored-By`, menciones de IA ni atribuciones automáticas.

## Review Focus

1. Lista de resúmenes vacía: debe devolver `200 []` sin invocar ningún repositorio; lo fija `EmployeeInvitationServiceTest.emptySummariesReturnImmediatelyWithoutQueries` y `UserControllerTest.emptyAuthSummariesReturnOkWithEmptyArray`.
2. Selección con IDs duplicados o fuera del tenant: debe preservar el primer orden tras deduplicar y rechazar toda selección inválida sin revelar qué ID falló; lo fija la tarea de resúmenes.
3. Dos reinvitaciones concurrentes o dos activaciones del mismo token: solo un token queda vigente y solo una activación puede consumirse; lo fijan las pruebas de repositorio/servicio con locks.
4. Cuenta existente en estados `active`, `disabled`, `archived` o `temporarily_locked`: `active` devuelve `409 ACCOUNT_ALREADY_ACTIVE`; cualquier estado no pendiente restante devuelve `400 INVITATION_NOT_ALLOWED`; lo fija la tarea de invitación.
5. Token ausente, aceptado, reemplazado, vencido, de tenant inconsistente o ligado a una cuenta no pendiente: todos deben devolver el mismo `400 INVALID_OR_EXPIRED_TOKEN`; lo fija la tarea de activación.

---

### Task 1: Persistencia y contratos de repositorio para invitaciones

**Files:**
- Create: `src/main/resources/db/changelog/changes/030-auth-employee-invitations.yaml`
- Create: `src/main/java/com/omniretail/backend/auth/entity/EmployeeInvitation.java`
- Create: `src/main/java/com/omniretail/backend/auth/repository/EmployeeInvitationRepository.java`
- Modify: `src/main/java/com/omniretail/backend/administration/repository/UserRepository.java`
- Modify: `src/main/java/com/omniretail/backend/auth/repository/AuthAccountRepository.java`
- Create: `src/test/java/com/omniretail/backend/auth/repository/EmployeeInvitationRepositoryTest.java`

**Interfaces:**
- Consumes: entidad `User`, entidad `AuthAccount`, PostgreSQL y la carga automática de `db/changelog/changes/*.yaml` por `includeAll`.
- Produces: `EmployeeInvitation`; `EmployeeInvitationRepository.findByTokenHashForUpdate(String)`, `findByUserIdAndAcceptedAtIsNullAndSupersededAtIsNull(UUID)` y `findTopByUserIdOrderByCreatedAtDesc(UUID)`; `UserRepository.findByTenantIdAndIdForUpdate(UUID, UUID)` y `findAllByTenantIdAndTypeAndIdIn(UUID, UserType, Collection<UUID>)`; `AuthAccountRepository.findAllByUserIdIn(Collection<UUID>)`.

- [ ] **Step 1: Escribir pruebas de repositorio que fallen**

  Crear `EmployeeInvitationRepositoryTest` con Testcontainers y casos que demuestren: persistencia completa sin `updated_at`; unicidad de `token_hash`; cascada al borrar el usuario; consulta de invitaciones vigentes; orden de la última invitación; y adquisición de `PESSIMISTIC_WRITE` para token, usuario e invitaciones activas dentro de transacciones. Añadir una comprobación del esquema que falle si existe `employee_invitations.updated_at` o falta cualquiera de `created_at`, `expires_at`, `accepted_at`, `superseded_at`.

- [ ] **Step 2: Documentar la ejecución RED sin correrla**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationRepositoryTest test`. Resultado RED esperado: tabla, entidad y repositorio aún inexistentes.

- [ ] **Step 3: Crear el changeset `030`**

  Definir `employee_invitations` con `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `user_id UUID NOT NULL`, `tenant_id UUID NOT NULL`, `token_hash VARCHAR(64) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `expires_at TIMESTAMPTZ NOT NULL`, `accepted_at TIMESTAMPTZ NULL`, `superseded_at TIMESTAMPTZ NULL`; FKs a `users(id)` y `tenants(id)` con `ON DELETE CASCADE`; constraint `uk_employee_invitations_token_hash`; índice `idx_employee_invitations_user(user_id)`. No agregar `updated_at` y no tocar `db.changelog-master.yaml`.

- [ ] **Step 4: Crear la entidad puntual**

  Implementar `EmployeeInvitation` con `@Id @GeneratedValue(strategy = GenerationType.UUID) UUID id` y propiedades `UUID userId`, `UUID tenantId`, `String tokenHash`, `Instant createdAt`, `Instant expiresAt`, `Instant acceptedAt`, `Instant supersededAt`; mapear nombres y nulabilidad exactos, `tokenHash` único/no actualizable y no heredar de `BaseEntity`.

- [ ] **Step 5: Crear repositorio y consultas con locks**

  En `EmployeeInvitationRepository`, aplicar `@Lock(PESSIMISTIC_WRITE)` a `findByTokenHashForUpdate` y a la consulta de invitaciones vigentes por usuario. En `UserRepository`, añadir una query bloqueante por `(tenantId,id)` y la búsqueda batch por tenant/tipo/IDs. En `AuthAccountRepository`, añadir la búsqueda batch por IDs de usuario.

- [ ] **Step 6: Documentar la ejecución GREEN sin correrla**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationRepositoryTest test`. Resultado esperado: PASS, incluidos schema, constraints y locks.

- [ ] **Step 7: Commit propuesto (no ejecutar en esta sesión)**

  ```bash
  git add src/main/resources/db/changelog/changes/030-auth-employee-invitations.yaml src/main/java/com/omniretail/backend/auth/entity/EmployeeInvitation.java src/main/java/com/omniretail/backend/auth/repository/EmployeeInvitationRepository.java src/main/java/com/omniretail/backend/administration/repository/UserRepository.java src/main/java/com/omniretail/backend/auth/repository/AuthAccountRepository.java src/test/java/com/omniretail/backend/auth/repository/EmployeeInvitationRepositoryTest.java
  git commit -m "feat(auth): agregar persistencia de invitaciones de empleados"
  ```

### Task 2: Puerto modular y flujo transaccional de invitación/reinvitación

**Files:**
- Create: `src/main/java/com/omniretail/backend/shared/security/EmployeeInvitationPort.java`
- Create: `src/main/java/com/omniretail/backend/shared/security/EmployeeInviteResult.java`
- Create: `src/main/java/com/omniretail/backend/shared/security/EmployeeAuthSummary.java`
- Create: `src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java`
- Modify: `src/main/java/com/omniretail/backend/auth/service/AuthTokens.java`
- Create: `src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java`

**Interfaces:**
- Consumes: repositorios de la tarea 1, `PasswordEncoder`, `ApplicationEventPublisher`, `FrontendProperties`, `EmailRequestedEvent`, `EmailMessage` y `AuthTokens`.
- Produces: `EmployeeInvitationPort.inviteEmployee(UUID tenantId, UUID userId)` y `getAuthSummaries(UUID tenantId, Collection<UUID> userIds)`; record `EmployeeInviteResult(UUID userId, String invitationToken, Instant expiresAt)`; record `EmployeeAuthSummary(UUID userId, String status, boolean mfaEnabled, Instant lastLoginAt)`; `EmployeeInvitationService` como implementación Spring del puerto y servicio de activación que se completará en tareas posteriores.

- [ ] **Step 1: Escribir pruebas unitarias RED de invite/reinvite**

  En `EmployeeInvitationServiceTest`, cubrir: usuario empleado válido sin cuenta crea `AuthAccount` con email administrativo, estado `password_reset_required`, password BCrypt aleatoria, cero intentos y una invitación a 48 horas; solo se persiste hash SHA-256; se devuelve token claro; se publica `EmailRequestedEvent` a `FrontendProperties.link("/activar-cuenta/" + token)` con asunto `Activa tu cuenta de empleado`. Cubrir además reinvitación que marca `supersededAt=now` y crea otro token, sincronización del email de una cuenta pendiente, ausencia/cross-tenant/cliente como `404 USER_NOT_FOUND`, cuenta activa como `409 ACCOUNT_ALREADY_ACTIVE`, y demás estados no pendientes como `400 INVITATION_NOT_ALLOWED`.

- [ ] **Step 2: Añadir pruebas de orden de locks y confidencialidad**

  Verificar con Mockito el orden usuario → invitaciones → cuenta; capturar entidades/evento para confirmar que el token claro no aparece en `tokenHash`, password hash ni campos persistidos. Añadir el caso de dos reinvitaciones secuenciales donde solo la última queda vigente.

- [ ] **Step 3: Documentar RED sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest test`. Resultado RED esperado: puerto, records y servicio no definidos.

- [ ] **Step 4: Crear puerto y records compartidos**

  Crear exactamente las dos firmas aprobadas en `EmployeeInvitationPort`; mantener `status` como `String` para impedir dependencia `shared → auth`; no exponer entidades ni repositorios.

- [ ] **Step 5: Implementar `inviteEmployee` de forma mínima**

  En `@Transactional EmployeeInviteResult inviteEmployee(UUID tenantId, UUID userId)`, fijar un solo `Instant now`, bloquear y validar usuario empleado, superseder invitaciones vigentes, bloquear/crear cuenta, aplicar las transiciones y códigos del diseño, generar `AuthTokens.generate()`, guardar solo `AuthTokens.hash(token)`, expirar en `now.plus(Duration.ofHours(48))`, publicar el correo y devolver el token claro. Mensajes públicos: `USER_NOT_FOUND` / `Usuario no encontrado.`, `ACCOUNT_ALREADY_ACTIVE` / `Este empleado ya tiene una cuenta activa.`, `INVITATION_NOT_ALLOWED` / `No se puede invitar a este empleado en su estado actual.`.

- [ ] **Step 6: Actualizar el contrato documental de `AuthTokens`**

  Ajustar únicamente su Javadoc para declarar la excepción controlada: los tokens de invitación pueden devolverse en la respuesta administrativa además de viajar por correo; nunca se persisten ni registran en claro.

- [ ] **Step 7: Documentar GREEN sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest test`. Resultado esperado: PASS para invite/reinvite, errores, correo, hash-only y orden de locks.

- [ ] **Step 8: Commit propuesto (no ejecutar en esta sesión)**

  ```bash
  git add src/main/java/com/omniretail/backend/shared/security/EmployeeInvitationPort.java src/main/java/com/omniretail/backend/shared/security/EmployeeInviteResult.java src/main/java/com/omniretail/backend/shared/security/EmployeeAuthSummary.java src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java src/main/java/com/omniretail/backend/auth/service/AuthTokens.java src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java
  git commit -m "feat(auth): implementar invitacion y reinvitacion de empleados"
  ```

### Task 3: Endpoints administrativos de invitación y reinvitación

**Files:**
- Modify: `src/main/java/com/omniretail/backend/administration/controller/UserController.java`
- Modify: `src/test/java/com/omniretail/backend/administration/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: `EmployeeInvitationPort.inviteEmployee(UUID, UUID)`, `EmployeeInviteResult` y `CurrentUser.require().tenantId()`.
- Produces: `POST /administration/users/{id}/invite` y `POST /administration/users/{id}/resend-invite`, ambos protegidos por `@RequirePermission("admin.users.manage")` y ambos con cuerpo `EmployeeInviteResult`/HTTP 200.

- [ ] **Step 1: Escribir pruebas MockMvc RED de ambos endpoints**

  Añadir casos para invite y resend exitosos que esperan `200`, `userId`, `invitationToken` no vacío y `expiresAt`; confirmar que resend reemplaza el token y el anterior queda `supersededAt` no nulo. Añadir sin JWT → 401, sin `admin.users.manage` → 403, usuario de otro tenant → 404 `USER_NOT_FOUND`, cuenta activa → 409 `ACCOUNT_ALREADY_ACTIVE` y cuenta no elegible → 400 `INVITATION_NOT_ALLOWED`.

- [ ] **Step 2: Documentar RED sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=UserControllerTest test`. Resultado RED esperado: rutas inexistentes.

- [ ] **Step 3: Inyectar el puerto en `UserController` y publicar las rutas**

  Añadir `EmployeeInvitationPort` como dependencia, obtener siempre `tenantId` desde `CurrentUser.require()` y delegar ambos métodos a la misma operación `inviteEmployee`; no introducir imports ni referencias a `auth`.

- [ ] **Step 4: Documentar GREEN sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=UserControllerTest test`. Resultado esperado: PASS de permisos, aislamiento y contratos invite/resend.

- [ ] **Step 5: Commit propuesto (no ejecutar en esta sesión)**

  ```bash
  git add src/main/java/com/omniretail/backend/administration/controller/UserController.java src/test/java/com/omniretail/backend/administration/controller/UserControllerTest.java
  git commit -m "feat(administration): exponer invitaciones de empleados"
  ```

### Task 4: Activación pública de cuenta de empleado

**Files:**
- Create: `src/main/java/com/omniretail/backend/auth/dto/ActivateEmployeeRequest.java`
- Create: `src/main/java/com/omniretail/backend/auth/dto/ActivateEmployeeResponse.java`
- Create: `src/main/java/com/omniretail/backend/auth/controller/EmployeeActivationController.java`
- Modify: `src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java`
- Modify: `src/main/java/com/omniretail/backend/shared/security/SecurityConfig.java`
- Modify: `src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java`
- Create: `src/test/java/com/omniretail/backend/auth/controller/EmployeeActivationControllerTest.java`

**Interfaces:**
- Consumes: `EmployeeInvitationRepository.findByTokenHashForUpdate`, `AuthAccountRepository.findByUserIdForUpdate`, `PasswordPolicy.EMPLOYEE`, `PasswordEncoder`, `SessionRevoker` y `AuthTokens.hash`.
- Produces: `EmployeeInvitationService.activateEmployeeAccount(String token, String newPassword)` y endpoint público `POST /auth/activate-employee`; request `ActivateEmployeeRequest(@NotBlank String token, @NotBlank String newPassword)`; response `ActivateEmployeeResponse(String message)`.

- [ ] **Step 1: Escribir pruebas unitarias RED del servicio de activación**

  Cubrir activación válida: bloquea invitación y luego cuenta, verifica usuario empleado/tenant, aplica `PasswordPolicy.EMPLOYEE`, guarda BCrypt, pone `active`, fija `passwordChangedAt`, limpia intentos/`lockedUntil`, fija `acceptedAt` y llama `SessionRevoker.revokeAllSessions(userId)`. Cubrir contraseña débil como `VALIDATION_ERROR` mediante `FieldValidationException` con `fields.newPassword` y rollback conceptual (sin mutaciones finales verificables).

- [ ] **Step 2: Añadir la matriz uniforme de token inválido**

  Casos separados para hash ausente, `acceptedAt`, `supersededAt`, `expiresAt <= now`, cuenta ausente/no pendiente, usuario ausente/no empleado y tenant distinto; todos esperan `BusinessException` 400, code `INVALID_OR_EXPIRED_TOKEN` y el mismo mensaje `Este enlace no es válido o ya expiró.`. Añadir consumo repetido y dos activaciones simuladas para probar un único éxito lógico.

- [ ] **Step 3: Escribir pruebas MockMvc RED del endpoint público**

  En `EmployeeActivationControllerTest`, probar activación sin JWT → 200 con `{message:"Cuenta activada correctamente."}`; token o contraseña en blanco → 400 `VALIDATION_ERROR` con el campo correspondiente; contraseña débil → 400 `VALIDATION_ERROR` con `fields.newPassword`; token inválido/reutilizado/vencido → 400 `INVALID_OR_EXPIRED_TOKEN` sin distinguir causa.

- [ ] **Step 4: Documentar RED sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest,EmployeeActivationControllerTest test`. Resultado RED esperado: método, DTOs, ruta y permiso público inexistentes.

- [ ] **Step 5: Implementar `activateEmployee` transaccional**

  Añadir `@Transactional ActivateEmployeeResponse activateEmployeeAccount(String token, String newPassword)` siguiendo el orden invitación → cuenta, validación uniforme y cambios atómicos del diseño. Traducir solo el primer error de `PasswordPolicy.EMPLOYEE.validate(newPassword, user.getEmail())` a `FieldValidationException.of("newPassword", message)`.

- [ ] **Step 6: Crear DTOs, controller y apertura de seguridad**

  Crear `EmployeeActivationController` con `@RequestMapping("/auth")`, `@PostMapping("/activate-employee")`, `@Valid` y respuesta exacta. Añadir únicamente `/api/v1/auth/activate-employee` a `SecurityConfig.PUBLIC_PATHS`.

- [ ] **Step 7: Documentar GREEN sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest,EmployeeActivationControllerTest test`. Resultado esperado: PASS de activación, política, uniformidad de error, revocación y acceso público.

- [ ] **Step 8: Commit propuesto (no ejecutar en esta sesión)**

  ```bash
  git add src/main/java/com/omniretail/backend/auth/dto/ActivateEmployeeRequest.java src/main/java/com/omniretail/backend/auth/dto/ActivateEmployeeResponse.java src/main/java/com/omniretail/backend/auth/controller/EmployeeActivationController.java src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java src/main/java/com/omniretail/backend/shared/security/SecurityConfig.java src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java src/test/java/com/omniretail/backend/auth/controller/EmployeeActivationControllerTest.java
  git commit -m "feat(auth): activar cuentas de empleados por invitacion"
  ```

### Task 5: Resúmenes de autenticación por lote con exactamente dos consultas

**Files:**
- Create: `src/main/java/com/omniretail/backend/administration/dto/EmployeeAuthSummariesRequest.java`
- Modify: `src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java`
- Modify: `src/main/java/com/omniretail/backend/administration/controller/UserController.java`
- Modify: `src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java`
- Modify: `src/test/java/com/omniretail/backend/administration/controller/UserControllerTest.java`

**Interfaces:**
- Consumes: `EmployeeInvitationPort.getAuthSummaries(UUID, Collection<UUID>)`, `UserRepository.findAllByTenantIdAndTypeAndIdIn` y `AuthAccountRepository.findAllByUserIdIn`.
- Produces: request `EmployeeAuthSummariesRequest(@NotNull @Size(max=100) List<@NotNull UUID> userIds)` y `POST /administration/users/auth-summaries` protegido por `admin.users.read`, con `List<EmployeeAuthSummary>`.

- [ ] **Step 1: Escribir pruebas unitarias RED del batch**

  Añadir `emptySummariesReturnImmediatelyWithoutQueries`: entrada vacía devuelve `List.of()` y `verifyNoInteractions(userRepository, authAccountRepository)`. Añadir entrada con duplicados que deduplica preservando primera aparición, ejecuta exactamente una consulta a usuarios y una a cuentas y recompone en ese orden. Probar cuenta existente (`status=AccountStatus.name()`, `lastLoginAt`, `mfaEnabled=false`) y usuario sin cuenta (`status=null`, `lastLoginAt=null`, `mfaEnabled=false`).

- [ ] **Step 2: Probar selección inválida sin filtración**

  Cubrir ID inexistente, customer e ID de otro tenant; si la cantidad de empleados retornados no coincide con IDs únicos solicitados, lanzar 400 `INVALID_EMPLOYEE_SELECTION` con mensaje único `La selección de empleados no es válida.`. Confirmar que no se consulta `auth_accounts` después de detectar una selección inválida.

- [ ] **Step 3: Escribir pruebas MockMvc RED del endpoint**

  Añadir: body `{"userIds":[]}` → `200 []`; comprobar mediante `@MockBean`/spy del puerto que no se hacen consultas en el servicio unitario (la garantía de cero queries vive en el test anterior); 100 IDs estructuralmente válidos no produce `VALIDATION_ERROR`; 101 IDs → 400 `VALIDATION_ERROR` con `fields.userIds`; `null`, campo ausente o elemento null → 400 `VALIDATION_ERROR`; sin permiso → 403; selección cross-tenant/customer/inexistente → 400 `INVALID_EMPLOYEE_SELECTION`; resultado preserva el orden deduplicado.

- [ ] **Step 4: Documentar RED sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest,UserControllerTest test`. Resultado RED esperado: DTO, método y ruta batch inexistentes.

- [ ] **Step 5: Implementar batch en el servicio**

  En `getAuthSummaries`, retornar antes de consultar si `userIds.isEmpty()`; deduplicar con `LinkedHashSet`; como defensa interna, rechazar si el tamaño recibido es mayor de 100; consultar usuarios una vez, validar cobertura total, consultar cuentas una vez y construir un mapa por `userId`; mapear a los records en el orden deduplicado. No hacer queries dentro de loops.

- [ ] **Step 6: Publicar DTO y endpoint administrativo**

  Validar solo nulidad y `max=100`: una lista vacía ES válida. En `UserController`, usar tenant de `CurrentUser.require()`, permiso `admin.users.read` y devolver directamente la lista del puerto.

- [ ] **Step 7: Documentar GREEN sin correrlo**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw -Dtest=EmployeeInvitationServiceTest,UserControllerTest test`. Resultado esperado: PASS; vacío devuelve `[]` sin queries, 101 devuelve 400 y batch no incurre en N+1.

- [ ] **Step 8: Commit propuesto (no ejecutar en esta sesión)**

  ```bash
  git add src/main/java/com/omniretail/backend/administration/dto/EmployeeAuthSummariesRequest.java src/main/java/com/omniretail/backend/auth/service/EmployeeInvitationService.java src/main/java/com/omniretail/backend/administration/controller/UserController.java src/test/java/com/omniretail/backend/auth/service/EmployeeInvitationServiceTest.java src/test/java/com/omniretail/backend/administration/controller/UserControllerTest.java
  git commit -m "feat(administration): consultar autenticacion de empleados por lote"
  ```

### Task 6: Verificación final diferida y revisión de alcance

**Files:**
- Review: todos los archivos de las tareas 1–5
- Review: `docs/superpowers/specs/2026-09-30-auth-employee-invitations-design.md`
- Confirm unchanged: `src/main/resources/db/changelog/db.changelog-master.yaml`

**Interfaces:**
- Consumes: implementación completa y contratos HTTP/persistencia del plan.
- Produces: evidencia futura de suite verde y diff limitado al feature.

- [ ] **Step 1: Revisar estáticamente el diff sin build**

  Confirmar: ningún import `administration → auth`; ningún token claro persistido/logueado; `EmployeeInvitation` no extiende `BaseEntity`; no existe `updated_at`; master intacto; no hay cambio `029`; ambas rutas invite delegan igual; activación es pública; batch vacío corta antes de repositorios y batch no vacío hace dos consultas.

- [ ] **Step 2: Documentar pruebas focalizadas futuras, sin ejecutarlas**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro:

  ```bash
  ./mvnw -Dtest=EmployeeInvitationRepositoryTest,EmployeeInvitationServiceTest,EmployeeActivationControllerTest,UserControllerTest test
  ```

  Resultado esperado: todos PASS.

- [ ] **Step 3: Documentar suite completa futura, sin ejecutarla**

  **NO EJECUTAR en esta sesión por restricción explícita.** Comando futuro: `./mvnw test`. Resultado esperado: BUILD SUCCESS y cero tests fallidos.

- [ ] **Step 4: Ejecutar auto-revisión del plan y del resultado**

  Trazar cada sección del spec a una tarea; validar firmas/nombres entre `shared`, `auth` y `administration`; verificar los cinco puntos de Review Focus; rechazar cambios fuera de alcance o duplicación de reglas.

- [ ] **Step 5: Commit propuesto solo si la verificación futura exige ajustes (no ejecutar ahora)**

  ```bash
  git add <solo-los-archivos-ajustados>
  git commit -m "fix(auth): ajustar flujo de invitacion de empleados"
  ```

## Auto-revisión realizada al redactar el plan

- **Cobertura del spec:** migración 030, entidad sin `BaseEntity`, locks, invite/reinvite, hash-only, correo after-commit mediante evento, activación pública, política de contraseña, revocación, puerto compartido, summaries, permisos, aislamiento y errores tienen tarea y prueba asignadas. No hay huecos detectados.
- **Consistencia de tipos:** el puerto usa exactamente `UUID`, `Collection<UUID>`, `List<EmployeeAuthSummary>`; `status` permanece `String`; los DTO HTTP no cruzan el límite modular salvo los records compartidos aprobados.
- **Proporción:** el plan decide firmas, invariantes y casos de prueba sin transcribir cuerpos de implementación; la lógica detallada queda en los ejecutores.
- **Review Focus:** cada uno de los cinco riesgos está ligado a una prueba concreta: vacío sin queries, selección/deduplicación, concurrencia, estados de cuenta y uniformidad del token inválido.
- **Restricción de ejecución:** todos los comandos de tests/build están marcados `NO EJECUTAR en esta sesión por restricción explícita`; este plan no autoriza su ejecución durante la sesión actual.
