package com.imzako.mail;

/**
 * Outgoing mail configuration resolved from {@code .env}. Kept as a record so the
 * sender identity is injectable in tests without touching Spring's mail autoconfiguration.
 */
public record MailProperties(String sender, String senderName) {}
