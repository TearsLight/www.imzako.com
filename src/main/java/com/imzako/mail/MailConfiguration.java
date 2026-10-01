package com.imzako.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MailConfiguration {
    @Bean
    MailProperties mailProperties(
            @Value("${app.mail.transport}") String transport,
            @Value("${app.mail.sender}") String sender,
            @Value("${app.mail.sender-name}") String senderName,
            @Value("${app.mail.billionmail.base-url}") String billionMailBaseUrl,
            @Value("${app.mail.billionmail.api-key}") String billionMailApiKey,
            @Value("${app.public-url}") String siteUrl,
            @Value("${app.mail.billionmail.timeout}") Duration timeout) {
        return new MailProperties(
                transport,
                sender,
                senderName,
                billionMailBaseUrl,
                billionMailApiKey,
                siteUrl,
                timeout);
    }

    @Bean
    RestClient billionMailRestClient(MailProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.billionMailBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("/+$", "");
    }
}
