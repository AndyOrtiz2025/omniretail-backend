package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.BankAccount;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.entity.BankAccountType;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BankAccountControllerTest {

    private static final String BASE_URL = "/api/v1/administration/bank-accounts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void createBankAccountSuccessMasksAccountNumber() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Distribuidora Central","accountNumber":"123456789012","accountType":"monetary","currency":"GTQ","alias":"Cuenta Principal"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankName").value("Banco Industrial"))
                .andExpect(jsonPath("$.holderName").value("Distribuidora Central"))
                .andExpect(jsonPath("$.accountNumber").value("123456789012"))
                .andExpect(jsonPath("$.accountNumberMasked").value("********9012"))
                .andExpect(jsonPath("$.accountType").value("monetary"))
                .andExpect(jsonPath("$.currency").value("GTQ"))
                .andExpect(jsonPath("$.alias").value("Cuenta Principal"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()));
    }

    @Test
    void createBankAccountSameAliasAllowed() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Titular Uno","accountNumber":"11112222","accountType":"monetary","currency":"GTQ","alias":"Cuenta Uno"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String secondBody =
                """
                {"bankName":"Banco G&T","holderName":"Titular Dos","accountNumber":"33334444","accountType":"savings","currency":"GTQ","alias":"Cuenta Uno"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alias").value("Cuenta Uno"));
    }

    @Test
    void createBankAccountDuplicateNumberWithDifferentFormatConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        persistBankAccount(tenant, "Cuenta Existente", "12345678", BankAccountStatus.active);

        String body =
                """
                {"bankName":"Banco G&T","holderName":"Titular","accountNumber":"1234-5678","accountType":"savings","currency":"GTQ","alias":"Cuenta Nueva"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_ACCOUNT_NUMBER_EXISTS"));
    }

    @Test
    void createBankAccountWithNumberOfArchivedAccountConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        persistBankAccount(tenant, "Cuenta Archivada", "12345678", BankAccountStatus.archived);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"1234 5678","accountType":"monetary","currency":"GTQ","alias":"Cuenta Nueva"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_ACCOUNT_NUMBER_EXISTS"));
    }

    @Test
    void updateBankAccountWithNumberOfAnotherAccountConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        persistBankAccount(tenant, "Cuenta Uno", "12345678", BankAccountStatus.active);
        BankAccount second = persistBankAccount(tenant, "Cuenta Dos", "87654321", BankAccountStatus.active);

        String updateBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"1234-5678","accountType":"monetary","currency":"GTQ","alias":"Cuenta Dos","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + second.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANK_ACCOUNT_NUMBER_EXISTS"));
    }

    @Test
    void updateBankAccountWithOwnNumberInDifferentFormatSucceeds() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        BankAccount bankAccount = persistBankAccount(tenant, "Cuenta Uno", "12345678", BankAccountStatus.active);

        String updateBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"1234-5678","accountType":"monetary","currency":"GTQ","alias":"Cuenta Uno","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + bankAccount.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("12345678"));
    }

    @Test
    void createBankAccountWithoutBranchIdsReturnsEmptyListAndDeduplicates() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Branch branch = persistBranch(tenant, "Sucursal Centro", BranchStatus.active);

        String withoutBranches =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"11112222","accountType":"monetary","currency":"GTQ","alias":"Sin Sucursales"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutBranches))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branchIds").isArray())
                .andExpect(jsonPath("$.branchIds.length()").value(0));

        String withDuplicates =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"33334444","accountType":"monetary","currency":"GTQ","alias":"Con Duplicados","branchIds":["%s","%s"]}
                """
                        .formatted(branch.getId(), branch.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDuplicates))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branchIds.length()").value(1))
                .andExpect(jsonPath("$.branchIds[0]").value(branch.getId().toString()));
    }

    @Test
    void createBankAccountWithInactiveBranchConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Branch inactiveBranch = persistBranch(tenant, "Sucursal Cerrada", BranchStatus.inactive);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"11112222","accountType":"monetary","currency":"GTQ","alias":"Cuenta","branchIds":["%s"]}
                """
                        .formatted(inactiveBranch.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
    }

    @Test
    void updateBankAccountKeepsAlreadyAssignedInactiveBranch() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Branch branch = persistBranch(tenant, "Sucursal Que Cierra", BranchStatus.active);
        BankAccount bankAccount = persistBankAccount(tenant, "Cuenta", "11112222", BankAccountStatus.active);
        bankAccount.setBranchIds(List.of(branch.getId()));
        bankAccountRepository.save(bankAccount);
        branch.setStatus(BranchStatus.inactive);
        branchRepository.save(branch);

        String updateBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountType":"monetary","currency":"GTQ","alias":"Cuenta","branchIds":["%s"],"status":"active"}
                """
                        .formatted(branch.getId());

        mockMvc.perform(put(BASE_URL + "/" + bankAccount.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchIds[0]").value(branch.getId().toString()));
    }

    @Test
    void sameAliasAllowedAcrossTenantsAndCrossTenantIsolation() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA);
        String tokenB = tokenFor(tenantB);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Titular Compartido","accountNumber":"55556666","accountType":"monetary","currency":"GTQ","alias":"Cuenta Compartida"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        BankAccount accountInTenantA =
                persistBankAccount(tenantA, "Cuenta Aislada", "77778888", BankAccountStatus.active);

        mockMvc.perform(get(BASE_URL + "/" + accountInTenantA.getId()).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BANK_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void createBankAccountWithBranchFromOtherTenantReturnsNotFound() throws Exception {
        Tenant tenant = persistTenant();
        Tenant otherTenant = persistTenant();
        String token = tokenFor(tenant);
        Branch foreignBranch = persistBranch(otherTenant, "Sucursal Ajena", BranchStatus.active);

        String body =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"11112222","accountType":"monetary","currency":"GTQ","alias":"Cuenta Con Sucursal","branchIds":["%s"]}
                """
                        .formatted(foreignBranch.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void updateBankAccountSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        BankAccount bankAccount = persistBankAccount(tenant, "Cuenta Original", "11112222", BankAccountStatus.active);

        String updateBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular Actualizado","accountType":"monetary","currency":"GTQ","alias":"Cuenta Renombrada","transferInstructions":"Enviar comprobante por correo","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + bankAccount.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alias").value("Cuenta Renombrada"))
                .andExpect(jsonPath("$.transferInstructions").value("Enviar comprobante por correo"));
    }

    @Test
    void updateBankAccountWithBlankAccountNumberKeepsPreviousValue() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        BankAccount bankAccount = persistBankAccount(tenant, "Cuenta Original", "123456789012", BankAccountStatus.active);

        String updateBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"","accountType":"monetary","currency":"GTQ","alias":"Cuenta Original","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + bankAccount.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("123456789012"))
                .andExpect(jsonPath("$.accountNumberMasked").value("********9012"));
    }

    @Test
    void archiveBankAccountSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        BankAccount bankAccount =
                persistBankAccount(tenant, "Cuenta A Archivar", "11112222", BankAccountStatus.active);

        mockMvc.perform(delete(BASE_URL + "/" + bankAccount.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_URL + "/" + bankAccount.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void listBankAccountsWithStatusFilterAndPagination() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        persistBankAccount(tenant, "Cuenta Activa", "11112222", BankAccountStatus.active);
        persistBankAccount(tenant, "Cuenta Inactiva", "33334444", BankAccountStatus.inactive);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].alias").value("Cuenta Activa"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void listActiveBankAccounts() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        persistBankAccount(tenant, "Cuenta Activa Uno", "11112222", BankAccountStatus.active);
        persistBankAccount(tenant, "Cuenta Archivada", "33334444", BankAccountStatus.archived);

        mockMvc.perform(get(BASE_URL + "/active").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].alias").value("Cuenta Activa Uno"));
    }

    @Test
    void createBankAccountValidationBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String invalidCurrencyBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"11112222","accountType":"monetary","currency":"EUR","alias":"Cuenta Invalida"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidCurrencyBody))
                .andExpect(status().isBadRequest());

        String invalidAccountNumberBody =
                """
                {"bankName":"Banco Industrial","holderName":"Titular","accountNumber":"ABC12345","accountType":"monetary","currency":"GTQ","alias":"Cuenta Invalida"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAccountNumberBody))
                .andExpect(status().isBadRequest());
    }

    private BankAccount persistBankAccount(Tenant tenant, String alias, String accountNumber, BankAccountStatus status) {
        BankAccount bankAccount = BankAccount.builder()
                .bankName("Banco Industrial")
                .holderName("Titular Demo")
                .accountNumber(accountNumber)
                .accountNumberMasked(mask(accountNumber))
                .accountType(BankAccountType.monetary)
                .currency("GTQ")
                .alias(alias)
                .status(status)
                .build();
        bankAccount.setTenantId(tenant.getId());
        return bankAccountRepository.save(bankAccount);
    }

    private static String mask(String accountNumber) {
        if (accountNumber.length() <= 4) {
            return accountNumber;
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    private Branch persistBranch(Tenant tenant, String name, BranchStatus status) {
        Branch branch = Branch.builder()
                .code("BR-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .type(BranchType.store)
                .status(status)
                .build();
        branch.setTenantId(tenant.getId());
        return branchRepository.save(branch);
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build();
        return tenantRepository.save(tenant);
    }

    private String tokenFor(Tenant tenant) {
        return tokenFor(tenant, List.of("admin.bank_accounts.manage"));
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        Session session = Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        session = sessionRepository.save(session);

        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
