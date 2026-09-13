package com.imzako.mail;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A pending email verification. Only one may exist per address; issuing a new code
 * replaces the previous one. The code itself is stored as a salted hash so a
 * database reader cannot reuse it, and {@code sendCount} drives resend throttling.
 */
@Document(collection = "email_verifications")
public record EmailVerification(
        @Id String emailKey,
        String email,
        String codeHash,
        Instant createdAt,
        Instant expiresAt,
        Instant lastSentAt,
        int sendCount,
        Instant sendWindowStartedAt,
        int attempts
) {}
