package com.imzako.auth;

/** Raised when a sign-in attempt must first solve a captcha. */
public class CaptchaRequiredException extends RuntimeException {
    public CaptchaRequiredException() {
        super("需要输入图形验证码");
    }
}
