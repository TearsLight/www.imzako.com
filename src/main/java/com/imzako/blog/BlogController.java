package com.imzako.blog;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/blog")
public class BlogController {
    private final MarkdownBlog blog;
    private final Set<SseEmitter> clients = new CopyOnWriteArraySet<>();
    private final AutoCloseable subscription;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "blog-events"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, String> IMAGE_TYPES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp", "avif", "image/avif");

    public BlogController(MarkdownBlog blog) {
        this.blog = blog;
        subscription = blog.listen(() -> clients.forEach(client -> send(client, "changed")));
        heartbeat.scheduleWithFixedDelay(() -> clients.forEach(client -> send(client, "heartbeat")), 15, 15, TimeUnit.SECONDS);
    }

    @GetMapping("/posts")
    public ResponseEntity<?> posts(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("posts", blog.posts(limit), "revision", blog.revision()));
    }

    @GetMapping("/post")
    public ResponseEntity<MarkdownBlog.Article> post(@RequestParam String slug) {
        var article = blog.article(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(article);
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(0L);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> { clients.remove(emitter); emitter.complete(); });
        emitter.onError(error -> clients.remove(emitter));
        // A fresh snapshot event on EVERY connection also covers missed events and server restarts.
        send(emitter, "sync");
        return emitter;
    }

    private void send(SseEmitter emitter, String event) {
        try {
            emitter.send(SseEmitter.event().name(event).id(blog.revision()).reconnectTime(2000)
                    .data(Map.of("revision", blog.revision())));
        } catch (Exception e) {
            clients.remove(emitter);
            emitter.complete();
        }
    }

    @GetMapping("/assets")
    public ResponseEntity<Resource> asset(@RequestParam String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.containsKey(extension)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(IMAGE_TYPES.get(extension)))
                    .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noCache())
                    .body(new FileSystemResource(blog.asset(path)));
        } catch (IOException | IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }

    @PreDestroy
    public void close() throws Exception {
        subscription.close();
        heartbeat.shutdownNow();
        clients.forEach(SseEmitter::complete);
        clients.clear();
    }
}
