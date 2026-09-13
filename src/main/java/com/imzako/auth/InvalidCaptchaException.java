package com.imzako.auth;

/** Raised when a submitted captcha id/answer pair is unknown, reused or wrong. */
public class InvalidCaptchaException extends RuntimeException {
    public InvalidCaptchaException() {
        super("图形验证码不正确或已过期");
    }
}
