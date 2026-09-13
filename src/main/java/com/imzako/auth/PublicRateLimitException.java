package com.imzako.auth;

/** Raised when an unauthenticated client exceeds an expensive endpoint limit. */
public class PublicRateLimitException extends RuntimeException {
    public PublicRateLimitException() {
        super("请求过于频繁，请稍后再试");
    }
}
