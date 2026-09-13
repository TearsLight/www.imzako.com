package com.imzako.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Issues and checks email verification codes. Codes are short lived, single use and
 * limited both per resend cooldown and per rolling window, so the endpoint cannot be
 * turned into a mail relay or a brute-force oracle.
 */
@Service
public class VerificationCodeService {
    static final int CODE_DIGITS = 6;
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoOperations mongo;
    private final PasswordEncoder encoder;
    private final Duration ttl;
    private final Duration resendCooldown;
    private final int maxSendsPerWindow;

    public VerificationCodeService(
            MongoOperations mongo,
            PasswordEncoder encoder,
            @Value("${EMAIL_VERIFICATION_TTL_SECONDS:600}") long ttlSeconds,
            @Value("${EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS:60}") long cooldownSeconds,
            @Value("${EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR:5}") int maxSendsPerWindow) {
        this.mongo = mongo;
        this.encoder = encoder;
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
        this.resendCooldown = Duration.ofSeconds(Math.max(0, cooldownSeconds));
        this.maxSendsPerWindow = Math.max(1, maxSendsPerWindow);
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /** Creates or replaces the code for an address, returning null when still inside the cooldown. */
    public String issue(String emailKey, String email) {
        Instant now = Instant.now();
        EmailVerification existing = find(emailKey);

        if (existing != null) {
            if (existing.lastSentAt() != null
                    && Duration.between(existing.lastSentAt(), now).compareTo(resendCooldown) < 0) {
                return null;
            }
            if (withinSendWindow(existing, now) && existing.sendCount() >= maxSendsPerWindow) {
                throw new VerificationException(
                        VerificationException.Reason.RATE_LIMITED, "验证码发送过于频繁，请稍后再试");
            }
        }

        String code = randomCode();
        boolean sameWindow = existing != null && withinSendWindow(existing, now);
        int sendCount = sameWindow ? existing.sendCount() + 1 : 1;
        Instant windowStart = sameWindow && existing.sendWindowStartedAt() != null
                ? existing.sendWindowStartedAt()
                : now;

        var verification = new EmailVerification(
                emailKey,
                email,
                encoder.encode(code),
                now,
                now.plus(ttl),
                now,
                sendCount,
                windowStart,
                0
        );
        mongo.save(verification);
        return code;
    }

    /** Consumes the code on success. Throws when missing, expired or wrong. */
    public EmailVerification verify(String emailKey, String submitted) {
        String code = submitted == null ? "" : submitted.trim();
        if (!code.matches("\\d{" + CODE_DIGITS + "}")) {
            throw new VerificationException(VerificationException.Reason.INVALID_CODE, "验证码不正确或已过期");
        }

        // Count the attempt first so repeated guesses consume the code.
        EmailVerification verification = mongo.findAndModify(
                Query.query(where("_id").is(emailKey)),
                new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                EmailVerification.class
        );
        if (verification == null) {
            throw new VerificationException(VerificationException.Reason.INVALID_CODE, "验证码不正确或已过期");
        }
        if (verification.expiresAt() != null && verification.expiresAt().isBefore(Instant.now())) {
            mongo.remove(Query.query(where("_id").is(emailKey)), EmailVerification.class);
            throw new VerificationException(VerificationException.Reason.INVALID_CODE, "验证码不正确或已过期");
        }
        if (verification.attempts() > MAX_ATTEMPTS) {
            mongo.remove(Query.query(where("_id").is(emailKey)), EmailVerification.class);
            throw new VerificationException(
                    VerificationException.Reason.TOO_MANY_ATTEMPTS, "验证码尝试次数过多，请重新获取");
        }
        if (!encoder.matches(code, verification.codeHash())) {
            throw new VerificationException(VerificationException.Reason.INVALID_CODE, "验证码不正确或已过期");
        }
        mongo.remove(Query.query(where("_id").is(emailKey)), EmailVerification.class);
        return verification;
    }

    public void discard(String emailKey) {
        mongo.remove(Query.query(where("_id").is(emailKey)), EmailVerification.class);
    }

    private EmailVerification find(String emailKey) {
        return mongo.findOne(Query.query(where("_id").is(emailKey)), EmailVerification.class);
    }

    private boolean withinSendWindow(EmailVerification existing, Instant now) {
        return existing.sendWindowStartedAt() != null
                && Duration.between(existing.sendWindowStartedAt(), now).compareTo(Duration.ofHours(1)) < 0;
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < CODE_DIGITS; i++) code.append(RANDOM.nextInt(10));
        return code.toString();
    }

    public static class VerificationException extends RuntimeException {
        public enum Reason { INVALID_CODE, TOO_MANY_ATTEMPTS, RATE_LIMITED }

        private final Reason reason;

        public VerificationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
