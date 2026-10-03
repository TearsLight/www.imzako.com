package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A temporary write restriction. Expired records are also removed by a TTL index. */
@Document(collection = "user_bans")
public record UserBan(
        @Id Long uid,
        Instant bannedAt,
        Instant bannedUntil,
        String reason,
        String bannedBy
) {
    public boolean activeAt(Instant instant) {
        return bannedUntil != null && bannedUntil.isAfter(instant);
    }
}
