package com.omniretail.backend.shared.security;

/** Claims propios que emite {@code JwtService} y lee {@link TenantJwtAuthenticationConverter}. */
public final class JwtClaimNames {

    public static final String TENANT_ID = "tenantId";
    public static final String USER_TYPE = "userType";
    public static final String ROLE_ID = "roleId";
    public static final String BRANCH_ID = "branchId";
    public static final String SESSION_ID = "sid";

    private JwtClaimNames() {
    }
}
