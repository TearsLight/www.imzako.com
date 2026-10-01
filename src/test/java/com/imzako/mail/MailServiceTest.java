package com.imzako.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MailServiceTest {
    private MockRestServiceServer server;
    private MailService mail;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://mail.example");
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new MailProperties(
                "billionmail",
                "noreply@imzako.com",
                "imzako",
                "https://mail.example",
                "send-api-key",
                "https://www.imzako.com",
                Duration.ofSeconds(10));
        mail = new MailService(
                mock(JavaMailSender.class),
                properties,
                builder.build(),
                new VerificationMailTemplate());
    }

    @Test
    void sendsVerificationAttributesThroughBillionMailApi() {
        server.expect(requestTo("https://mail.example/api/batch_mail/api/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "send-api-key"))
                .andExpect(content().json("""
                        {
                          "addresser": "noreply@imzako.com",
                          "recipient": "member@example.com",
                          "attribs": {
                            "username": "新用户",
                            "code": "135790",
                            "minutes": "2",
                            "site_url": "https://www.imzako.com"
                          }
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"success\":true,\"code\":0,\"msg\":\"queued\"}",
                        MediaType.APPLICATION_JSON));

        mail.sendVerificationCode("member@example.com", "新用户", "135790", 61);

        server.verify();
    }

    @Test
    void treatsLogicalApiFailureAsDeliveryFailure() {
        server.expect(requestTo("https://mail.example/api/batch_mail/api/send"))
                .andRespond(withSuccess(
                        "{\"success\":false,\"code\":1001,\"msg\":\"invalid key\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> mail.sendVerificationCode(
                        "member@example.com", "member", "135790", 600))
                .isInstanceOf(MailService.MailDeliveryException.class)
                .hasMessage("验证码邮件发送失败，请稍后重试");
    }
}
