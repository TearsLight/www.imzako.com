package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

@Document(collection = "users")
public record UserAccount(
        @Id String id,
        long uid,
        String email,
        String emailKey,
        String username,
        String usernameKey,
        String passwordHash,
        String gender,
        LocalDate birthday,
        Instant createdAt,
        Instant updatedAt,
        boolean enabled
) {}
