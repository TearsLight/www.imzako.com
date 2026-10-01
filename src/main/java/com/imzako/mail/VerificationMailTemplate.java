package com.imzako.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class VerificationMailTemplate {
    private static final String DEFAULT_DISPLAY_NAME = "你好";

    private final String html;

    public VerificationMailTemplate() {
        try {
            html = new ClassPathResource("mail/verification-code.html")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取验证码邮件模板", failure);
        }
    }

    String displayName(String username) {
        return username == null || username.isBlank() ? DEFAULT_DISPLAY_NAME : username.strip();
    }

    String htmlSafeDisplayName(String username) {
        return HtmlUtils.htmlEscape(displayName(username));
    }

    String renderHtml(String username, String code, long minutes, String siteUrl) {
        return html
                .replace("{{.API.username}}", htmlSafeDisplayName(username))
                .replace("{{.API.code}}", HtmlUtils.htmlEscape(code))
                .replace("{{.API.minutes}}", Long.toString(minutes))
                .replace("{{.API.site_url}}", HtmlUtils.htmlEscape(siteUrl));
    }

    String renderPlainText(String username, String code, long minutes, String senderName) {
        return """
                %s：

                你的账户验证码是：%s

                验证码将在 %d 分钟后失效。若非本人操作，请忽略本邮件。

                %s
                """.formatted(displayName(username), code, minutes, senderName);
    }
}
