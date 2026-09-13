package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A website account, addressed by {@code uid} everywhere. Username is a display
 * label only and may repeat, so {@code usernameKey} carries no unique index;
 * {@code uid} and {@code emailKey} are the unique identifiers.
 *
 * <p>{@code emailVerified} gates sign-in: an account created by the public form
 * must confirm the emailed code before it can log in.
 */
@Document(collection = "users")
public record UserAccount(
        @Id String id,
        long uid,
        String email,
        String emailKey,
        String username,
        String usernameKey,
        String passwordHash,
        String avatar,
        Instant createdAt,
        Instant updatedAt,
        boolean enabled,
        boolean emailVerified,
        long sessionVersion
) {}
