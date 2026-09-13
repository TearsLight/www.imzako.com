package com.imzako.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mail;
    private final MailProperties properties;

    public MailService(JavaMailSender mail, MailProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    /** True when a sender identity is configured, so callers can fail fast with a useful message. */
    public boolean isConfigured() {
        return properties.sender() != null && !properties.sender().isBlank();
    }

    /**
     * Sends the account verification code. The code sits on its own line so it stays
     * readable in clients that collapse whitespace.
     */
    public void sendVerificationCode(String recipient, String username, String code, long ttlSeconds) {
        long minutes = Math.max(1, ttlSeconds / 60);
        String greeting = username == null || username.isBlank() ? "你好" : username;
        String body = """
                %s，你好：

                你的账号验证码是：

                %s

                请在 %d 分钟内完成验证。若非本人操作，请忽略本邮件。

                -- %s
                """.formatted(greeting, code, minutes, properties.senderName());

        var message = new SimpleMailMessage();
        message.setFrom(properties.sender());
        message.setTo(recipient);
        message.setSubject(properties.senderName() + " 账号验证码");
        message.setText(body);
        try {
            mail.send(message);
        } catch (MailException failure) {
            log.warn("Could not send the verification code to {}: {}", recipient, failure.getMessage());
            throw new MailDeliveryException("验证码邮件发送失败，请稍后重试", failure);
        }
    }

    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) { super(message, cause); }
    }
}
