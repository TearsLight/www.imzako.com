package com.imzako;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebRoutesTest {
    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"/", "/blog", "/blog/", "/login", "/login/", "/about", "/about/"})
    void pageRoutesServeTheBuiltVueApp(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("<div id=\"app\"></div>", "/assets/");
    }

    @Test
    void builtScriptIsServedAsJavascript() throws Exception {
        var matcher = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(get("/").body());
        assertThat(matcher.find()).as("the Vue entry script must be present").isTrue();
        var response = get(matcher.group(1));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("javascript");
        assertThat(response.body()).doesNotContain("<!DOCTYPE html>");
    }

    @Test
    void healthEndpointReturnsJson() throws Exception {
        var response = get("/api/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"status\":\"UP\"", "\"application\":\"www.imzako.com\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/missing", "/assets/missing.js", "/missing"})
    void missingResourcesDoNotReturnTheVueApp(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("<div id=\"app\"></div>");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
