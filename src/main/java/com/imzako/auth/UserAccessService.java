package com.imzako.auth;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Administrative permission and temporary-ban operations used by RCON and HTTP authorization. */
@Service
public class UserAccessService {
    public static final int USER = 1;
    public static final int ADMIN = 2;
    public static final int OWNER = 3;
    private static final long MAX_BAN_HOURS = 24L * 365L * 10L;

    private final MongoOperations mongo;

    public UserAccessService(MongoOperations mongo) {
        this.mongo = mongo;
    }

    public AccessStatus setPermission(long uid, int level, String actor) {
        requireUser(uid);
        if (level < USER || level > OWNER) {
            throw new IllegalArgumentException("权限等级只能是 1、2 或 3");
        }
        mongo.save(new AdminUser(uid, level, Instant.now(), cleanActor(actor)));
        return status(uid);
    }

    public AccessStatus ban(long uid, long hours, String reason, String actor) {
        requireUser(uid);
        if (hours < 1 || hours > MAX_BAN_HOURS) {
            throw new IllegalArgumentException("封禁小时数必须在 1 到 " + MAX_BAN_HOURS + " 之间");
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > 300) {
            throw new IllegalArgumentException("封禁理由长度必须在 1 到 300 个字符之间");
        }
        Instant now = Instant.now();
        mongo.save(new UserBan(uid, now, now.plus(Duration.ofHours(hours)), normalizedReason, cleanActor(actor)));
        return status(uid);
    }

    public AccessStatus pardon(long uid) {
        requireUser(uid);
        mongo.remove(Query.query(where("_id").is(uid)), UserBan.class);
        return status(uid);
    }

    public AccessStatus status(long uid) {
        requireUser(uid);
        AdminUser permission = mongo.findById(uid, AdminUser.class);
        int level = normalizeLevel(permission);
        UserBan ban = mongo.findById(uid, UserBan.class);
        Instant now = Instant.now();
        if (ban != null && !ban.activeAt(now)) {
            mongo.remove(Query.query(where("_id").is(uid)), UserBan.class);
            ban = null;
        }
        return new AccessStatus(uid, level, ban != null, ban == null ? null : ban.bannedUntil(),
                ban == null ? null : ban.reason());
    }

    public int permissionLevel(long uid) {
        AdminUser permission = mongo.findById(uid, AdminUser.class);
        return normalizeLevel(permission);
    }

    public UserBan activeBan(long uid) {
        UserBan ban = mongo.findById(uid, UserBan.class);
        return ban != null && ban.activeAt(Instant.now()) ? ban : null;
    }

    public void requirePermission(long uid, int minimum) {
        if (permissionLevel(uid) < minimum) {
            throw new ForbiddenOperationException("权限不足，需要等级 " + minimum);
        }
    }

    public void requireWritable(long uid) {
        UserBan ban = activeBan(uid);
        if (ban != null) {
            throw new ForbiddenOperationException("账号已被封禁至 " + ban.bannedUntil() + "：" + ban.reason());
        }
    }

    static int normalizeLevel(AdminUser permission) {
        if (permission == null) return USER;
        // Old admin_users documents do not have a level and deserialize as zero.
        if (permission.level() == 0) return ADMIN;
        return Math.max(USER, Math.min(OWNER, permission.level()));
    }

    private void requireUser(long uid) {
        if (!mongo.exists(Query.query(where("uid").is(uid)), UserAccount.class)) {
            throw new IllegalArgumentException("用户不存在：" + uid);
        }
    }

    private static String cleanActor(String actor) {
        String value = actor == null ? "rcon" : actor.strip();
        return value.isEmpty() ? "rcon" : value.substring(0, Math.min(value.length(), 100));
    }

    public record AccessStatus(long uid, int permissionLevel, boolean banned, Instant bannedUntil, String banReason) {}

    public static class ForbiddenOperationException extends RuntimeException {
        public ForbiddenOperationException(String message) {
            super(message);
        }
    }
}
