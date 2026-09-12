package com.imzako.blog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.mongodb.initialize=false")
class BlogLiveTest {
    @TempDir static Path directory;
    @Value("${local.server.port}") int port;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("BLOG_DIRECTORY", () -> directory.toString());
    }

    @Test
    void realHttpAndSseReflectFileChangesAndResynchronizeAfterReconnect() throws Exception {
        String initialRevision;
        Path file = directory.resolve("live.md");
        try (var events = connect()) {
            assertThat(events.await("sync")).contains("retry:2000");
            initialRevision = getJson("/api/blog/posts").get("revision").asString();
            Files.writeString(file, "# Live article\n\nOriginal **Markdown** content.");
            assertThat(events.await("changed")).doesNotContain(initialRevision);
            assertThat(getJson("/api/blog/posts?limit=10").get("posts").get(0).get("title").asString()).isEqualTo("Live article");
            assertThat(getJson("/api/blog/post?slug=live").get("markdown").asString()).contains("Original **Markdown**");

            // Simulate an editor saving via a temporary file and replacement.
            Path saved = Files.writeString(directory.resolve("live.tmp"), "# Updated title\n\nSaved while the page stays open.");
            Files.move(saved, file, StandardCopyOption.REPLACE_EXISTING);
            events.await("changed");
            assertThat(getJson("/api/blog/post?slug=live").get("markdown").asString()).contains("Saved while");
            initialRevision = getJson("/api/blog/posts").get("revision").asString();
        }

        Files.writeString(file, "# Offline edit\n\nCatch up after reconnect.");
        String previousRevision = initialRevision;
        MarkdownBlogTest.await(() -> {
            try { return !getJson("/api/blog/posts").get("revision").asString().equals(previousRevision); }
            catch (Exception e) { return false; }
        });
        try (var reconnected = connect()) {
            String currentRevision = getJson("/api/blog/posts").get("revision").asString();
            assertThat(reconnected.await("sync")).contains(currentRevision).doesNotContain(previousRevision);
            assertThat(getJson("/api/blog/post?slug=live").get("post").get("title").asString()).isEqualTo("Offline edit");

            Path renamed = Files.move(file, directory.resolve("renamed.md"));
            reconnected.await("changed");
            assertThat(get("/api/blog/post?slug=live").statusCode()).isEqualTo(404);
            assertThat(get("/api/blog/post?slug=renamed").statusCode()).isEqualTo(200);
            assertThat(get("/blog/posts/renamed").body()).contains("<div id=\"app\"></div>");
            Files.delete(renamed);
            reconnected.await("changed");
            assertThat(getJson("/api/blog/posts").get("posts").size()).isZero();
            assertThat(get("/api/blog/post?slug=renamed").statusCode()).isEqualTo(404);
        }

        Files.write(directory.resolve("photo.png"), new byte[] {(byte) 137, 80, 78, 71});
        assertThat(get("/api/blog/assets?path=photo.png").headers().firstValue("Content-Type")).contains("image/png");
        assertThat(get("/api/blog/assets?path=../private.png").statusCode()).isEqualTo(404);
        assertThat(get("/api/blog/assets?path=live.md").statusCode()).isEqualTo(404);
        assertThat(get("/api/blog/posts").headers().firstValue("Cache-Control")).contains("no-store");
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build();
    }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode getJson(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private Events connect() throws Exception {
        var response = client.send(request("/api/blog/events"), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        return new Events(response.body());
    }
    private static class Events implements AutoCloseable {
        final InputStream stream;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Events(InputStream stream) {
            this.stream = stream;
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder event = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) { queue.add(event.toString()); event.setLength(0); }
                        else event.append(line).append('\n');
                    }
                } catch (IOException ignored) { /* Closing the test connection ends the reader. */ }
            });
        }
        String await(String name) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline) {
                String event = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertThat(event).as("SSE should send " + name).isNotNull();
                if (event.contains("event:" + name + "\n")) return event;
            }
            throw new AssertionError("No SSE event: " + name);
        }
        public void close() throws IOException { stream.close(); }
    }
}
