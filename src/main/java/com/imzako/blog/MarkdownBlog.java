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
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    public record ManagedArticle(String slug, String title, String date, List<String> tags,
                                 boolean draft, String markdown, String updatedAt) {}
    public record CreatedPost(String slug, String title, boolean draft, String path) {}
    private static final Logger log = LoggerFactory.getLogger(MarkdownBlog.class);
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+?)\\s*#*\\s*$");
    /** YAML-style front matter delimited by --- lines at the very start of the file. */
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A\\uFEFF?---\\s*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
    private static final DateTimeFormatter COMMAND_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");
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
        return posts(Math.min(limit, 10), null);
    }

    public List<Post> posts(int limit, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return articles.values().stream().map(Article::post)
                .filter(post -> needle.isEmpty() || searchable(post).contains(needle))
                .sorted(Comparator.comparing((Post post) -> Instant.parse(post.publishedAt())).reversed().thenComparing(Post::id))
                .limit(Math.max(0, Math.min(limit, 200))).toList();
    }
    public Optional<Article> article(String slug) { return Optional.ofNullable(articles.get(slug)); }

    /** Creates a Markdown draft without Node/npm; used by both RCON and the owner API. */
    public synchronized CreatedPost createPost(String requestedTitle, String requestedTags,
                                               boolean draft, String requestedDirectory) throws IOException {
        String title = oneLine(requestedTitle, "博客标题");
        List<String> tags = splitTags(requestedTags);
        Path directory = safeDirectory(requestedDirectory);
        Files.createDirectories(directory);
        rejectSymbolicComponents(directory);

        String base = fileName(title);
        Path target = directory.resolve(base + ".md");
        int suffix = 2;
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target = directory.resolve(base + "-" + suffix++ + ".md");
        }
        String date = LocalDateTime.now().format(COMMAND_DATE);
        writePost(target, title, date, tags, draft, "# " + title + "\n\n");
        return new CreatedPost(slug(target), title, draft, root.relativize(target).toString().replace('\\', '/'));
    }

    public synchronized ManagedArticle managedArticle(String requestedSlug) throws IOException {
        Path path = safePost(requestedSlug, true);
        return readManaged(path);
    }

    public synchronized List<ManagedArticle> managedPosts() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var result = new ArrayList<ManagedArticle>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList()) {
                if (!Files.isSymbolicLink(path) && Files.size(path) <= 1_048_576) result.add(readManaged(path));
            }
        }
        result.sort(Comparator.comparing(ManagedArticle::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(result);
    }

    public synchronized ManagedArticle updatePost(String requestedSlug, String requestedTitle,
                                                   String requestedTags, boolean draft,
                                                   String markdown) throws IOException {
        Path path = safePost(requestedSlug, true);
        ManagedArticle existing = readManaged(path);
        String title = oneLine(requestedTitle, "博客标题");
        if (markdown == null || markdown.length() > 1_000_000) {
            throw new IllegalArgumentException("博客正文不能超过 1000000 个字符");
        }
        String date = existing.date() == null || existing.date().isBlank()
                ? LocalDateTime.now().format(COMMAND_DATE) : existing.date();
        writePost(path, title, date, splitTags(requestedTags), draft, markdown);
        return readManaged(path);
    }

    public synchronized void deletePost(String requestedSlug) throws IOException {
        Files.delete(safePost(requestedSlug, true));
    }

    // Asset requests resolve inside the blog root, including every real path component.
    public Path asset(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(root.toRealPath())) throw new NoSuchFileException(relative);
        return path;
    }

    private static String searchable(Post post) {
        return (post.title() + " " + post.excerpt() + " " + post.category())
                .toLowerCase(Locale.ROOT);
    }

    private Path safeDirectory(String requested) throws IOException {
        String value = requested == null ? "" : requested.strip().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.equals("blog")) value = "";
        else if (value.startsWith("blog/")) value = value.substring(5);
        Path directory = root.resolve(value).normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("博客目录必须位于 BLOG_DIRECTORY 内");
        return directory;
    }

    private Path safePost(String requestedSlug, boolean mustExist) throws IOException {
        String value = requestedSlug == null ? "" : requestedSlug.strip().replace('\\', '/');
        if (value.toLowerCase(Locale.ROOT).endsWith(".md")) value = value.substring(0, value.length() - 3);
        if (value.isEmpty() || value.startsWith("/") || value.contains("\0")) {
            throw new IllegalArgumentException("博客 slug 无效");
        }
        Path path = root.resolve(value + ".md").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("博客 slug 无效");
        if (mustExist && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(value);
        rejectSymbolicComponents(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("博客文件不能是符号链接");
        return path;
    }

    private void rejectSymbolicComponents(Path path) throws IOException {
        Path current = root;
        Path relative = root.relativize(path.normalize());
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("博客目录不能包含符号链接");
        }
    }

    private ManagedArticle readManaged(Path path) throws IOException {
        if (Files.size(path) > 1_048_576) throw new IOException("博客文件超过 1 MiB");
        String raw = Files.readString(path, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        FrontMatter front = parseFrontMatter(raw);
        String body = stripFrontMatter(raw);
        String title = front.title() == null ? headingOrFileName(body, path) : front.title();
        return new ManagedArticle(slug(path), title, front.date(), front.tags(), front.draft(), body,
                Files.getLastModifiedTime(path).toInstant().toString());
    }

    private void writePost(Path target, String title, String date, List<String> tags,
                           boolean draft, String markdown) throws IOException {
        String content = "---\n"
                + "title: " + title + "\n"
                + "date: " + date + "\n"
                + "tags: " + String.join(" ", tags) + "\n"
                + "draft: " + draft + "\n"
                + "---\n\n"
                + (markdown == null ? "" : markdown.stripLeading());
        if (!content.endsWith("\n")) content += "\n";
        Path temporary = Files.createTempFile(target.getParent(), ".imzako-post-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String slug(Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - 3);
    }

    private static String fileName(String title) {
        String slug = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-");
        return slug.isBlank() ? "post" : slug;
    }

    private static String oneLine(String value, String label) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException(label + "长度必须在 1 到 200 个字符之间");
        }
        return normalized;
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
                    return LocalDateTime.parse(value.trim(), COMMAND_DATE)
                            .atZone(ZoneId.systemDefault()).toInstant().toString();
                } catch (RuntimeException notACommandDate) {
                    try {
                    return LocalDate.parse(candidate).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
                    } catch (RuntimeException notADate) {
                        return null;
                    }
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
