package com.imzako.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends one real message through the configured SMTP server. Only runs when
 * IMAIL_SMTP_PROBE=true so ordinary test runs never touch the network.
 */
@SpringBootTest(properties = "app.mongodb.initialize=false")
@EnabledIfEnvironmentVariable(named = "IMAIL_SMTP_PROBE", matches = "true")
class SmtpProbeTest {
    @Autowired
    JavaMailSender mail;

    /** Read from Spring config so .env values are visible even without shell exports. */
    @Value("${app.mail.sender}")
    String sender;

    @Test
    void sendsARealMessage() {
        var message = new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(sender);
        message.setSubject("imzako smtp probe");
        message.setText("SMTP connectivity probe from the imzako test suite.");
        mail.send(message);
    }
}
