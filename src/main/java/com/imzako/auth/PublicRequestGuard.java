package com.imzako.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounds expensive unauthenticated operations by client address. Buckets live in
 * memory for the single-instance deployment and are capped so attacker-controlled
 * addresses cannot grow the process indefinitely.
 */
@Service
public class PublicRequestGuard {
    private final int registrationsPerHour;
    private final int resendsPerHour;
    private final int captchasPerMinute;
    private final int maxTrackedKeys;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(128, 0.75f, true);

    private record Bucket(int requests, Instant expiresAt) {}

    public PublicRequestGuard(
            @Value("${REGISTRATION_MAX_REQUESTS_PER_HOUR:10}") int registrationsPerHour,
            @Value("${VERIFICATION_RESEND_MAX_REQUESTS_PER_HOUR:20}") int resendsPerHour,
            @Value("${CAPTCHA_MAX_REQUESTS_PER_MINUTE:30}") int captchasPerMinute,
            @Value("${PUBLIC_RATE_LIMIT_MAX_TRACKED_KEYS:10000}") int maxTrackedKeys) {
        this.registrationsPerHour = Math.max(1, registrationsPerHour);
        this.resendsPerHour = Math.max(1, resendsPerHour);
        this.captchasPerMinute = Math.max(1, captchasPerMinute);
        this.maxTrackedKeys = Math.max(100, maxTrackedKeys);
    }

    public void registration(String address) {
        require("register", address, registrationsPerHour, Duration.ofHours(1));
    }

    public void resend(String address) {
        require("resend", address, resendsPerHour, Duration.ofHours(1));
    }

    public void captcha(String address) {
        require("captcha", address, captchasPerMinute, Duration.ofMinutes(1));
    }

    private synchronized void require(String operation, String address, int limit, Duration window) {
        String value = address == null || address.isBlank() ? "unknown" : address.trim();
        if (value.length() > 128) value = value.substring(0, 128);
        String key = operation + ":" + value;
        Instant now = Instant.now();
        Bucket current = buckets.get(key);

        if (current == null || !current.expiresAt().isAfter(now)) {
            if (current == null && buckets.size() >= maxTrackedKeys) {
                var eldest = buckets.keySet().iterator();
                if (eldest.hasNext()) buckets.remove(eldest.next());
            }
            buckets.put(key, new Bucket(1, now.plus(window)));
            return;
        }
        if (current.requests() >= limit) {
            throw new PublicRateLimitException();
        }
        buckets.put(key, new Bucket(current.requests() + 1, current.expiresAt()));
    }

    synchronized int trackedKeys() {
        return buckets.size();
    }
}
