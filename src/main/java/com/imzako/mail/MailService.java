package com.imzako.mail;

import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String SEND_PATH = "/api/batch_mail/api/send";

    private final ObjectProvider<JavaMailSender> smtp;
    private final MailProperties properties;
    private final RestClient billionMail;
    private final VerificationMailTemplate template;

    public MailService(
            ObjectProvider<JavaMailSender> smtp,
            MailProperties properties,
            RestClient billionMailRestClient,
            VerificationMailTemplate template) {
        this.smtp = smtp;
        this.properties = properties;
        this.billionMail = billionMailRestClient;
        this.template = template;
    }

    /** True when the selected transport and sender identity are configured. */
    public boolean isConfigured() {
        if (properties.sender() == null || properties.sender().isBlank()) {
            return false;
        }
        return !properties.usesBillionMail() || properties.hasBillionMailCredentials();
    }

    public void sendVerificationCode(String recipient, String username, String code, long ttlSeconds) {
        long minutes = Math.max(1, (ttlSeconds + 59) / 60);
        try {
            if (properties.usesBillionMail()) {
                sendWithBillionMail(recipient, username, code, minutes);
            } else {
                sendWithSmtp(recipient, username, code, minutes);
            }
        } catch (MailDeliveryException failure) {
            throw failure;
        } catch (MailException | MessagingException | UnsupportedEncodingException | RestClientException failure) {
            log.warn("Could not send a verification email to {}: {}", recipient, failure.getMessage());
            throw new MailDeliveryException("验证码邮件发送失败，请稍后重试", failure);
        }
    }

    private void sendWithBillionMail(String recipient, String username, String code, long minutes) {
        if (!properties.hasBillionMailCredentials()) {
            throw new MailDeliveryException("BillionMail 发信配置不完整", null);
        }

        var request = new BillionMailSendRequest(
                properties.sender(),
                recipient,
                Map.of(
                        "username", template.htmlSafeDisplayName(username),
                        "code", code,
                        "minutes", Long.toString(minutes),
                        "site_url", properties.siteUrl()));

        BillionMailResponse response = billionMail.post()
                .uri(SEND_PATH)
                .header("x-api-key", properties.billionMailApiKey())
                .body(request)
                .retrieve()
                .body(BillionMailResponse.class);

        if (response == null || !response.success() || response.code() != 0) {
            int responseCode = response == null ? -1 : response.code();
            log.warn("BillionMail rejected a verification email to {} (code {})", recipient, responseCode);
            throw new MailDeliveryException("验证码邮件发送失败，请稍后重试", null);
        }
    }

    private void sendWithSmtp(String recipient, String username, String code, long minutes)
            throws MessagingException, UnsupportedEncodingException {
        JavaMailSender sender = smtp.getIfAvailable();
        if (sender == null) {
            throw new MailDeliveryException("SMTP 未配置", null);
        }
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(properties.sender(), properties.senderName());
        helper.setTo(recipient);
        helper.setSubject(properties.senderName() + " 账户验证码");
        helper.setText(
                template.renderPlainText(username, code, minutes, properties.senderName()),
                template.renderHtml(username, code, minutes, properties.siteUrl()));
        sender.send(message);
    }

    record BillionMailSendRequest(String addresser, String recipient, Map<String, String> attribs) {}

    record BillionMailResponse(boolean success, int code, String msg) {}

    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
