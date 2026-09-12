package com.imzako.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String SESSION_USER = "authenticatedUser";
    private final AuthService auth;
    private final int sessionTtl;

    public AuthController(AuthService auth, @Value("${SESSION_TTL_SECONDS:86400}") int sessionTtl) {
        this.auth = auth;
        this.sessionTtl = Math.max(60, sessionTtl);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        var user = auth.register(request.email(), request.username(), request.password(), request.gender(), request.birthday());
        establishSession(http, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(UserView.from(user)));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        var user = auth.login(request.username(), request.password());
        establishSession(http, user);
        return new AuthResponse(UserView.from(user));
    }

    @GetMapping("/me")
    public AuthResponse me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        if (!(user instanceof UserView view)) throw new NotAuthenticatedException();
        return new AuthResponse(view);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    private void establishSession(HttpServletRequest request, AuthService.AuthenticatedUser user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(sessionTtl);
        session.setAttribute(SESSION_USER, UserView.from(user));
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String username,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[\\p{L}\\p{N}_.-]+$", message = "用户名只能包含文字、数字、点、横线和下划线") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 20) String gender,
            @Past LocalDate birthday
    ) {}

    public record AuthResponse(UserView user) {}

    public record UserView(
            long id,
            String username,
            String displayName,
            String email,
            String gender,
            LocalDate birthday,
            List<String> roles
    ) implements java.io.Serializable {
        static UserView from(AuthService.AuthenticatedUser user) {
            return new UserView(user.uid(), user.username(), user.username(), user.email(), user.gender(), user.birthday(), user.roles());
        }
    }

    public static class NotAuthenticatedException extends RuntimeException {}
}
