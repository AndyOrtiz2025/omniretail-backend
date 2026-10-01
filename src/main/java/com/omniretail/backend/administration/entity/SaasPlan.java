package com.omniretail.backend.administration.entity;

import com.omniretail.backend.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "saas_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaasPlan extends BaseEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @NotBlank
    @Size(max = 100)
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Positive
    @Column(name = "max_branches")
    private Integer maxBranches;

    @Positive
    @Column(name = "max_employees")
    private Integer maxEmployees;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "monthly_quetzales", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyQuetzales;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlanStatus status;

    @NotNull
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "capabilities", nullable = false, columnDefinition = "text[]")
    private List<String> capabilities;

}
