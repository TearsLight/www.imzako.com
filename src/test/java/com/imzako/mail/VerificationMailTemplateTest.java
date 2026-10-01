package com.imzako.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerificationMailTemplateTest {
    private final VerificationMailTemplate template = new VerificationMailTemplate();

    @Test
    void rendersCodeAndEscapesUserControlledHtml() {
        String html = template.renderHtml(
                "<script>alert(1)</script>", "123456", 10, "https://www.imzako.com");

        assertThat(html)
                .contains("123456", "10 分钟", "&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("{{.API.code}}", "<script>alert(1)</script>");
    }
}
