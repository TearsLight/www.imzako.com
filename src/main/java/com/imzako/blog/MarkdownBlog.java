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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class MarkdownBlog implements AutoCloseable {
    /**
     * {@code categories} merges the front-matter tags with the first directory below
     * the blog root; {@code category} keeps the joined form for display.
     */
    public record Post(String id, String title, String excerpt, String url, String publishedAt,
                       String category, List<String> categories) {}
    public record Article(Post post, String markdown, String updatedAt) {}
    private static final Logger log = LoggerFactory.getLogger(MarkdownBlog.class);
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+?)\\s*#*\\s*$");
    /** YAML-style front matter delimited by --- lines at the very start of the file. */
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A\\uFEFF?---\\s*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
    private static final String DEFAULT_CATEGORY = "随笔";
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
                            String raw = Files.readString(path, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
                            Instant modifiedAt = Files.getLastModifiedTime(path).toInstant();
                            var frontMatter = parseFrontMatter(raw);
                            if (frontMatter.draft()) continue;
                            // The metadata block is not part of the article body.
                            String markdown = stripFrontMatter(raw);
                            String title = frontMatter.title() != null ? frontMatter.title() : headingOrFileName(markdown, path);
                            List<String> categories = categories(frontMatter.tags(), relative);
                            String publishedAt = frontMatter.date() != null ? frontMatter.date() : modifiedAt.toString();
                            String excerpt = excerpt(markdown);
                            String url = "/blog/posts/" + UriUtils.encodePath(slug, StandardCharsets.UTF_8);
                            next.put(slug, new Article(
                                    new Post(slug, title, excerpt, url, publishedAt, String.join(" · ", categories), categories),
                                    markdown,
                                    modifiedAt.toString()));
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

    /** Parsed front-matter values; every field is optional. */
    record FrontMatter(String title, String date, List<String> tags, boolean draft) {}

    static FrontMatter parseFrontMatter(String markdown) {
        var match = FRONT_MATTER.matcher(markdown);
        if (!match.find()) return new FrontMatter(null, null, List.of(), false);

        String title = null;
        String date = null;
        List<String> tags = List.of();
        boolean draft = false;
        for (String line : match.group(1).split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(colon + 1).trim());
            switch (key) {
                case "title" -> { if (!value.isEmpty()) title = value; }
                case "date" -> { date = parseDate(value); }
                case "tags", "tag" -> tags = splitTags(value);
                case "draft" -> draft = "true".equalsIgnoreCase(value)
                        || "yes".equalsIgnoreCase(value)
                        || "1".equals(value);
                default -> { }
            }
        }
        return new FrontMatter(title, date, tags, draft);
    }

    /** Removes the front-matter block so it never reaches the rendered article. */
    static String stripFrontMatter(String markdown) {
        return FRONT_MATTER.matcher(markdown).replaceFirst("").stripLeading();
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * Accepts tags separated by spaces, commas or the YAML list dash. A leading
     * {@code #} is tolerated so users can paste hashtag style input.
     */
    static List<String> splitTags(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return List.of();
        var tags = new LinkedHashSet<String>();
        for (String token : value.split("[\\s,，]+")) {
            String tag = token.strip().replaceFirst("^#+", "").trim();
            if (!tag.isEmpty()) tags.add(tag);
        }
        return List.copyOf(tags);
    }

    /** Returns an ISO instant for the front-matter date, or null when unparseable. */
    static String parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim().replace(' ', 'T');
        try {
            return Instant.parse(candidate).toString();
        } catch (RuntimeException notAnInstant) {
            // A plain date or local timestamp is read as UTC so it still sorts by day.
            try {
                return LocalDateTime.parse(candidate).toInstant(ZoneOffset.UTC).toString();
            } catch (RuntimeException notALocalDateTime) {
                try {
                    return LocalDate.parse(candidate).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
                } catch (RuntimeException notADate) {
                    return null;
                }
            }
        }
    }

    /** Tags become categories; the first directory below the root also counts. */
    static List<String> categories(List<String> tags, String relativePath) {
        var categories = new LinkedHashSet<String>(tags);
        int separator = relativePath.indexOf('/');
        if (separator > 0) categories.add(relativePath.substring(0, separator));
        if (categories.isEmpty()) categories.add(DEFAULT_CATEGORY);
        return List.copyOf(categories);
    }

    private static String headingOrFileName(String markdown, Path path) {
        var titleMatch = TITLE.matcher(markdown);
        return titleMatch.find()
                ? titleMatch.group(1)
                : path.getFileName().toString().replaceFirst("(?i)\\.md$", "");
    }

    private static String excerpt(String markdown) {
        String excerpt = markdown.replaceAll("(?s)```.*?```|~~~.*?~~~", " ")
                .replaceAll("(?m)^#{1,6}\\s+.*$", " ")
                .replaceAll("!?\\[([^]]*)]\\([^)]+\\)", "$1")
                .replaceAll("[`*_>#|]", "").replaceAll("\\s+", " ").trim();
        return excerpt.length() > 180 ? excerpt.substring(0, 180) + "…" : excerpt;
    }
}
