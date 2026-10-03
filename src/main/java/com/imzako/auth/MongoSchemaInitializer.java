package com.imzako.auth;

import com.imzako.mail.EmailVerification;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Component
@ConditionalOnProperty(name = "app.mongodb.initialize", havingValue = "true", matchIfMissing = true)
public class MongoSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(MongoSchemaInitializer.class);
    private final MongoOperations mongo;

    public MongoSchemaInitializer(MongoOperations mongo) {
        this.mongo = mongo;
    }

    @PostConstruct
    void initialize() {
        ensureCollection("users");
        ensureCollection("admin_users");
        ensureCollection("user_bans");
        ensureCollection("sequences");
        ensureCollection("email_verifications");

        var users = mongo.indexOps(UserAccount.class);
        users.createIndex(new Index().on("uid", Sort.Direction.ASC).unique().named("uid_unique"));
        users.createIndex(new Index().on("emailKey", Sort.Direction.ASC).unique().named("email_unique"));
        // Usernames are display labels and may repeat, so no unique index belongs here.
        dropLegacyUsernameIndex(users);

        ensureVerificationTtlIndex();
        ensureBanTtlIndex();

        mongo.upsert(
                Query.query(where("_id").is("users")),
                new Update().setOnInsert("value", UidSequence.FIRST_UID - 1),
                UidSequence.Sequence.class
        );

        verifyExistingAccounts();
        log.info("MongoDB account schema ready: users, admin_users, user_bans, sequences, email_verifications");
    }

    /**
     * Accounts created before email verification existed have no {@code emailVerified}
     * field, which would read as unverified and lock their owners out. Only documents
     * where the field is absent are touched, so a genuinely unverified new account is
     * never silently confirmed.
     */
    private void verifyExistingAccounts() {
        var missing = Query.query(where("emailVerified").exists(false));
        var result = mongo.updateMulti(missing, new Update().set("emailVerified", true), UserAccount.class);
        if (result.getModifiedCount() > 0) {
            log.info("Marked {} pre-existing account(s) as email verified", result.getModifiedCount());
        }
    }

    /** Replaces the earlier plain expiresAt index with a real MongoDB TTL index. */
    private void ensureVerificationTtlIndex() {
        var indexes = mongo.indexOps(EmailVerification.class);
        indexes.getIndexInfo().stream()
                .filter(index -> "verification_expiry".equals(index.getName()))
                .filter(index -> !Duration.ZERO.equals(index.getExpireAfter().orElse(null)))
                .findFirst()
                .ifPresent(index -> indexes.dropIndex("verification_expiry"));
        indexes.createIndex(new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .expire(Duration.ZERO)
                .named("verification_expiry"));
    }

    private void ensureBanTtlIndex() {
        mongo.indexOps(UserBan.class).createIndex(new Index()
                .on("bannedUntil", Sort.Direction.ASC)
                .expire(Duration.ZERO)
                .named("user_ban_expiry"));
    }

    /**
     * Earlier versions enforced unique usernames. That index must go, otherwise
     * duplicate usernames fail with a duplicate-key error.
     */
    private void dropLegacyUsernameIndex(IndexOperations users) {
        try {
            if (users.getIndexInfo().stream().anyMatch(index -> "username_unique".equals(index.getName()))) {
                users.dropIndex("username_unique");
                log.info("Dropped legacy unique index 'username_unique'; usernames may now repeat");
            }
        } catch (RuntimeException failed) {
            log.warn("Could not drop legacy index 'username_unique': {}", failed.getMessage());
        }
    }

    private void ensureCollection(String name) {
        if (mongo.collectionExists(name)) return;
        try {
            mongo.createCollection(name);
        } catch (DataIntegrityViolationException ignored) {
            // Another application instance may have created it between the check and command.
        }
    }
}
