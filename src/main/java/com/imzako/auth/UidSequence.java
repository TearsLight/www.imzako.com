package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Component
public class UidSequence {
    static final long FIRST_UID = 1_000_001L;
    private final MongoOperations mongo;

    public UidSequence(MongoOperations mongo) {
        this.mongo = mongo;
    }

    public long next() {
        var sequence = mongo.findAndModify(
                Query.query(where("_id").is("users")),
                new Update().inc("value", 1),
                FindAndModifyOptions.options().returnNew(true),
                Sequence.class
        );
        if (sequence == null || sequence.value() < FIRST_UID) {
            throw new IllegalStateException("User UID sequence is not initialized");
        }
        return sequence.value();
    }

    @Document(collection = "sequences")
    public record Sequence(@Id String id, long value) {}
}
