package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankAccount extends TenantScopedEntity {

    @NotBlank
    @Size(max = 80)
    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @NotBlank
    @Size(max = 120)
    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @NotBlank
    @Size(max = 24)
    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @NotBlank
    @Size(max = 24)
    @Column(name = "account_number_masked", nullable = false)
    private String accountNumberMasked;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private BankAccountType accountType;

    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false)
    private String currency;

    @NotBlank
    @Size(max = 50)
    @Column(name = "alias", nullable = false)
    private String alias;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "branch_ids", columnDefinition = "uuid[]")
    private List<UUID> branchIds;

    @Size(max = 300)
    @Column(name = "transfer_instructions")
    private String transferInstructions;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BankAccountStatus status = BankAccountStatus.active;
}
