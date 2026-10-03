package com.omniretail.backend.ecommerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerPaymentMethod;
import com.omniretail.backend.ecommerce.repository.CustomerPaymentMethodRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerPaymentMethodControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PAYMENT_METHODS = "/api/v1/me/payment-methods";
    private static final String PASSWORD = "Cliente123!";
    private static final String EMPLOYEE_PASSWORD = "Empleado1234!";
    private static final List<String> CUSTOMER_PERMISSIONS =
            List.of("customer.account.read", "customer.account.update", "customer.payment_method.manage");
    private static final YearMonth NOW = YearMonth.now(ZoneId.of("America/Guatemala"));
    private static final int NEXT_YEAR = NOW.getYear() + 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private CustomerPaymentMethodRepository paymentMethodRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Crear y listar ---

    @Test
    void firstCardIsDefaultAndSecondIsNot() throws Exception {
        CustomerAccount account = customer(tenant());

        createCard(account.token(), cardJson("4242"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").value(account.customer().getTenantId().toString()))
                .andExpect(jsonPath("$.customerId").value(account.customer().getId().toString()))
                .andExpect(jsonPath("$.type").value("card"))
                .andExpect(jsonPath("$.brand").value("Visa"))
                .andExpect(jsonPath("$.issuingBank").value("Banco Industrial"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.expirationMonth").value(8))
                .andExpect(jsonPath("$.expirationYear").value(NEXT_YEAR))
                .andExpect(jsonPath("$.cardholderName").value("Ana López"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.default").doesNotExist())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        createCard(account.token(), cardJson("9876", "\"brand\": \"Mastercard\"",
                        "\"issuingBank\": \"Banco G&T Continental\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));

        listCards(account.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].last4").value("4242"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].last4").value("9876"))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void listShowsDefaultFirstThenOldest() throws Exception {
        CustomerAccount account = customer(tenant());
        createCard(account.token(), cardJson("1111"));
        createCard(account.token(), cardJson("2222"));
        String third = idOf(createCard(account.token(), cardJson("3333")));

        setDefault(account.token(), third).andExpect(status().isOk());

        listCards(account.token())
                .andExpect(jsonPath("$[0].last4").value("3333"))
                .andExpect(jsonPath("$[1].last4").value("1111"))
                .andExpect(jsonPath("$[2].last4").value("2222"));
    }

    @Test
    void blankCardholderIsStoredAsNull() throws Exception {
        CustomerAccount account = customer(tenant());

        String id = idOf(createCard(account.token(), cardJson("4242", "\"cardholderName\": \"   \"", "\"last4\": \" 4242 \""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.cardholderName").doesNotExist()));

        assertThat(paymentMethodRepository.findById(UUID.fromString(id)).orElseThrow().getCardholderName()).isNull();
    }

    // --- Seguridad del body ---

    @Test
    void cardNumberCvvTokenAndDefaultFlagAreRejected() throws Exception {
        CustomerAccount account = customer(tenant());

        createCard(account.token(), cardJson("4242",
                        "\"cardNumber\": \"4242424242424242\"",
                        "\"cvv\": \"123\"",
                        "\"providerPaymentMethodId\": \"pm_demo_robado\"",
                        "\"isDefault\": true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.cardNumber").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.cvv").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.providerPaymentMethodId").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.isDefault").value("Campo no permitido."));
        createCard(account.token(), cardJson("4242", "\"tenantId\": \"%s\"".formatted(UUID.randomUUID()),
                        "\"customerId\": \"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.tenantId").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.customerId").value("Campo no permitido."));

        assertThat(cards(account)).isEmpty();
    }

    @Test
    void responseNeverIncludesProviderToken() throws Exception {
        CustomerAccount account = customer(tenant());

        String id = idOf(createCard(account.token(), cardJson("4242"))
                .andExpect(jsonPath("$.providerPaymentMethodId").doesNotExist()));
        String second = idOf(createCard(account.token(), cardJson("9876")));
        listCards(account.token())
                .andExpect(jsonPath("$[0].providerPaymentMethodId").doesNotExist())
                .andExpect(jsonPath("$[1].providerPaymentMethodId").doesNotExist());
        updateCard(account.token(), id, updateJson(12, NEXT_YEAR, "Ana"))
                .andExpect(jsonPath("$.providerPaymentMethodId").doesNotExist());
        setDefault(account.token(), second).andExpect(jsonPath("$.providerPaymentMethodId").doesNotExist());

        // El token existe, pero solo dentro del servidor.
        assertThat(paymentMethodRepository.findById(UUID.fromString(id)).orElseThrow().getProviderPaymentMethodId())
                .startsWith("pm_demo_");
    }

    // --- Validacion ---

    @Test
    void invalidCardReturnsFrontendMessages() throws Exception {
        CustomerAccount account = customer(tenant());

        createCard(account.token(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.brand").value("La marca de la tarjeta es obligatoria."))
                .andExpect(jsonPath("$.fields.issuingBank").value("El banco emisor es obligatorio."))
                .andExpect(jsonPath("$.fields.last4").value("Ingresa exactamente los últimos 4 dígitos."))
                .andExpect(jsonPath("$.fields.expirationMonth").value("El mes debe estar entre 1 y 12."))
                .andExpect(jsonPath("$.fields.expirationYear").value("El año debe ser el actual o uno posterior."));

        createCard(account.token(), cardJson("12a4", "\"brand\": \"casa\"", "\"issuingBank\": \"Banco Inventado\"",
                        "\"expirationMonth\": 13", "\"cardholderName\": \"%s\"".formatted("a".repeat(61))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.brand").value("Selecciona una marca de tarjeta válida."))
                .andExpect(jsonPath("$.fields.issuingBank").value("Selecciona un banco emisor válido."))
                .andExpect(jsonPath("$.fields.last4").value("Ingresa exactamente los últimos 4 dígitos."))
                .andExpect(jsonPath("$.fields.expirationMonth").value("El mes debe estar entre 1 y 12."))
                .andExpect(jsonPath("$.fields.cardholderName").value(
                        "El nombre en la tarjeta no puede superar 60 caracteres."));

        // Las listas son exactas: misma marca o banco con otra escritura no pasa.
        createCard(account.token(), cardJson("4242", "\"brand\": \"VISA\"", "\"issuingBank\": \"banco industrial\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.brand").value("Selecciona una marca de tarjeta válida."))
                .andExpect(jsonPath("$.fields.issuingBank").value("Selecciona un banco emisor válido."));
        createCard(account.token(), cardJson("42424"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.last4").value("Ingresa exactamente los últimos 4 dígitos."));

        assertThat(cards(account)).isEmpty();
    }

    @Test
    void expiredOrTooFarCardIsRejected() throws Exception {
        CustomerAccount account = customer(tenant());
        YearMonth lastMonth = NOW.minusMonths(1);
        int maxYear = NOW.getYear() + 20;

        createCard(account.token(), cardJson("4242", "\"expirationMonth\": " + lastMonth.getMonthValue(),
                        "\"expirationYear\": " + lastMonth.getYear()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.expirationYear").value("La tarjeta está vencida."));
        createCard(account.token(), cardJson("4242", "\"expirationYear\": " + (NOW.getYear() - 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.expirationYear").value("La tarjeta está vencida."));
        createCard(account.token(), cardJson("4242", "\"expirationYear\": " + (maxYear + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.expirationYear").value(
                        "El año de expiración no puede ser mayor a " + maxYear + "."));
        assertThat(cards(account)).isEmpty();

        // Los limites son validos: el mes actual y el año actual + 20.
        createCard(account.token(), cardJson("4242", "\"expirationMonth\": " + NOW.getMonthValue(),
                        "\"expirationYear\": " + NOW.getYear()))
                .andExpect(status().isCreated());
        createCard(account.token(), cardJson("9876", "\"expirationYear\": " + maxYear))
                .andExpect(status().isCreated());
    }

    // --- Editar ---

    @Test
    void updateChangesHolderAndExpirationOnly() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createCard(account.token(), cardJson("4242")));
        String second = idOf(createCard(account.token(), cardJson("9876")));

        updateCard(account.token(), first, updateJson(12, NEXT_YEAR + 2, "  Ana María  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationMonth").value(12))
                .andExpect(jsonPath("$.expirationYear").value(NEXT_YEAR + 2))
                .andExpect(jsonPath("$.cardholderName").value("Ana María"))
                .andExpect(jsonPath("$.brand").value("Visa"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.isDefault").value(true));
        updateCard(account.token(), second, "{\"expirationMonth\": 1, \"expirationYear\": %d}".formatted(NEXT_YEAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardholderName").doesNotExist())
                .andExpect(jsonPath("$.isDefault").value(false));

        CustomerPaymentMethod stored = paymentMethodRepository.findById(UUID.fromString(second)).orElseThrow();
        assertThat(stored.getCardholderName()).isNull();
        assertThat(stored.getExpirationMonth()).isEqualTo(1);
    }

    @Test
    void updateRejectsImmutableFieldsAndExpiredDates() throws Exception {
        CustomerAccount account = customer(tenant());
        String id = idOf(createCard(account.token(), cardJson("4242")));

        updateCard(account.token(), id, """
                        {"expirationMonth": 8, "expirationYear": %d, "brand": "Mastercard",
                         "issuingBank": "BAC Credomatic", "last4": "0000", "status": "archived", "isDefault": false}"""
                        .formatted(NEXT_YEAR))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.brand").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.issuingBank").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.last4").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.status").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.isDefault").value("Campo no permitido."));
        updateCard(account.token(), id, updateJson(1, NOW.getYear() - 1, "Ana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.expirationYear").value("La tarjeta está vencida."));

        CustomerPaymentMethod unchanged = paymentMethodRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(unchanged.getBrand()).isEqualTo("Visa");
        assertThat(unchanged.getIssuingBank()).isEqualTo("Banco Industrial");
        assertThat(unchanged.getLast4()).isEqualTo("4242");
        assertThat(unchanged.getExpirationYear()).isEqualTo(NEXT_YEAR);
    }

    // --- Principal ---

    @Test
    void setDefaultLeavesExactlyOne() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createCard(account.token(), cardJson("1111")));
        String second = idOf(createCard(account.token(), cardJson("2222")));
        String third = idOf(createCard(account.token(), cardJson("3333")));

        setDefault(account.token(), third)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(third))
                .andExpect(jsonPath("$.isDefault").value(true));
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(third));

        // Repetir sobre la que ya es principal no cambia nada.
        setDefault(account.token(), third).andExpect(status().isOk());
        setDefault(account.token(), second).andExpect(status().isOk());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(second));
        assertThat(paymentMethodRepository.findById(UUID.fromString(first)).orElseThrow().getIsDefault()).isFalse();
    }

    @Test
    void deletingDefaultPromotesOldestRemaining() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createCard(account.token(), cardJson("1111")));
        String second = idOf(createCard(account.token(), cardJson("2222")));
        String third = idOf(createCard(account.token(), cardJson("3333")));
        setDefault(account.token(), third).andExpect(status().isOk());
        // Forzar el orden por antiguedad: la segunda pasa a ser la mas antigua de las que quedan.
        jdbcTemplate.update(
                "UPDATE customer_payment_methods SET created_at = now() - interval '1 day' WHERE id = ?::uuid", second);

        deleteCard(account.token(), first).andExpect(status().isNoContent());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(third));

        deleteCard(account.token(), third).andExpect(status().isNoContent());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(second));

        deleteCard(account.token(), second).andExpect(status().isNoContent());
        assertThat(cards(account)).isEmpty();

        // Despues de quedarse sin tarjetas, la siguiente vuelve a nacer principal.
        createCard(account.token(), cardJson("4444")).andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void concurrentSetDefaultLeavesSingleDefault() throws Exception {
        for (int round = 0; round < 5; round++) {
            CustomerAccount account = customer(tenant());
            idOf(createCard(account.token(), cardJson("1111")));
            String second = idOf(createCard(account.token(), cardJson("2222")));
            String third = idOf(createCard(account.token(), cardJson("3333")));

            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> results = List.of(second, third).stream()
                        .map(id -> executor.submit(() -> {
                            start.await();
                            return setDefault(account.token(), id).andReturn().getResponse().getStatus();
                        }))
                        .toList();
                start.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                }
            }

            assertThat(defaultIds(account)).hasSize(1).first()
                    .isIn(UUID.fromString(second), UUID.fromString(third));
        }
    }

    // --- Aislamiento y acceso ---

    @Test
    void cardOfAnotherCustomerIsNotFound() throws Exception {
        Tenant tenant = tenant();
        CustomerAccount owner = customer(tenant);
        CustomerAccount other = customer(tenant);
        CustomerAccount otherTenant = customer(tenant());
        String id = idOf(createCard(owner.token(), cardJson("4242")));

        for (CustomerAccount intruder : List.of(other, otherTenant)) {
            // No hay GET por id: el listado del intruso simplemente no la incluye.
            listCards(intruder.token()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
            updateCard(intruder.token(), id, updateJson(1, NEXT_YEAR, "Robada"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_FOUND"));
            setDefault(intruder.token(), id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_FOUND"));
            deleteCard(intruder.token(), id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_FOUND"));
        }
        deleteCard(owner.token(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_FOUND"));

        CustomerPaymentMethod unchanged = paymentMethodRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(unchanged.getCardholderName()).isEqualTo("Ana López");
        assertThat(unchanged.getIsDefault()).isTrue();
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        String id = UUID.randomUUID().toString();

        mockMvc.perform(get(PAYMENT_METHODS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PAYMENT_METHODS).contentType(MediaType.APPLICATION_JSON).content(cardJson("4242")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(PAYMENT_METHODS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(1, NEXT_YEAR, "Ana")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(PAYMENT_METHODS + "/" + id + "/default")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PAYMENT_METHODS + "/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeGetsForbiddenEvenWithCustomerPermissions() throws Exception {
        String token = employeeToken(tenant());

        listCards(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_REQUIRED"));
        createCard(token, cardJson("4242"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_REQUIRED"));
    }

    @Test
    void customerWithoutPermissionGetsAccessDenied() throws Exception {
        Tenant tenant = tenant();
        CustomerAccount account = customer(tenant, role(tenant, "customer.address.manage"));

        listCards(account.token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // --- Utilidades ---

    private record CustomerAccount(User user, Customer customer, String token) {
    }

    private ResultActions listCards(String token) throws Exception {
        return mockMvc.perform(get(PAYMENT_METHODS).header("Authorization", bearer(token)));
    }

    private ResultActions createCard(String token, String body) throws Exception {
        return mockMvc.perform(post(PAYMENT_METHODS)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions updateCard(String token, String id, String body) throws Exception {
        return mockMvc.perform(put(PAYMENT_METHODS + "/" + id)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteCard(String token, String id) throws Exception {
        return mockMvc.perform(delete(PAYMENT_METHODS + "/" + id).header("Authorization", bearer(token)));
    }

    private ResultActions setDefault(String token, String id) throws Exception {
        return mockMvc.perform(put(PAYMENT_METHODS + "/" + id + "/default").header("Authorization", bearer(token)));
    }

    /**
     * Tarjeta valida, como la envia paymentMethodService.createPaymentMethod. Cada {@code override} es un
     * campo completo, p. ej. {@code "\"brand\": \"Mastercard\""}, que reemplaza o agrega ese campo.
     */
    private static String cardJson(String last4, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("brand", "\"Visa\"");
        fields.put("issuingBank", "\"Banco Industrial\"");
        fields.put("last4", "\"" + last4 + "\"");
        fields.put("expirationMonth", "8");
        fields.put("expirationYear", String.valueOf(NEXT_YEAR));
        fields.put("cardholderName", "\"Ana López\"");
        for (String override : overrides) {
            String[] parts = override.split(":", 2);
            fields.put(parts[0].trim().replace("\"", ""), parts[1].trim());
        }
        StringJoiner json = new StringJoiner(", ", "{", "}");
        fields.forEach((key, value) -> json.add("\"" + key + "\": " + value));
        return json.toString();
    }

    private static String updateJson(int month, int year, String cardholderName) {
        return "{\"expirationMonth\": %d, \"expirationYear\": %d, \"cardholderName\": \"%s\"}"
                .formatted(month, year, cardholderName);
    }

    private List<CustomerPaymentMethod> cards(CustomerAccount account) {
        return paymentMethodRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                account.customer().getTenantId(), account.customer().getId());
    }

    private List<UUID> defaultIds(CustomerAccount account) {
        return cards(account).stream()
                .filter(method -> Boolean.TRUE.equals(method.getIsDefault()))
                .map(CustomerPaymentMethod::getId)
                .toList();
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Tenant tenant() {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private Role role(Tenant tenant, String... permissions) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(RoleStatus.active)
                .permissions(List.of(permissions))
                .build();
        role.setTenantId(tenant.getId());
        return roleRepository.save(role);
    }

    private CustomerAccount customer(Tenant tenant) throws Exception {
        return customer(tenant, role(tenant, CUSTOMER_PERMISSIONS.toArray(String[]::new)));
    }

    private CustomerAccount customer(Tenant tenant, Role role) throws Exception {
        String email = "cliente-" + UUID.randomUUID() + "@test.local";
        User user = User.builder()
                .name("Cliente test")
                .email(email)
                .phone("55551234")
                .type(UserType.customer)
                .roleId(role.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        Customer customer = Customer.builder()
                .userId(user.getId())
                .code("CLI-" + UUID.randomUUID())
                .name("Cliente test")
                .email(email)
                .phone("55551234")
                .build();
        customer.setTenantId(tenant.getId());
        customer = customerRepository.save(customer);
        user.setCustomerId(customer.getId());
        user = userRepository.save(user);
        activeAccount(user, PASSWORD);
        return new CustomerAccount(user, customer, login(email, PASSWORD, tenant.getSlug()));
    }

    private String employeeToken(Tenant tenant) throws Exception {
        String email = "empleado-" + UUID.randomUUID() + "@test.local";
        User user = User.builder()
                .name("Empleado test")
                .email(email)
                .type(UserType.employee)
                .roleId(role(tenant, CUSTOMER_PERMISSIONS.toArray(String[]::new)).getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        activeAccount(user, EMPLOYEE_PASSWORD);
        return login(email, EMPLOYEE_PASSWORD, null);
    }

    private void activeAccount(User user, String password) {
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .passwordHash(passwordEncoder.encode(password))
                .status(AccountStatus.active)
                .build());
    }

    private String login(String email, String password, String tenantSlug) throws Exception {
        String body = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\", \"tenantSlug\": %s}".formatted(
                                email, password, tenantSlug == null ? "null" : "\"" + tenantSlug + "\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
