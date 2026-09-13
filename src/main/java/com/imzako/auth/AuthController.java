package com.imzako.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String SESSION_USER = "authenticatedUser";
    private static final String SESSION_VERSION = "authenticatedUserVersion";

    private final AuthService auth;
    private final CaptchaService captchas;
    private final LoginGuard loginGuard;
    private final PublicRequestGuard publicGuard;
    private final int sessionTtl;
    private final boolean captchaEnabled;
    private final boolean trustProxy;

    public AuthController(
            AuthService auth,
            CaptchaService captchas,
            LoginGuard loginGuard,
            PublicRequestGuard publicGuard,
            @Value("${SESSION_TTL_SECONDS:86400}") int sessionTtl,
            @Value("${app.captcha.enabled:false}") boolean captchaEnabled,
            @Value("${TRUST_PROXY:false}") boolean trustProxy) {
        this.auth = auth;
        this.captchas = captchas;
        this.loginGuard = loginGuard;
        this.publicGuard = publicGuard;
        this.sessionTtl = Math.max(60, sessionTtl);
        this.captchaEnabled = captchaEnabled;
        this.trustProxy = trustProxy;
    }

    /**
     * Creates the account. Without the email gate the caller is signed in right
     * away; with it enabled the response asks for the emailed code instead.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        publicGuard.registration(clientAddress(http));
        var user = auth.register(request.email(), request.username(), request.password(), request.avatar());
        if (!auth.isEmailVerificationEnabled()) {
            establishSession(http, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.signedIn(user));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.verificationRequired(user));
    }

    /** Confirms the emailed code and signs the account in. */
    @PostMapping("/register/verify")
    public AuthResponse verifyRegistration(
            @Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        var user = auth.verifyEmail(request.identity(), request.code());
        establishSession(http, user);
        return AuthResponse.signedIn(user);
    }

    /** Re-sends the code for an account whose address is not confirmed yet. */
    @PostMapping("/register/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(
            @Valid @RequestBody IdentityRequest request, HttpServletRequest http) {
        publicGuard.resend(clientAddress(http));
        auth.sendVerificationCodeFor(request.identity());
    }

    /**
     * Signs in with the email or UID. When the email gate is on, an unconfirmed
     * address is answered with the verification prompt instead of a session. When the
     * captcha gate is on, repeated failures must be answered with a captcha first.
     */
    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String address = clientAddress(http);
        if (captchaEnabled && loginGuard.captchaRequired(request.identity(), address)) {
            if (isBlank(request.captchaId()) || isBlank(request.captchaAnswer())) {
                throw new CaptchaRequiredException();
            }
            if (!captchas.consume(request.captchaId(), request.captchaAnswer())) {
                loginGuard.recordFailure(request.identity(), address);
                throw new InvalidCaptchaException();
            }
        }

        AuthService.AuthenticatedUser user;
        try {
            user = auth.authenticate(request.identity(), request.password());
        } catch (AuthService.EmailNotVerifiedException pending) {
            // The password was correct, so this is not a failed attempt.
            loginGuard.recordSuccess(request.identity(), address);
            return AuthResponse.verificationRequired(pending.maskedEmail());
        } catch (AuthService.InvalidCredentialsException rejected) {
            loginGuard.recordFailure(request.identity(), address);
            throw rejected;
        }

        loginGuard.recordSuccess(request.identity(), address);
        establishSession(http, user);
        return AuthResponse.signedIn(user);
    }

    /** Issues a fresh captcha image. The answer never leaves the server. */
    @GetMapping(value = "/captcha", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> captcha(HttpServletRequest request) throws IOException {
        publicGuard.captcha(clientAddress(request));
        CaptchaService.Challenge challenge = captchas.create();
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(challenge.image(), "png", buffer);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Captcha-Id", challenge.id())
                .header("X-Captcha-Threshold", String.valueOf(loginGuard.threshold()))
                .contentType(MediaType.IMAGE_PNG)
                .body(buffer.toByteArray());
    }

    @GetMapping("/me")
    public AuthResponse me(HttpServletRequest request) {
        return AuthResponse.signedIn(currentUser(request));
    }

    /** Renames the account. Usernames are not unique, so this never returns 409. */
    @PutMapping("/username")
    public AuthResponse updateUsername(@Valid @RequestBody UsernameRequest body, HttpServletRequest request) {
        long uid = currentUser(request).id();
        return signedIn(request, auth.updateUsername(uid, body.username()));
    }

    /** Replaces or clears the profile image. */
    @PutMapping("/avatar")
    public AuthResponse updateAvatar(@RequestBody AvatarRequest body, HttpServletRequest request) {
        long uid = currentUser(request).id();
        return signedIn(request, auth.updateAvatar(uid, body.avatar()));
    }

    /** Rebinds the login email. Requires the current password. */
    @PutMapping("/email")
    public AuthResponse updateEmail(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        long uid = currentUser(request).id();
        var change = auth.requestEmailChange(uid, body.currentPassword(), body.email());
        if (change.user() != null) return signedIn(request, change.user());
        return AuthResponse.verificationRequired(change.maskedEmail());
    }

    /** Confirms ownership of a pending replacement email address. */
    @PostMapping("/email/verify")
    public AuthResponse verifyEmailChange(
            @Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        long uid = currentUser(request).id();
        return signedIn(request, auth.verifyEmailChange(uid, body.code()));
    }

    /**
     * Changes the password and invalidates the session, so the browser has to sign
     * in again with the new credentials.
     */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updatePassword(@Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        UserView current = currentUser(request);
        auth.updatePassword(current.id(), body.currentPassword(), body.newPassword());
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    /**
     * Client address used for captcha throttling. {@code server.forward-headers-strategy}
     * is not enabled, so forwarded headers are only trusted when explicitly configured.
     */
    private String clientAddress(HttpServletRequest request) {
        String forwarded = trustProxy ? request.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private UserView currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        Object version = session == null ? null : session.getAttribute(SESSION_VERSION);
        if (!(user instanceof UserView view)
                || !(version instanceof Long sessionVersion)
                || !auth.isSessionValid(view.id(), sessionVersion)) {
            if (session != null) session.invalidate();
            throw new NotAuthenticatedException();
        }
        return view;
    }

    private void establishSession(HttpServletRequest request, AuthService.AuthenticatedUser user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(sessionTtl);
        session.setAttribute(SESSION_USER, UserView.from(user));
        session.setAttribute(SESSION_VERSION, user.sessionVersion());
    }

    /** Stores the updated user in the session so later reads and the header stay current. */
    private AuthResponse signedIn(HttpServletRequest request, AuthService.AuthenticatedUser user) {
        UserView view = UserView.from(user);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_USER, view);
            session.setAttribute(SESSION_VERSION, user.sessionVersion());
        }
        return AuthResponse.signedIn(view);
    }

    /**
     * {@code identity} accepts the account email or the numeric UID. Usernames are
     * not unique and therefore cannot identify an account. The password is only
     * checked against the stored hash, so no length rule belongs here.
     */
    public record LoginRequest(
            @NotBlank @Size(max = 254) String identity,
            @NotBlank @Size(max = 200) String password,
            @Size(max = 64) String captchaId,
            @Size(max = 16) String captchaAnswer
    ) {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[\\p{L}\\p{N}_.-]+$", message = "用户名只能包含文字、数字、点、横线和下划线") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 300_000) String avatar
    ) {}

    public record VerifyRequest(
            @NotBlank @Size(max = 254) String identity,
            @NotBlank @Size(max = 12) String code
    ) {}

    public record IdentityRequest(@NotBlank @Size(max = 254) String identity) {}

    public record UsernameRequest(
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[\\p{L}\\p{N}_.-]+$", message = "用户名只能包含文字、数字、点、横线和下划线") String username
    ) {}

    public record AvatarRequest(@Size(max = 300_000) String avatar) {}

    public record EmailRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Email @Size(max = 254) String email
    ) {}

    public record CodeRequest(@NotBlank @Size(max = 12) String code) {}

    public record PasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    /**
     * {@code user} is present only when signed in. Otherwise {@code verificationRequired}
     * is true and {@code maskedEmail} tells the caller where the code was sent.
     */
    public record AuthResponse(UserView user, boolean verificationRequired, String maskedEmail) {
        static AuthResponse signedIn(AuthService.AuthenticatedUser user) {
            return signedIn(UserView.from(user));
        }

        static AuthResponse signedIn(UserView user) {
            return new AuthResponse(user, false, null);
        }

        static AuthResponse verificationRequired(AuthService.AuthenticatedUser user) {
            return verificationRequired(AuthService.maskEmail(user.email()));
        }

        static AuthResponse verificationRequired(String maskedEmail) {
            return new AuthResponse(null, true, maskedEmail);
        }
    }

    public record UserView(
            long id,
            String username,
            String displayName,
            String email,
            String avatar,
            List<String> roles
    ) implements java.io.Serializable {
        static UserView from(AuthService.AuthenticatedUser user) {
            return new UserView(
                    user.uid(), user.username(), user.username(), user.email(), user.avatar(), user.roles());
        }
    }

    public static class NotAuthenticatedException extends RuntimeException {}
}
