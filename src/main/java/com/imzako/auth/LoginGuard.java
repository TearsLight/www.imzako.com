package com.imzako.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tracks consecutive failed sign-ins and demands a captcha once the configured
 * threshold is reached.
 *
 * <p>Failures are counted per identity and per client address. An attacker rotating
 * identities is still caught by the address bucket, while one rotating addresses is
 * caught by the identity bucket. Counters live in memory, which suits the
 * single-instance deployment this project uses.
 */
@Service
public class LoginGuard {
    static final int DEFAULT_THRESHOLD = 3;
    private static final String ANONYMOUS = "anonymous";

    private final int threshold;
    private final Duration window;
    private final int maxTrackedKeys;
    /**
     * Access order keeps recently used identities while bounding attacker-controlled
     * keys. Every access is synchronized, so LinkedHashMap remains safe under concurrent
     * login requests.
     */
    private final Map<String, Counter> counters = new LinkedHashMap<>(128, 0.75f, true);

    @Autowired
    public LoginGuard(
            @Value("${LOGIN_CAPTCHA_THRESHOLD:" + DEFAULT_THRESHOLD + "}") int threshold,
            @Value("${LOGIN_FAILURE_WINDOW_SECONDS:900}") long windowSeconds,
            @Value("${LOGIN_FAILURE_MAX_TRACKED_KEYS:10000}") int maxTrackedKeys) {
        this.threshold = Math.max(1, threshold);
        this.window = Duration.ofSeconds(Math.max(60, windowSeconds));
        this.maxTrackedKeys = Math.max(100, maxTrackedKeys);
    }

    LoginGuard(int threshold, long windowSeconds) {
        this(threshold, windowSeconds, 10_000);
    }

    public int threshold() {
        return threshold;
    }

    private record Counter(int failures, Instant lastFailureAt) {}

    /** True when further attempts must carry a solved captcha. */
    public synchronized boolean captchaRequired(String identity, String clientAddress) {
        return countFor(identityKey(identity)) >= threshold
                || countFor(addressKey(clientAddress)) >= threshold;
    }

    /** Records a failed attempt for both buckets. */
    public synchronized void recordFailure(String identity, String clientAddress) {
        bump(identityKey(identity));
        bump(addressKey(clientAddress));
    }

    /** Clears both buckets after a successful sign-in. */
    public synchronized void recordSuccess(String identity, String clientAddress) {
        counters.remove(identityKey(identity));
        counters.remove(addressKey(clientAddress));
    }

    private int countFor(String key) {
        Counter counter = counters.get(key);
        if (counter == null) return 0;
        if (isStale(counter)) {
            counters.remove(key);
            return 0;
        }
        return counter.failures();
    }

    private void bump(String key) {
        Instant now = Instant.now();
        Counter current = counters.get(key);
        if (current == null || isStale(current)) {
            if (current == null && counters.size() >= maxTrackedKeys) {
                var eldest = counters.keySet().iterator();
                if (eldest.hasNext()) counters.remove(eldest.next());
            }
            counters.put(key, new Counter(1, now));
            return;
        }
        counters.put(key, new Counter(current.failures() + 1, now));
    }

    private boolean isStale(Counter counter) {
        return Duration.between(counter.lastFailureAt(), Instant.now()).compareTo(window) > 0;
    }

    private String identityKey(String identity) {
        String value = identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
        return "id:" + (value.isEmpty() ? ANONYMOUS : value);
    }

    private String addressKey(String clientAddress) {
        String value = clientAddress == null ? "" : clientAddress.trim();
        if (value.length() > 128) value = value.substring(0, 128);
        return "ip:" + (value.isEmpty() ? ANONYMOUS : value);
    }

    synchronized int trackedKeys() {
        return counters.size();
    }
}
