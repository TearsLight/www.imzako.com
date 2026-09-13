package com.imzako.auth;

import com.imzako.mail.MailService;
import com.imzako.mail.EmailVerification;
import com.imzako.mail.VerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    private static final String AVATAR = "data:image/png;base64,iVBORw0KGgo=";

    @Mock MongoOperations mongo;
    @Mock UidSequence sequence;
    @Mock VerificationCodeService verificationCodes;
    @Mock MailService mail;
    PasswordEncoder passwords;
    AuthService service;

    private AuthService newService(boolean emailVerificationEnabled) {
        return new AuthService(mongo, sequence, passwords, verificationCodes, mail, "test", emailVerificationEnabled);
    }

    @BeforeEach
    void setUp() {
        passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = newService(true);
    }

    @Test
    void registrationCreatesAnUnverifiedAccountAndEmailsACode() {
        when(sequence.next()).thenReturn(1_000_001L);
        when(mongo.insert(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationCodes.issue(anyString(), anyString())).thenReturn("123456");
        when(verificationCodes.ttlSeconds()).thenReturn(600L);

        var user = service.register(" User@Example.COM ", " 新用户 ", "correct horse battery", AVATAR);

        assertThat(user.uid()).isEqualTo(1_000_001L);
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.username()).isEqualTo("新用户");
        assertThat(user.avatar()).isEqualTo(AVATAR);
        assertThat(user.roles()).containsExactly("USER");

        var account = ArgumentCaptor.forClass(UserAccount.class);
        verify(mongo).insert(account.capture());
        assertThat(account.getValue().emailVerified()).isFalse();
        assertThat(account.getValue().passwordHash()).startsWith("{bcrypt}").doesNotContain("correct horse battery");
        assertThat(passwords.matches("correct horse battery", account.getValue().passwordHash())).isTrue();

        verify(mail).sendVerificationCode("user@example.com", "新用户", "123456", 600L);
    }

    @Test
    void withTheEmailGateOffRegistrationTrustsTheAddressAndSkipsTheMail() {
        var unguarded = newService(false);
        when(sequence.next()).thenReturn(1_000_010L);
        when(mongo.insert(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var user = unguarded.register("open@example.com", "open-user", "correct horse battery", null);

        var account = ArgumentCaptor.forClass(UserAccount.class);
        verify(mongo).insert(account.capture());
        assertThat(account.getValue().emailVerified()).isTrue();
        // No code is issued or mailed while the gate is closed.
        verifyNoInteractions(mail);
        verifyNoInteractions(verificationCodes);

        // And the account can sign in straight away despite never confirming an address.
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account.getValue());
        assertThat(unguarded.authenticate("open@example.com", "correct horse battery").uid())
                .isEqualTo(user.uid());
    }

    @Test
    void registrationDoesNotCheckUsernameUniqueness() {
        when(sequence.next()).thenReturn(1_000_002L);
        when(mongo.insert(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationCodes.issue(anyString(), anyString())).thenReturn("654321");

        var user = service.register("second@example.com", "新用户", "correct horse battery", null);

        assertThat(user.username()).isEqualTo("新用户");
        assertThat(user.avatar()).isNull();
        // Only the email is looked up for duplicates.
        verify(mongo, never()).exists(argThat(query -> query.toString().contains("usernameKey")), eq(UserAccount.class));
    }

    @Test
    void registrationRollsBackTheAccountWhenTheVerificationMailCannotBeSent() {
        when(sequence.next()).thenReturn(1_000_003L);
        when(mongo.insert(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationCodes.issue(anyString(), anyString())).thenReturn("123456");
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(mail).sendVerificationCode(anyString(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> service.register(
                "rollback@example.com", "rollback-user", "correct horse battery", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SMTP unavailable");

        verify(verificationCodes).discard("rollback@example.com");
        verify(mongo).remove(any(Query.class), eq(UserAccount.class));
    }

    @Test
    void registrationRejectsDuplicateEmail() {
        when(mongo.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);
        assertThatThrownBy(() -> service.register("user@example.com", "member", "secure-password", null))
                .isInstanceOf(AuthService.DuplicateAccountException.class);
        verifyNoInteractions(mail);
    }

    @Test
    void loginAcceptsEmailAndGetsAdminRoleFromAdminCollection() {
        var account = verifiedAccount(passwords.encode("a-secure-password"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account);
        when(mongo.exists(any(Query.class), eq(AdminUser.class))).thenReturn(true);

        var user = service.authenticate("USER@EXAMPLE.COM", "a-secure-password");

        assertThat(user.roles()).containsExactly("USER", "ADMIN");
        assertThat(user.uid()).isEqualTo(1_000_001L);
    }

    @Test
    void loginAcceptsTheUidAsIdentity() {
        var account = verifiedAccount(passwords.encode("a-secure-password"));
        // The email lookup misses, the uid lookup hits.
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(null, account);

        var user = service.authenticate("1000001", "a-secure-password");

        assertThat(user.uid()).isEqualTo(1_000_001L);
        verify(mongo, times(2)).findOne(any(Query.class), eq(UserAccount.class));
    }

    @Test
    void loginRejectsAmbiguousUsernamesBecauseTheyAreNotUnique() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(null);

        assertThatThrownBy(() -> service.authenticate("member", "a-secure-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }

    @Test
    void loginDoesNotRevealWhetherAccountOrPasswordWasWrong() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("right-password")));
        assertThatThrownBy(() -> service.authenticate("user@example.com", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(null);
        assertThatThrownBy(() -> service.authenticate("missing@example.com", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }

    @Test
    void loginOnAnUnverifiedAccountAsksForTheEmailedCode() {
        var account = unverifiedAccount(passwords.encode("a-secure-password"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account);
        when(verificationCodes.issue(anyString(), anyString())).thenReturn("135790");

        assertThatThrownBy(() -> service.authenticate("user@example.com", "a-secure-password"))
                .isInstanceOf(AuthService.EmailNotVerifiedException.class)
                .hasMessageContaining("邮箱尚未验证");

        // A fresh code is sent so the caller can prompt for it straight away.
        verify(mail).sendVerificationCode(eq("user@example.com"), eq("member"), eq("135790"), anyLong());
    }

    @Test
    void verifyingTheCodeMarksTheAccountVerified() {
        var account = unverifiedAccount(passwords.encode("a-secure-password"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account);

        var pending = unverifiedAccount(passwords.encode("a-secure-password"));
        var updated = new UserAccount(
                pending.id(), pending.uid(), pending.email(), pending.emailKey(), pending.username(),
                 pending.usernameKey(), pending.passwordHash(), pending.avatar(), pending.createdAt(),
                 pending.updatedAt(), pending.enabled(), true, pending.sessionVersion());
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class)))
                .thenReturn(updated);

        service.verifyEmail("user@example.com", "123456");

        verify(verificationCodes).verify("user@example.com", "123456");
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(), eq(UserAccount.class));
        assertThat(update.getValue().getUpdateObject().toString()).contains("emailVerified");
    }

    @Test
    void resendingACodeSkipsUnknownAndAlreadyVerifiedAccounts() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(null);
        service.sendVerificationCodeFor("nobody@example.com");
        verifyNoInteractions(mail);

        var verified = verifiedAccount(passwords.encode("pw"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(verified);
        service.sendVerificationCodeFor("user@example.com");
        verifyNoInteractions(mail);
    }

    @Test
    void usernameUpdateStoresTheNewDisplayValue() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(verifiedAccount(passwords.encode("pw")));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("pw")));

        service.updateUsername(1_000_001L, " 改名用户 ");

        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(), eq(UserAccount.class));
        assertThat(update.getValue().getUpdateObject().toString()).contains("改名用户");
    }

    @Test
    void avatarUpdateCanClearTheStoredImage() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(verifiedAccount(passwords.encode("pw")));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("pw")));

        service.updateAvatar(1_000_001L, "   ");

        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(), eq(UserAccount.class));
        assertThat(update.getValue().getUpdateObject().toString()).contains("avatar");
    }

    @Test
    void passwordChangeRequiresTheCurrentPassword() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("right-password")));

        assertThatThrownBy(() -> service.updatePassword(1_000_001L, "wrong-password", "a-new-password"))
                .isInstanceOf(AuthService.InvalidAccountException.class);

        assertThatThrownBy(() -> service.updatePassword(1_000_001L, "right-password", "short"))
                .isInstanceOf(AuthService.InvalidAccountException.class);
    }

    @Test
    void emailChangeRejectsAnAddressOwnedByAnotherAccount() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("right-password")));
        when(mongo.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);

        assertThatThrownBy(() -> service.requestEmailChange(1_000_001L, "right-password", "taken@example.com"))
                .isInstanceOf(AuthService.DuplicateAccountException.class);
    }

    @Test
    void emailChangeLeavesTheAccountUntouchedUntilTheNewAddressIsVerified() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("right-password")));
        when(verificationCodes.issue("email-change:1000001", "new@example.com")).thenReturn("123456");
        when(verificationCodes.ttlSeconds()).thenReturn(600L);

        var change = service.requestEmailChange(
                1_000_001L, "right-password", " New@Example.com ");

        assertThat(change.user()).isNull();
        assertThat(change.maskedEmail()).endsWith("@example.com");
        verify(mail).sendVerificationCode("new@example.com", "member", "123456", 600L);
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class));
    }

    @Test
    void verifiedEmailChangeUpdatesTheAddressAndKeepsItVerified() {
        Instant now = Instant.parse("2026-09-12T08:00:00Z");
        var pending = new EmailVerification(
                "email-change:1000001", "new@example.com", "hash", now, now.plusSeconds(600),
                now, 1, now, 1);
        when(verificationCodes.verify("email-change:1000001", "123456")).thenReturn(pending);
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("right-password")));
        var changed = verifiedAccount(passwords.encode("right-password"));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class)))
                .thenReturn(changed);

        service.verifyEmailChange(1_000_001L, "123456");

        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(), eq(UserAccount.class));
        assertThat(update.getValue().getUpdateObject().toString())
                .contains("new@example.com")
                .contains("emailVerified");
    }

    @Test
    void passwordChangeIncrementsTheSessionVersion() {
        var account = verifiedAccount(passwords.encode("right-password"));
        when(mongo.findOne(any(Query.class), eq(UserAccount.class))).thenReturn(account);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(UserAccount.class)))
                .thenReturn(account);

        service.updatePassword(1_000_001L, "right-password", "a-new-password");

        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(), eq(UserAccount.class));
        assertThat(update.getValue().getUpdateObject().toString()).contains("sessionVersion");
    }

    @Test
    void sessionValidationRequiresTheStoredVersionAndAnEnabledAccount() {
        when(mongo.findOne(any(Query.class), eq(UserAccount.class)))
                .thenReturn(verifiedAccount(passwords.encode("pw")), null);

        assertThat(service.isSessionValid(1_000_001L, 0)).isTrue();
        assertThat(service.isSessionValid(1_000_001L, 0)).isFalse();
    }

    @Test
    void avatarValidationRejectsNonImagePayloads() {
        assertThatThrownBy(() -> AuthService.normalizeAvatar("javascript:alert(1)"))
                .isInstanceOf(AuthService.InvalidAccountException.class);
        assertThatThrownBy(() -> AuthService.normalizeAvatar("data:image/png;base64," + "A".repeat(400_000)))
                .isInstanceOf(AuthService.InvalidAccountException.class);
        assertThat(AuthService.normalizeAvatar(null)).isNull();
    }

    @Test
    void maskedEmailKeepsTheDomainButHidesTheLocalPart() {
        String masked = AuthService.maskEmail("lanterns1020@gmail.com");
        assertThat(masked).endsWith("@gmail.com").startsWith("la").contains("*");
        // The local part must not survive in clear text.
        assertThat(masked).doesNotContain("nterns1020");

        // Short addresses still hide something and keep a leading character.
        assertThat(AuthService.maskEmail("ab@x.com")).endsWith("@x.com").contains("***");
        assertThat(AuthService.maskEmail("")).isEmpty();
    }

    private UserAccount verifiedAccount(String passwordHash) {
        return account(passwordHash, true);
    }

    private UserAccount unverifiedAccount(String passwordHash) {
        return account(passwordHash, false);
    }

    private UserAccount account(String passwordHash, boolean emailVerified) {
        Instant now = Instant.parse("2026-09-12T08:00:00Z");
        return new UserAccount("mongo-id", 1_000_001L, "user@example.com", "user@example.com", "member", "member",
                passwordHash, AVATAR, now, now, true, emailVerified, 0);
    }
}
