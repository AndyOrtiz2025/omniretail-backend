package com.omniretail.backend.auth.entity;

public enum AccountStatus {
    pending_verification,
    active,
    temporarily_locked,
    password_reset_required,
    disabled,
    archived
}
