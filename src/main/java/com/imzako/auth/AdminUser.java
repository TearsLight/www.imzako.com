package com.imzako.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A UID present in this collection receives the ADMIN role at login. */
@Document(collection = "admin_users")
public record AdminUser(@Id Long uid, Instant grantedAt, String grantedBy) {}
