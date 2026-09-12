package com.imzako.auth;

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
