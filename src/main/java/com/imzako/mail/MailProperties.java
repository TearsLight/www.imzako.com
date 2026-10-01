package com.imzako.mail;

import java.time.Duration;

/** Outgoing mail configuration resolved from {@code .env}. */
public record MailProperties(
        String transport,
        String sender,
        String senderName,
        String billionMailBaseUrl,
        String billionMailApiKey,
        String siteUrl,
        Duration timeout) {

    public boolean usesBillionMail() {
        return "billionmail".equalsIgnoreCase(transport);
    }

    public boolean hasBillionMailCredentials() {
        return billionMailBaseUrl != null
                && !billionMailBaseUrl.isBlank()
                && billionMailApiKey != null
                && !billionMailApiKey.isBlank();
    }
}
