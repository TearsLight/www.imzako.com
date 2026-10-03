package com.imzako.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserAccessServiceTest {
    MongoOperations mongo;
    UserAccessService service;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoOperations.class);
        service = new UserAccessService(mongo);
        when(mongo.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);
    }

    @Test
    void persistsPermissionLevelsAndReadsThemBack() {
        when(mongo.findById(1_000_001L, AdminUser.class))
                .thenReturn(new AdminUser(1_000_001L, 3, java.time.Instant.now(), "test"));

        var result = service.setPermission(1_000_001L, 3, "console");

        var saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(mongo).save(saved.capture());
        assertThat(saved.getValue().level()).isEqualTo(3);
        assertThat(result.permissionLevel()).isEqualTo(3);
    }

    @Test
    void persistsAndRemovesTemporaryBans() {
        when(mongo.findById(1_000_001L, UserBan.class)).thenAnswer(invocation -> null);
        service.ban(1_000_001L, 24, "刷屏", "console");
        verify(mongo).save(any(UserBan.class));

        service.pardon(1_000_001L);
        verify(mongo).remove(any(Query.class), eq(UserBan.class));
    }

    @Test
    void rejectsInvalidPermissionAndBanValues() {
        assertThatThrownBy(() -> service.setPermission(1_000_001L, 4, "console"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ban(1_000_001L, 0, "reason", "console"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ban(1_000_001L, 1, " ", "console"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
