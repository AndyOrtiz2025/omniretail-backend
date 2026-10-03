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
import com.omniretail.backend.ecommerce.entity.Address;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.AddressRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
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
class CustomerAccountControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String PROFILE = "/api/v1/me/profile";
    private static final String ADDRESSES = "/api/v1/me/addresses";
    private static final String PASSWORD = "Cliente123!";
    private static final String EMPLOYEE_PASSWORD = "Empleado1234!";
    private static final List<String> CUSTOMER_PERMISSIONS =
            List.of("customer.account.read", "customer.account.update", "customer.address.manage");

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
    private AddressRepository addressRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Perfil ---

    @Test
    void customerReadsOwnProfile() throws Exception {
        Tenant tenant = tenant();
        CustomerAccount account = customer(tenant);

        mockMvc.perform(get(PROFILE).header("Authorization", bearer(account.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.customer().getId().toString()))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.userId").value(account.user().getId().toString()))
                .andExpect(jsonPath("$.code").value(account.customer().getCode()))
                .andExpect(jsonPath("$.name").value("Cliente test"))
                .andExpect(jsonPath("$.email").value(account.user().getEmail()))
                .andExpect(jsonPath("$.phone").value("55551234"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void customerUpdatesProfileAndAuthMeReflectsIt() throws Exception {
        CustomerAccount account = customer(tenant());

        updateProfile(account.token(), "{\"name\": \"  Ana López  \", \"phone\": \"44443333\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ana López"))
                .andExpect(jsonPath("$.phone").value("44443333"))
                .andExpect(jsonPath("$.email").value(account.user().getEmail()));

        Customer customer = customerRepository.findById(account.customer().getId()).orElseThrow();
        assertThat(customer.getName()).isEqualTo("Ana López");
        assertThat(customer.getPhone()).isEqualTo("44443333");
        User user = userRepository.findById(account.user().getId()).orElseThrow();
        assertThat(user.getName()).isEqualTo("Ana López");
        assertThat(user.getPhone()).isEqualTo("44443333");

        mockMvc.perform(get(ME).header("Authorization", bearer(account.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Ana López"));
    }

    @Test
    void omittedOrBlankPhoneRemovesIt() throws Exception {
        CustomerAccount account = customer(tenant());

        updateProfile(account.token(), "{\"name\": \"Ana\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());
        updateProfile(account.token(), "{\"name\": \"Ana\", \"phone\": \"   \"}").andExpect(status().isOk());

        assertThat(customerRepository.findById(account.customer().getId()).orElseThrow().getPhone()).isNull();
        assertThat(userRepository.findById(account.user().getId()).orElseThrow().getPhone()).isNull();
    }

    @Test
    void invalidProfileReturnsFrontendMessages() throws Exception {
        CustomerAccount account = customer(tenant());

        updateProfile(account.token(), "{\"name\": \"   \", \"phone\": \"5555-1234\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.name").value("El nombre es obligatorio."))
                .andExpect(jsonPath("$.fields.phone").value("El teléfono solo puede contener números."));
        updateProfile(account.token(), "{\"name\": \"%s\", \"phone\": \"5555123\"}".formatted("a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").value("El nombre no puede superar 100 caracteres."))
                .andExpect(jsonPath("$.fields.phone").value("El teléfono debe tener exactamente 8 dígitos."));

        Customer unchanged = customerRepository.findById(account.customer().getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Cliente test");
        assertThat(unchanged.getPhone()).isEqualTo("55551234");
    }

    @Test
    void extraProfileFieldsAreRejected() throws Exception {
        CustomerAccount account = customer(tenant());

        updateProfile(account.token(), "{\"name\": \"Ana\", \"email\": \"otro@test.local\", \"tenantId\": \"%s\"}"
                        .formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.email").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.tenantId").value("Campo no permitido."));

        Customer unchanged = customerRepository.findById(account.customer().getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Cliente test");
        assertThat(unchanged.getEmail()).isEqualTo(account.user().getEmail());
    }

    @Test
    void employeeGetsForbiddenEvenWithCustomerPermissions() throws Exception {
        String token = employeeToken(tenant());

        mockMvc.perform(get(PROFILE).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_REQUIRED"));
        updateProfile(token, "{\"name\": \"Ana\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_REQUIRED"));
        mockMvc.perform(get(ADDRESSES).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_ACCOUNT_REQUIRED"));
    }

    @Test
    void customerWithoutPermissionGetsAccessDenied() throws Exception {
        Tenant tenant = tenant();
        CustomerAccount account = customer(tenant, role(tenant, "storefront.orders.read"));

        mockMvc.perform(get(PROFILE).header("Authorization", bearer(account.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get(ADDRESSES).header("Authorization", bearer(account.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Ana\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADDRESSES)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ADDRESSES).contentType(MediaType.APPLICATION_JSON).content(addressJson("Casa")))
                .andExpect(status().isUnauthorized());
    }

    // --- Direcciones ---

    @Test
    void firstAddressIsDefaultAndNextOnesAreNot() throws Exception {
        CustomerAccount account = customer(tenant());

        createAddress(account.token(), addressJson("Casa"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").value(account.customer().getTenantId().toString()))
                .andExpect(jsonPath("$.customerId").value(account.customer().getId().toString()))
                .andExpect(jsonPath("$.label").value("Casa"))
                .andExpect(jsonPath("$.recipientName").value("Ana López"))
                .andExpect(jsonPath("$.line1").value("5a avenida 10-20 zona 1"))
                .andExpect(jsonPath("$.city").value("Mixco"))
                .andExpect(jsonPath("$.stateOrDepartment").value("Guatemala"))
                .andExpect(jsonPath("$.postalCode").value("01057"))
                .andExpect(jsonPath("$.country").value("Guatemala"))
                .andExpect(jsonPath("$.references").value("Frente al parque, casa verde"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.default").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        createAddress(account.token(), addressJson("Trabajo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));

        listAddresses(account.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].label").value("Casa"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].label").value("Trabajo"))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void optionalFieldsAreTrimmedAndBlankBecomesNull() throws Exception {
        CustomerAccount account = customer(tenant());

        createAddress(account.token(), """
                        {"label": "  Casa ", "recipientName": " Ana ", "line1": " Calle 1 ", "line2": "  ",
                         "city": " Antigua Guatemala ", "stateOrDepartment": " Sacatepéquez ", "postalCode": "",
                         "references": " "}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Casa"))
                .andExpect(jsonPath("$.recipientName").value("Ana"))
                .andExpect(jsonPath("$.line1").value("Calle 1"))
                .andExpect(jsonPath("$.line2").doesNotExist())
                .andExpect(jsonPath("$.city").value("Antigua Guatemala"))
                .andExpect(jsonPath("$.stateOrDepartment").value("Sacatepéquez"))
                .andExpect(jsonPath("$.postalCode").doesNotExist())
                .andExpect(jsonPath("$.references").doesNotExist())
                .andExpect(jsonPath("$.country").value("Guatemala"));
    }

    @Test
    void countryIsAcceptedButAlwaysGuatemala() throws Exception {
        CustomerAccount account = customer(tenant());

        String id = idOf(createAddress(account.token(), addressJson("Casa", "\"country\": \"México\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.country").value("Guatemala")));
        updateAddress(account.token(), id, addressJson("Casa 2", "\"country\": \"Honduras\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Casa 2"))
                .andExpect(jsonPath("$.country").value("Guatemala"));

        assertThat(addressRepository.findById(UUID.fromString(id)).orElseThrow().getCountry()).isEqualTo("Guatemala");
    }

    @Test
    void extraAddressFieldsAreRejected() throws Exception {
        CustomerAccount account = customer(tenant());

        createAddress(account.token(), addressJson("Casa", "\"isDefault\": false",
                        "\"tenantId\": \"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.isDefault").value("Campo no permitido."))
                .andExpect(jsonPath("$.fields.tenantId").value("Campo no permitido."));
        assertThat(addresses(account)).isEmpty();

        String id = idOf(createAddress(account.token(), addressJson("Casa")));
        updateAddress(account.token(), id, addressJson("Otra", "\"customerId\": \"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.customerId").value("Campo no permitido."));
        assertThat(addressRepository.findById(UUID.fromString(id)).orElseThrow().getLabel()).isEqualTo("Casa");
    }

    @Test
    void invalidAddressReturnsFrontendMessages() throws Exception {
        CustomerAccount account = customer(tenant());

        createAddress(account.token(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.label").value("El nombre de la dirección es obligatorio."))
                .andExpect(jsonPath("$.fields.recipientName").value("El destinatario es obligatorio."))
                .andExpect(jsonPath("$.fields.line1").value("La dirección es obligatoria."))
                .andExpect(jsonPath("$.fields.stateOrDepartment").value("El departamento es obligatorio."))
                .andExpect(jsonPath("$.fields.city").value("El municipio es obligatorio."));

        createAddress(account.token(), """
                        {"label": "%s", "recipientName": "Ana2", "line1": "-Calle", "line2": "Apto <3>",
                         "city": "Mixco", "stateOrDepartment": "Guatemala", "postalCode": "1234",
                         "references": "%s"}""".formatted("a".repeat(36), "a".repeat(301)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.label").value("El nombre de la dirección no puede superar 35 caracteres."))
                .andExpect(jsonPath("$.fields.recipientName").value(
                        "El destinatario permite letras, espacios, apóstrofes y guiones; máximo 60 caracteres."))
                .andExpect(jsonPath("$.fields.line1").value(
                        "La dirección permite letras, números, puntos y guiones; máximo 200 caracteres."))
                .andExpect(jsonPath("$.fields.line2").value(
                        "El complemento permite solo letras, números y espacios; máximo 200 caracteres."))
                .andExpect(jsonPath("$.fields.references").value(
                        "Las referencias permiten letras, números, espacios y comas; máximo 300 caracteres."))
                .andExpect(jsonPath("$.fields.postalCode").value("El código postal debe tener 5 dígitos."));

        assertThat(addresses(account)).isEmpty();
    }

    @Test
    void departmentAndMunicipalityMustBeValid() throws Exception {
        CustomerAccount account = customer(tenant());

        createAddress(account.token(), addressJson("Casa", "\"stateOrDepartment\": \"Narnia\"", "\"city\": \"Mixco\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.stateOrDepartment").value("Selecciona un departamento válido."))
                // Sin departamento valido el municipio no se puede verificar.
                .andExpect(jsonPath("$.fields.city").doesNotExist());
        createAddress(account.token(), addressJson("Casa", "\"stateOrDepartment\": \"Guatemala\"",
                        "\"city\": \"Antigua Guatemala\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.city").value("Selecciona un municipio que pertenezca al departamento elegido."))
                .andExpect(jsonPath("$.fields.stateOrDepartment").doesNotExist());

        assertThat(addresses(account)).isEmpty();
    }

    @Test
    void updateKeepsDefaultFlag() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createAddress(account.token(), addressJson("Casa")));
        String second = idOf(createAddress(account.token(), addressJson("Trabajo")));

        updateAddress(account.token(), first, addressJson("Casa nueva"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Casa nueva"))
                .andExpect(jsonPath("$.isDefault").value(true));
        updateAddress(account.token(), second, addressJson("Oficina"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test
    void setDefaultLeavesExactlyOne() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createAddress(account.token(), addressJson("Casa")));
        String second = idOf(createAddress(account.token(), addressJson("Trabajo")));
        String third = idOf(createAddress(account.token(), addressJson("Playa")));

        setDefault(account.token(), third)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(third))
                .andExpect(jsonPath("$.isDefault").value(true));
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(third));

        // Repetir sobre la que ya es predeterminada no cambia nada.
        setDefault(account.token(), third).andExpect(status().isOk());
        setDefault(account.token(), second).andExpect(status().isOk());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(second));
        assertThat(addressRepository.findById(UUID.fromString(first)).orElseThrow().getIsDefault()).isFalse();
    }

    @Test
    void deletingDefaultPromotesOldestRemaining() throws Exception {
        CustomerAccount account = customer(tenant());
        String first = idOf(createAddress(account.token(), addressJson("Casa")));
        String second = idOf(createAddress(account.token(), addressJson("Trabajo")));
        String third = idOf(createAddress(account.token(), addressJson("Playa")));
        setDefault(account.token(), third).andExpect(status().isOk());
        // Forzar el orden por antiguedad: la segunda pasa a ser la mas antigua de las que quedan.
        jdbcTemplate.update("UPDATE addresses SET created_at = now() - interval '1 day' WHERE id = ?::uuid", second);

        deleteAddress(account.token(), first).andExpect(status().isNoContent());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(third));

        deleteAddress(account.token(), third).andExpect(status().isNoContent());
        assertThat(defaultIds(account)).containsExactly(UUID.fromString(second));

        deleteAddress(account.token(), second).andExpect(status().isNoContent());
        assertThat(addresses(account)).isEmpty();

        // Despues de quedarse sin direcciones, la siguiente vuelve a nacer predeterminada.
        createAddress(account.token(), addressJson("Nueva")).andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void addressOfAnotherCustomerIsNotFound() throws Exception {
        Tenant tenant = tenant();
        CustomerAccount owner = customer(tenant);
        CustomerAccount other = customer(tenant);
        CustomerAccount otherTenant = customer(tenant());
        String id = idOf(createAddress(owner.token(), addressJson("Casa")));

        for (CustomerAccount intruder : List.of(other, otherTenant)) {
            updateAddress(intruder.token(), id, addressJson("Robada"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
            setDefault(intruder.token(), id).andExpect(status().isNotFound());
            deleteAddress(intruder.token(), id).andExpect(status().isNotFound());
            listAddresses(intruder.token()).andExpect(jsonPath("$", hasSize(0)));
        }
        deleteAddress(owner.token(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));

        Address unchanged = addressRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(unchanged.getLabel()).isEqualTo("Casa");
        assertThat(unchanged.getIsDefault()).isTrue();
    }

    @Test
    void concurrentSetDefaultLeavesSingleDefault() throws Exception {
        for (int round = 0; round < 5; round++) {
            CustomerAccount account = customer(tenant());
            idOf(createAddress(account.token(), addressJson("Casa")));
            String second = idOf(createAddress(account.token(), addressJson("Trabajo")));
            String third = idOf(createAddress(account.token(), addressJson("Playa")));

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

    // --- Utilidades ---

    private record CustomerAccount(User user, Customer customer, String token) {
    }

    private ResultActions updateProfile(String token, String body) throws Exception {
        return mockMvc.perform(put(PROFILE)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions listAddresses(String token) throws Exception {
        return mockMvc.perform(get(ADDRESSES).header("Authorization", bearer(token)));
    }

    private ResultActions createAddress(String token, String body) throws Exception {
        return mockMvc.perform(post(ADDRESSES)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions updateAddress(String token, String id, String body) throws Exception {
        return mockMvc.perform(put(ADDRESSES + "/" + id)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteAddress(String token, String id) throws Exception {
        return mockMvc.perform(delete(ADDRESSES + "/" + id).header("Authorization", bearer(token)));
    }

    private ResultActions setDefault(String token, String id) throws Exception {
        return mockMvc.perform(put(ADDRESSES + "/" + id + "/default").header("Authorization", bearer(token)));
    }

    /**
     * Direccion valida, como la envia addressService.toFields (incluye country). Cada {@code override}
     * es un campo completo, p. ej. {@code "\"city\": \"Mixco\""}, que reemplaza o agrega ese campo.
     */
    private static String addressJson(String label, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("label", "\"" + label + "\"");
        fields.put("recipientName", "\"Ana López\"");
        fields.put("line1", "\"5a avenida 10-20 zona 1\"");
        fields.put("line2", "\"Apartamento 3\"");
        fields.put("city", "\"Mixco\"");
        fields.put("stateOrDepartment", "\"Guatemala\"");
        fields.put("postalCode", "\"01057\"");
        fields.put("country", "\"Guatemala\"");
        fields.put("references", "\"Frente al parque, casa verde\"");
        for (String override : overrides) {
            String[] parts = override.split(":", 2);
            fields.put(parts[0].trim().replace("\"", ""), parts[1].trim());
        }
        StringJoiner json = new StringJoiner(", ", "{", "}");
        fields.forEach((key, value) -> json.add("\"" + key + "\": " + value));
        return json.toString();
    }

    private List<Address> addresses(CustomerAccount account) {
        return addressRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                account.customer().getTenantId(), account.customer().getId());
    }

    private List<UUID> defaultIds(CustomerAccount account) {
        return addresses(account).stream()
                .filter(address -> Boolean.TRUE.equals(address.getIsDefault()))
                .map(Address::getId)
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
