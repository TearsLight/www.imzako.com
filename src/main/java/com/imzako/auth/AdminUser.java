package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persisted permission assignment. The historical collection name is retained so
 * existing administrator records continue to work after permission levels are
 * introduced. Records created before the level field existed are treated as level 2.
 */
@Document(collection = "admin_users")
public record AdminUser(@Id Long uid, int level, Instant grantedAt, String grantedBy) {}
