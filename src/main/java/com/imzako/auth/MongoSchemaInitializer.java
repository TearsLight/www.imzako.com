package com.imzako.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

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
        ensureCollection("sequences");

        var users = mongo.indexOps(UserAccount.class);
        users.createIndex(new Index().on("uid", Sort.Direction.ASC).unique().named("uid_unique"));
        users.createIndex(new Index().on("emailKey", Sort.Direction.ASC).unique().named("email_unique"));
        users.createIndex(new Index().on("usernameKey", Sort.Direction.ASC).unique().named("username_unique"));

        mongo.upsert(
                Query.query(where("_id").is("users")),
                new Update().setOnInsert("value", UidSequence.FIRST_UID - 1),
                UidSequence.Sequence.class
        );
        log.info("MongoDB account schema ready: users, admin_users, sequences");
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
