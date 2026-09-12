package com.imzako.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock MongoOperations mongo;
    @Mock UidSequence sequence;
    PasswordEncoder passwords;
    AuthService service;

    @BeforeEach
    void setUp() {
        passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new AuthService(mongo, sequence, passwords);
    }

    @Test
    void registrationNormalizesIdentityAssignsFirstUidAndHashesPassword() {
        when(sequence.next()).thenReturn(1_000_001L);
        when(mongo.insert(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var user = service.register(" User@Example.COM ", " 新用户 ", "correct horse battery", "female", LocalDate.of(2000, 1, 2));

        assertThat(user.uid()).isEqualTo(1_000_001L);
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.username()).isEqualTo("新用户");
        assertThat(user.roles()).containsExactly("USER");

        var account = ArgumentCaptor.forClass(UserAccount.class);
        verify(mongo).insert(account.capture());
        assertThat(account.getValue().passwordHash()).startsWith("{bcrypt}").doesNotContain("correct horse battery");
        assertThat(passwords.matches("correct horse battery", account.getValue().passwordHash())).isTrue();
    }

    @Test
    void loginAcceptsEmailAndGetsAdminRoleFromAdminCollection() {
        var account = account(passwords.encode("a-secure-password"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account);
        when(mongo.exists(any(Query.class), eq(AdminUser.class))).thenReturn(true);

        var user = service.login("USER@EXAMPLE.COM", "a-secure-password");

        assertThat(user.roles()).containsExactly("USER", "ADMIN");
        assertThat(user.uid()).isEqualTo(1_000_001L);
    }

    @Test
    void loginDoesNotRevealWhetherAccountOrPasswordWasWrong() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account(passwords.encode("right-password")));
        assertThatThrownBy(() -> service.login("member", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(null);
        assertThatThrownBy(() -> service.login("missing", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }

    @Test
    void registrationRejectsDuplicatesInvalidGenderAndFutureBirthday() {
        when(mongo.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);
        assertThatThrownBy(() -> service.register("user@example.com", "member", "secure-password", null, null))
                .isInstanceOf(AuthService.DuplicateAccountException.class);

        assertThatThrownBy(() -> AuthService.normalizeGender("secret"))
                .isInstanceOf(AuthService.InvalidAccountException.class);

        assertThatThrownBy(() -> service.register("user@example.com", "member", "secure-password", null, LocalDate.now().plusDays(1)))
                .isInstanceOf(AuthService.InvalidAccountException.class);
    }

    private UserAccount account(String passwordHash) {
        Instant now = Instant.parse("2026-09-12T08:00:00Z");
        return new UserAccount("mongo-id", 1_000_001L, "user@example.com", "user@example.com", "member", "member",
                passwordHash, "undisclosed", null, now, now, true);
    }
}
