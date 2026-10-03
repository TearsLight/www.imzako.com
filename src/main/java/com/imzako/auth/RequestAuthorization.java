package com.imzako.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Resolves a fresh database-backed identity for privileged HTTP endpoints. */
@Service
public class RequestAuthorization {
    private final AuthService auth;
    private final UserAccessService access;

    public RequestAuthorization(AuthService auth, UserAccessService access) {
        this.auth = auth;
        this.access = access;
    }

    public AuthService.AuthenticatedUser requirePermission(HttpServletRequest request, int minimum) {
        HttpSession session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(AuthController.SESSION_USER);
        Object version = session == null ? null : session.getAttribute(AuthController.SESSION_VERSION);
        if (!(stored instanceof AuthController.UserView view)
                || !(version instanceof Long sessionVersion)
                || !auth.isSessionValid(view.id(), sessionVersion)) {
            if (session != null) session.invalidate();
            throw new ResponseStatusException(UNAUTHORIZED, "尚未登录");
        }
        AuthService.AuthenticatedUser fresh = auth.profile(view.id());
        session.setAttribute(AuthController.SESSION_USER, AuthController.UserView.from(fresh));
        if (fresh.permissionLevel() < minimum) {
            throw new ResponseStatusException(FORBIDDEN, "权限不足");
        }
        return fresh;
    }

    public AuthService.AuthenticatedUser requireWritablePermission(HttpServletRequest request, int minimum) {
        AuthService.AuthenticatedUser user = requirePermission(request, minimum);
        if (access.activeBan(user.uid()) != null) {
            throw new ResponseStatusException(FORBIDDEN, "账号处于封禁期，仅有只读权限");
        }
        return user;
    }
}
