package com.imzako.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class AuthService {
    private final MongoOperations mongo;
    private final UidSequence uidSequence;
    private final PasswordEncoder passwords;

    public AuthService(MongoOperations mongo, UidSequence uidSequence, PasswordEncoder passwords) {
        this.mongo = mongo;
        this.uidSequence = uidSequence;
        this.passwords = passwords;
    }

    public AuthenticatedUser register(String email, String username, String password, String gender, LocalDate birthday) {
        String normalizedUsername = normalizeDisplay(username);
        String usernameKey = key(normalizedUsername);
        String normalizedEmail = normalizeEmail(email);
        String normalizedGender = normalizeGender(gender);
        if (birthday != null && !birthday.isBefore(LocalDate.now())) {
            throw new InvalidAccountException("生日必须早于今天");
        }

        var duplicate = new Criteria().orOperator(
                where("usernameKey").is(usernameKey),
                where("emailKey").is(normalizedEmail)
        );
        if (mongo.exists(Query.query(duplicate), UserAccount.class)) {
            throw new DuplicateAccountException("用户名或邮箱已被注册");
        }

        Instant now = Instant.now();
        var account = new UserAccount(
                null,
                uidSequence.next(),
                normalizedEmail,
                normalizedEmail,
                normalizedUsername,
                usernameKey,
                passwords.encode(password),
                normalizedGender,
                birthday,
                now,
                now,
                true
        );
        try {
            return view(mongo.insert(account));
        } catch (DuplicateKeyException conflict) {
            throw new DuplicateAccountException("用户名或邮箱已被注册");
        }
    }

    public AuthenticatedUser login(String identity, String password) {
        String identityKey = key(identity);
        var identityMatch = new Criteria().orOperator(
                where("usernameKey").is(identityKey),
                where("emailKey").is(identityKey)
        );
        UserAccount account = mongo.findOne(Query.query(identityMatch), UserAccount.class);
        if (account == null || !account.enabled() || !passwords.matches(password, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return view(account);
    }

    private AuthenticatedUser view(UserAccount account) {
        boolean admin = mongo.exists(Query.query(where("_id").is(account.uid())), AdminUser.class);
        return new AuthenticatedUser(
                account.uid(),
                account.email(),
                account.username(),
                account.gender(),
                account.birthday(),
                admin ? List.of("USER", "ADMIN") : List.of("USER")
        );
    }

    static String normalizeDisplay(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
    }

    static String key(String value) {
        return normalizeDisplay(value).toLowerCase(Locale.ROOT);
    }

    static String normalizeEmail(String value) {
        return key(value);
    }

    static String normalizeGender(String gender) {
        String value = gender == null || gender.isBlank() ? "undisclosed" : gender.trim().toLowerCase(Locale.ROOT);
        if (!List.of("male", "female", "other", "undisclosed").contains(value)) {
            throw new InvalidAccountException("性别字段无效");
        }
        return value;
    }

    public record AuthenticatedUser(
            long uid,
            String email,
            String username,
            String gender,
            LocalDate birthday,
            List<String> roles
    ) {}

    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String message) { super(message); }
    }

    public static class InvalidAccountException extends RuntimeException {
        public InvalidAccountException(String message) { super(message); }
    }

    public static class InvalidCredentialsException extends RuntimeException {}
}
