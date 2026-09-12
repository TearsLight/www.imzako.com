package com.imzako.blog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class MarkdownBlog implements AutoCloseable {
    public record Post(String id, String title, String excerpt, String url, String publishedAt, String category) {}
    public record Article(Post post, String markdown, String updatedAt) {}
    private static final Logger log = LoggerFactory.getLogger(MarkdownBlog.class);
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+?)\\s*#*\\s*$");
    private final Path root;
    private final Map<WatchKey, Path> watched = new HashMap<>();
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "markdown-blog-watch"); thread.setDaemon(true); return thread;
    });
    private volatile Map<String, Article> articles = Map.of();
    private volatile String revision = UUID.randomUUID().toString();
    private WatchService watcher;
    private long dirtySince;
    private long lastScan;

    public MarkdownBlog(@Value("${BLOG_DIRECTORY:blog}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void start() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Blog directory must not be a symbolic link");
        watcher = root.getFileSystem().newWatchService();
        scan();
        worker.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        log.info("Markdown blog ready: {} ({} articles)", root, articles.size());
    }

    public String revision() { return revision; }
    public AutoCloseable listen(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    public List<Post> posts(int limit) {
        return articles.values().stream().map(Article::post)
                .sorted(Comparator.comparing((Post post) -> Instant.parse(post.publishedAt())).reversed().thenComparing(Post::id))
                .limit(Math.max(0, Math.min(limit, 10))).toList();
    }
    public Optional<Article> article(String slug) { return Optional.ofNullable(articles.get(slug)); }

    // Asset requests resolve inside the blog root, including every real path component.
    public Path asset(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(root.toRealPath())) throw new NoSuchFileException(relative);
        return path;
    }

    private void tick() {
        try {
            WatchKey key;
            while ((key = watcher.poll()) != null) {
                if (!key.pollEvents().isEmpty()) dirtySince = System.currentTimeMillis();
                if (!key.reset()) watched.remove(key);
            }
            long now = System.currentTimeMillis();
            if ((dirtySince != 0 && now - dirtySince >= 300) || now - lastScan >= 10000) scan();
        } catch (Exception e) {
            log.warn("Cannot refresh Markdown blog; keeping the last readable index", e);
        }
    }

    private void scan() throws IOException {
        Map<String, Article> next = new HashMap<>();
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) continue;
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (!watched.containsValue(path)) {
                            watched.put(path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), path);
                        }
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        String slug = relative.substring(0, relative.length() - 3);
                        try {
                            // Bound the amount of Markdown read from each file.
                            if (Files.size(path) > 1_048_576) { log.warn("Skipping oversized Markdown: {}", relative); continue; }
                            String markdown = Files.readString(path, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
                            String modified = Files.getLastModifiedTime(path).toInstant().toString();
                            var titleMatch = TITLE.matcher(markdown);
                            String title = titleMatch.find() ? titleMatch.group(1) : path.getFileName().toString().replaceFirst("(?i)\\.md$", "");
                            String excerpt = markdown.replaceAll("(?s)```.*?```|~~~.*?~~~", " ")
                                    .replaceAll("(?m)^#{1,6}\\s+.*$", " ")
                                    .replaceAll("!?\\[([^]]*)]\\([^)]+\\)", "$1")
                                    .replaceAll("[`*_>#|]", "").replaceAll("\\s+", " ").trim();
                            if (excerpt.length() > 180) excerpt = excerpt.substring(0, 180) + "…";
                            String url = "/blog/posts/" + UriUtils.encodePath(slug, StandardCharsets.UTF_8);
                            String category = root.relativize(path).getNameCount() > 1 ? root.relativize(path).getName(0).toString() : "随笔";
                            next.put(slug, new Article(new Post(slug, title, excerpt, url, modified, category), markdown, modified));
                        } catch (IOException e) {
                            // Editors may briefly lock a file during an atomic save; retry next scan.
                            if (articles.containsKey(slug)) next.put(slug, articles.get(slug));
                            log.debug("Markdown temporarily unreadable: {}", relative);
                        }
                    }
                }
            }
        }
        dirtySince = 0;
        lastScan = System.currentTimeMillis();
        if (!next.equals(articles)) {
            articles = Map.copyOf(next);
            revision = UUID.randomUUID().toString();
            listeners.forEach(listener -> {
                try { listener.run(); } catch (RuntimeException e) { log.debug("Blog subscriber disconnected", e); }
            });
        }
    }

    @PreDestroy
    public void close() throws IOException {
        worker.shutdownNow();
        if (watcher != null) watcher.close();
        listeners.clear();
    }
}
