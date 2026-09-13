package com.imzako.auth;

import com.imzako.mail.MailService;
import com.imzako.mail.VerificationCodeService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidRequest(MethodArgumentNotValidException error) {
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "请检查注册或登录信息", fields));
    }

    @ExceptionHandler(AuthService.InvalidAccountException.class)
    ResponseEntity<ApiError> invalidAccount(AuthService.InvalidAccountException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ACCOUNT", error.getMessage(), Map.of()));
    }

    /** 428 tells the client to fetch /api/auth/captcha and retry with an answer. */
    @ExceptionHandler(CaptchaRequiredException.class)
    ResponseEntity<ApiError> captchaRequired(CaptchaRequiredException error) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(new ApiError("CAPTCHA_REQUIRED", error.getMessage(), Map.of()));
    }

    @ExceptionHandler(InvalidCaptchaException.class)
    ResponseEntity<ApiError> invalidCaptcha(InvalidCaptchaException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_CAPTCHA", error.getMessage(), Map.of()));
    }

    @ExceptionHandler(PublicRateLimitException.class)
    ResponseEntity<ApiError> publicRateLimited(PublicRateLimitException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("RATE_LIMITED", error.getMessage(), Map.of()));
    }

    /** A correct password on an unverified account: report which address needs confirming. */
    @ExceptionHandler(AuthService.EmailNotVerifiedException.class)
    ResponseEntity<ApiError> emailNotVerified(AuthService.EmailNotVerifiedException error) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(new ApiError("EMAIL_NOT_VERIFIED", error.getMessage(), Map.of("maskedEmail", error.maskedEmail())));
    }

    @ExceptionHandler(VerificationCodeService.VerificationException.class)
    ResponseEntity<ApiError> verification(VerificationCodeService.VerificationException error) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VERIFICATION_" + error.reason().name(), error.getMessage(), Map.of()));
    }

    @ExceptionHandler(MailService.MailDeliveryException.class)
    ResponseEntity<ApiError> mailFailed(MailService.MailDeliveryException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("MAIL_UNAVAILABLE", error.getMessage(), Map.of()));
    }

    @ExceptionHandler(AuthService.DuplicateAccountException.class)
    ResponseEntity<ApiError> duplicate(AuthService.DuplicateAccountException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("ACCOUNT_EXISTS", error.getMessage(), Map.of()));
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("INVALID_CREDENTIALS", "账号或密码不正确", Map.of()));
    }

    @ExceptionHandler(AuthController.NotAuthenticatedException.class)
    ResponseEntity<ApiError> notAuthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("NOT_AUTHENTICATED", "尚未登录", Map.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("DATABASE_UNAVAILABLE", "账号服务暂时不可用", Map.of()));
    }

    public record ApiError(String code, String message, Map<String, String> fields) {}
}
