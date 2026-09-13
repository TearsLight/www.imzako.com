package com.imzako.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MailConfiguration {
    @Bean
    MailProperties mailProperties(
            @Value("${app.mail.sender}") String sender,
            @Value("${app.mail.sender-name}") String senderName) {
        return new MailProperties(sender, senderName);
    }
}
