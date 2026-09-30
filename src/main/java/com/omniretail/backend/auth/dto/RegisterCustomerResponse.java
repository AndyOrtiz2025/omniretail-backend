package com.omniretail.backend.auth.dto;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import java.util.UUID;

/** Nunca incluye el token de verificacion: viaja solo en el correo. */
public record RegisterCustomerResponse(UserView user) {

    public record UserView(UUID id, String name, String email, UserType type) {

        public static UserView from(User user) {
            return new UserView(user.getId(), user.getName(), user.getEmail(), user.getType());
        }
    }
}
