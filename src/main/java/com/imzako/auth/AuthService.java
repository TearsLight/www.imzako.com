package com.imzako.auth;

import com.imzako.mail.MailService;
import com.imzako.mail.VerificationCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Upper bound for the stored profile image, applied to the data URI itself. */
    static final int MAX_AVATAR_LENGTH = 300_000;
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 128;
    static final int MIN_USERNAME_LENGTH = 3;
    static final int MAX_USERNAME_LENGTH = 32;

    private final MongoOperations mongo;
    private final UidSequence uidSequence;
    private final PasswordEncoder passwords;
    private final VerificationCodeService verificationCodes;
    private final MailService mail;
    private final boolean production;
    private final boolean emailVerificationEnabled;

    public AuthService(
            MongoOperations mongo,
            UidSequence uidSequence,
            PasswordEncoder passwords,
            VerificationCodeService verificationCodes,
            MailService mail,
            @Value("${APP_ENV:development}") String environment,
            @Value("${app.email-verification.enabled:false}") boolean emailVerificationEnabled) {
        this.mongo = mongo;
        this.uidSequence = uidSequence;
        this.passwords = passwords;
        this.verificationCodes = verificationCodes;
        this.mail = mail;
        this.production = "production".equalsIgnoreCase(environment.trim());
        this.emailVerificationEnabled = emailVerificationEnabled;
    }

    /** Whether new accounts must confirm their address before signing in. */
    public boolean isEmailVerificationEnabled() {
        return emailVerificationEnabled;
    }

    /**
     * Creates the account. With email verification enabled the account stays
     * unverified and a code is emailed; otherwise the address is trusted straight
     * away and the caller may sign the user in immediately.
     */
    public AuthenticatedUser register(String email, String username, String password, String avatar) {
        String normalizedUsername = normalizeDisplay(username);
        String normalizedEmail = normalizeEmail(email);
        String normalizedAvatar = normalizeAvatar(avatar);

        // Only the email identifies an account; usernames are allowed to repeat.
        if (mongo.exists(Query.query(where("emailKey").is(normalizedEmail)), UserAccount.class)) {
            throw new DuplicateAccountException("邮箱已被注册");
        }

        Instant now = Instant.now();
        var account = new UserAccount(
                null,
                uidSequence.next(),
                normalizedEmail,
                normalizedEmail,
                normalizedUsername,
                key(normalizedUsername),
                passwords.encode(password),
                normalizedAvatar,
                now,
                now,
                true,
                // Without the verification gate the address is accepted as given.
                !emailVerificationEnabled,
                0
        );
        AuthenticatedUser created;
        try {
            created = view(mongo.insert(account));
        } catch (DuplicateKeyException conflict) {
            throw new DuplicateAccountException("邮箱已被注册");
        }

        if (emailVerificationEnabled) {
            try {
                sendVerificationCode(created);
            } catch (RuntimeException deliveryFailure) {
                try {
                    verificationCodes.discard(created.email());
                    mongo.remove(Query.query(where("uid").is(created.uid())), UserAccount.class);
                } catch (RuntimeException cleanupFailure) {
                    deliveryFailure.addSuppressed(cleanupFailure);
                }
                throw deliveryFailure;
            }
        }
        return created;
    }

    /**
     * Issues a code, replacing any previous one once the resend cooldown has passed.
     * Outside production the code is logged so a developer can complete the flow
     * without access to the mailbox.
     */
    public void sendVerificationCode(AuthenticatedUser account) {
        String code = verificationCodes.issue(account.email(), account.email());
        if (code == null) {
            throw new VerificationCodeService.VerificationException(
                    VerificationCodeService.VerificationException.Reason.RATE_LIMITED,
                    "验证码已发送，请稍后再试");
        }
        mail.sendVerificationCode(account.email(), account.username(), code, verificationCodes.ttlSeconds());
        if (!production) {
            log.info("Email verification code for {} is {} (development only)", account.email(), code);
        }
    }

    /**
     * Resends a code for an identifier. Unknown identifiers are silently accepted so
     * the endpoint cannot be used to discover which addresses are registered.
     */
    public void sendVerificationCodeFor(String identity) {
        UserAccount account = findByEmail(identity);
        if (account == null) account = findByUid(identity);
        if (account == null || account.emailVerified()) return;
        sendVerificationCode(view(account));
    }

    /** Confirms the emailed code and marks the address verified. */
    public AuthenticatedUser verifyEmail(String identity, String code) {
        UserAccount account = findByEmail(identity);
        if (account == null) account = findByUid(identity);
        if (account == null) throw new InvalidCredentialsException();

        verificationCodes.verify(account.emailKey(), code);
        return markEmailVerified(account);
    }

    private AuthenticatedUser markEmailVerified(UserAccount account) {
        if (account.emailVerified()) return view(account);
        return update(account.uid(), new Update().set("emailVerified", true));
    }

    /**
     * Resolves the account for a sign-in attempt. Callers must first reject
     * unverified accounts so a wrong password is never reported before the address
     * is confirmed.
     */
    public AuthenticatedUser authenticate(String identity, String password) {
        UserAccount account = findByEmail(identity);
        if (account == null) account = findByUid(identity);
        if (account == null || !account.enabled() || !passwords.matches(password, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        if (emailVerificationEnabled && !account.emailVerified()) {
            // Refresh the code so the caller can prompt for it immediately.
            try {
                sendVerificationCode(view(account));
            } catch (RuntimeException ignored) {
                // A throttled resend must not mask the verification requirement.
            }
            throw new EmailNotVerifiedException(maskEmail(account.email()));
        }
        return view(account);
    }

    /** Hides most of the address, e.g. {@code la******20@gmail.com}. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "***" + domain;
        int keep = Math.min(2, local.length() - 1);
        return local.substring(0, keep) + "*".repeat(Math.max(2, local.length() - keep)) + domain;
    }

    public AuthenticatedUser updateUsername(long uid, String username) {
        String normalized = normalizeDisplay(username);
        return update(uid, new Update().set("username", normalized).set("usernameKey", key(normalized)));
    }

    public AuthenticatedUser updateAvatar(long uid, String avatar) {
        String normalized = normalizeAvatar(avatar);
        Update update = new Update();
        if (normalized == null) update.unset("avatar");
        else update.set("avatar", normalized);
        return update(uid, update);
    }

    /**
     * Starts an email change. When verification is enabled the current account is
     * left untouched until the code sent to the new address is confirmed.
     */
    public EmailChange requestEmailChange(long uid, String currentPassword, String email) {
        UserAccount account = require(uid);
        verifyPassword(account, currentPassword);

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.equals(account.emailKey())) {
            return new EmailChange(view(account), null);
        }
        var taken = Query.query(where("emailKey").is(normalizedEmail).and("uid").ne(uid));
        if (mongo.exists(taken, UserAccount.class)) {
            throw new DuplicateAccountException("邮箱已被注册");
        }

        if (!emailVerificationEnabled) {
            return new EmailChange(updateEmail(uid, normalizedEmail), null);
        }

        String key = emailChangeKey(uid);
        String code = verificationCodes.issue(key, normalizedEmail);
        if (code == null) {
            throw new VerificationCodeService.VerificationException(
                    VerificationCodeService.VerificationException.Reason.RATE_LIMITED,
                    "验证码已发送，请稍后再试");
        }
        try {
            mail.sendVerificationCode(
                    normalizedEmail, account.username(), code, verificationCodes.ttlSeconds());
        } catch (RuntimeException deliveryFailure) {
            verificationCodes.discard(key);
            throw deliveryFailure;
        }
        return new EmailChange(null, maskEmail(normalizedEmail));
    }

    /** Finishes an email change after proving ownership of the pending address. */
    public AuthenticatedUser verifyEmailChange(long uid, String code) {
        String key = emailChangeKey(uid);
        var pending = verificationCodes.verify(key, code);
        String normalizedEmail = normalizeEmail(pending.email());
        var taken = Query.query(where("emailKey").is(normalizedEmail).and("uid").ne(uid));
        if (mongo.exists(taken, UserAccount.class)) {
            throw new DuplicateAccountException("邮箱已被注册");
        }
        try {
            return updateEmail(uid, normalizedEmail);
        } catch (DuplicateKeyException conflict) {
            throw new DuplicateAccountException("邮箱已被注册");
        }
    }

    private AuthenticatedUser updateEmail(long uid, String normalizedEmail) {
        return update(uid, new Update()
                .set("email", normalizedEmail)
                .set("emailKey", normalizedEmail)
                .set("emailVerified", true));
    }

    private static String emailChangeKey(long uid) {
        return "email-change:" + uid;
    }

    public void updatePassword(long uid, String currentPassword, String newPassword) {
        UserAccount account = require(uid);
        verifyPassword(account, currentPassword);
        if (newPassword == null
                || newPassword.length() < MIN_PASSWORD_LENGTH
                || newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new InvalidAccountException(
                    "密码长度应为 " + MIN_PASSWORD_LENGTH + "–" + MAX_PASSWORD_LENGTH + " 个字符");
        }
        update(uid, new Update()
                .set("passwordHash", passwords.encode(newPassword))
                .inc("sessionVersion", 1));
    }

    public AuthenticatedUser profile(long uid) {
        return view(require(uid));
    }

    public boolean isSessionValid(long uid, long sessionVersion) {
        UserAccount account = mongo.findOne(
                Query.query(where("uid").is(uid).and("enabled").is(true)),
                UserAccount.class);
        return account != null && account.sessionVersion() == sessionVersion;
    }

    private AuthenticatedUser update(long uid, Update update) {
        require(uid);
        update.set("updatedAt", Instant.now());
        UserAccount account = mongo.findAndModify(
                Query.query(where("uid").is(uid)),
                update,
                FindAndModifyOptions.options().returnNew(true),
                UserAccount.class
        );
        if (account == null) throw new InvalidCredentialsException();
        return view(account);
    }

    private UserAccount require(long uid) {
        UserAccount account = mongo.findOne(Query.query(where("uid").is(uid)), UserAccount.class);
        if (account == null) throw new InvalidCredentialsException();
        return account;
    }

    private void verifyPassword(UserAccount account, String currentPassword) {
        if (currentPassword == null || !passwords.matches(currentPassword, account.passwordHash())) {
            throw new InvalidAccountException("当前密码不正确");
        }
    }

    private UserAccount findByEmail(String identity) {
        if (identity == null || identity.isBlank()) return null;
        return mongo.findOne(Query.query(where("emailKey").is(normalizeEmail(identity))), UserAccount.class);
    }

    private UserAccount findByUid(String identity) {
        String value = identity == null ? "" : identity.trim();
        if (!value.matches("\\d{1,18}")) return null;
        try {
            return mongo.findOne(Query.query(where("uid").is(Long.parseLong(value))), UserAccount.class);
        } catch (NumberFormatException outOfRange) {
            return null;
        }
    }

    private AuthenticatedUser view(UserAccount account) {
        boolean admin = mongo.exists(Query.query(where("_id").is(account.uid())), AdminUser.class);
        return new AuthenticatedUser(
                account.uid(),
                account.email(),
                account.username(),
                account.avatar(),
                account.sessionVersion(),
                admin ? List.of("USER", "ADMIN") : List.of("USER")
        );
    }

    static String normalizeDisplay(String value) {
        if (value == null || value.isBlank()) throw new InvalidAccountException("用户名不能为空");
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
        if (normalized.length() < MIN_USERNAME_LENGTH || normalized.length() > MAX_USERNAME_LENGTH) {
            throw new InvalidAccountException(
                    "用户名长度应为 " + MIN_USERNAME_LENGTH + "–" + MAX_USERNAME_LENGTH + " 个字符");
        }
        if (!normalized.matches("[\\p{L}\\p{N}_.-]+")) {
            throw new InvalidAccountException("用户名只能包含文字、数字、点、横线和下划线");
        }
        return normalized;
    }

    static String key(String value) {
        return normalizeDisplay(value).toLowerCase(Locale.ROOT);
    }

    /** Emails are unique, so they are normalized without the username length and character rules. */
    static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) throw new InvalidAccountException("邮箱不能为空");
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** Accepts an image data URI, or null to clear the avatar. */
    static String normalizeAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) return null;
        String value = avatar.trim();
        if (!value.startsWith("data:image/")) {
            throw new InvalidAccountException("头像必须是图片数据");
        }
        if (value.length() > MAX_AVATAR_LENGTH) {
            throw new InvalidAccountException("头像文件过大");
        }
        return value;
    }

    public record AuthenticatedUser(
            long uid,
            String email,
            String username,
            String avatar,
            long sessionVersion,
            List<String> roles
    ) {}

    public record EmailChange(AuthenticatedUser user, String maskedEmail) {}

    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String message) { super(message); }
    }

    public static class InvalidAccountException extends RuntimeException {
        public InvalidAccountException(String message) { super(message); }
    }

    public static class InvalidCredentialsException extends RuntimeException {}

    /** Raised after a correct password when the address still needs confirming. */
    public static class EmailNotVerifiedException extends RuntimeException {
        private final String maskedEmail;

        public EmailNotVerifiedException(String maskedEmail) {
            super("邮箱尚未验证");
            this.maskedEmail = maskedEmail;
        }

        public String maskedEmail() {
            return maskedEmail;
        }
    }
}
