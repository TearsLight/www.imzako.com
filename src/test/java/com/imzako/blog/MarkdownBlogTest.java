package com.imzako.blog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

class MarkdownBlogTest {
    @TempDir Path directory;

    @Test
    void scansExistingMarkdownAndCapsTheListAtTenNewestArticles() throws Exception {
        for (int i = 0; i < 12; i++) {
            Path file = Files.writeString(directory.resolve("post-" + i + ".md"), "# Article " + i + "\n\nA **real** article.");
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-12T08:00:00Z").plusMillis(i * 100)));
        }
        Files.writeString(directory.resolve("ignore.txt"), "# Not Markdown");
        Files.writeString(directory.resolve("oversized.md"), "x".repeat(1_048_577));
        try (var blog = runningBlog()) {
            assertThat(blog.posts(99)).hasSize(10);
            assertThat(blog.posts(2)).extracting(MarkdownBlog.Post::id).containsExactly("post-11", "post-10");
            assertThat(blog.posts(-1)).isEmpty();
            assertThat(blog.article("post-0")).isPresent();
            assertThat(blog.article("ignore")).isEmpty();
            assertThat(blog.article("oversized")).isEmpty();
            assertThat(blog.article("post-0").orElseThrow().post().excerpt()).isEqualTo("A real article.");
        }
    }

    @Test
    void followsCreatesAtomicSavesRenamesAndDeletesInsideNewSubdirectories() throws Exception {
        try (var blog = runningBlog()) {
            Path folder = Files.createDirectory(directory.resolve("随笔"));
            Path file = Files.writeString(folder.resolve("第一篇.MD"), "\uFEFF# 最初的标题\n\n第一版内容");
            await(() -> blog.article("随笔/第一篇").isPresent());
            var initial = blog.article("随笔/第一篇").orElseThrow();
            assertThat(initial.post().title()).isEqualTo("最初的标题");
            assertThat(initial.post().category()).isEqualTo("随笔");
            assertThat(initial.post().url()).contains("%E9%9A%8F%E7%AC%94");
            String revision = blog.revision();

            Path saved = Files.writeString(folder.resolve("save.tmp"), "# 更新后的标题\n\n第二版内容");
            Files.move(saved, file, StandardCopyOption.REPLACE_EXISTING);
            await(() -> blog.article("随笔/第一篇").map(a -> a.markdown().contains("第二版内容")).orElse(false));
            assertThat(blog.revision()).isNotEqualTo(revision);
            assertThat(blog.article("随笔/第一篇").orElseThrow().post().url()).isEqualTo(initial.post().url());

            Path renamed = Files.move(file, folder.resolve("renamed.md"));
            await(() -> blog.article("随笔/第一篇").isEmpty() && blog.article("随笔/renamed").isPresent());
            Files.delete(renamed);
            await(() -> blog.posts(10).isEmpty());
        }
    }

    @Test
    void frontMatterSuppliesTheTitleDateAndCategories() throws Exception {
        Files.writeString(directory.resolve("guide.md"), """
                ---
                title: Front Matter 指南
                date: 2026-09-12T21:30:00+08:00
                tags: 技术 Markdown
                ---

                # 正文标题会被忽略

                正文内容。
                """);
        try (var blog = runningBlog()) {
            var post = blog.article("guide").orElseThrow().post();
            assertThat(post.title()).isEqualTo("Front Matter 指南");
            assertThat(post.publishedAt()).isEqualTo("2026-09-12T13:30:00Z");
            assertThat(post.categories()).containsExactly("技术", "Markdown");
            assertThat(post.category()).isEqualTo("技术 · Markdown");
            // The metadata block must not leak into the body or the excerpt.
            String body = blog.article("guide").orElseThrow().markdown();
            assertThat(body).doesNotContain("title:").doesNotContain("---");
            assertThat(body).startsWith("# 正文标题会被忽略");
            assertThat(post.excerpt()).doesNotContain("Front Matter 指南").doesNotContain("tags");
        }
    }

    @Test
    void draftFrontMatterKeepsAnArticleOutOfThePublicIndex() throws Exception {
        Files.writeString(directory.resolve("draft.md"), """
                ---
                title: Work in progress
                draft: true
                ---

                This must stay private.
                """);

        try (var blog = runningBlog()) {
            assertThat(blog.posts(10)).isEmpty();
            assertThat(blog.article("draft")).isEmpty();
        }
    }

    @Test
    void frontMatterTagsMergeWithTheContentDirectory() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("notes"));
        Files.writeString(folder.resolve("merge.md"), """
                ---
                title: 合并分类
                date: 2026-09-10
                tags: 技术 笔记
                ---

                内容。
                """);
        try (var blog = runningBlog()) {
            var post = blog.article("notes/merge").orElseThrow().post();
            // Tags come first, then the directory, without duplicates.
            assertThat(post.categories()).containsExactly("技术", "笔记", "notes");
            assertThat(post.category()).isEqualTo("技术 · 笔记 · notes");
            // A date-only value is read as UTC midnight.
            assertThat(post.publishedAt()).isEqualTo("2026-09-10T00:00:00Z");
        }
    }

    @Test
    void withoutFrontMatterTheHeadingAndDirectoryStillDecide() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("随笔"));
        Files.writeString(folder.resolve("plain.md"), "# 一级标题决定标题\n\n内容。");
        try (var blog = runningBlog()) {
            var post = blog.article("随笔/plain").orElseThrow().post();
            assertThat(post.title()).isEqualTo("一级标题决定标题");
            assertThat(post.categories()).containsExactly("随笔");
            assertThat(post.category()).isEqualTo("随笔");
        }
    }

    @Test
    void anUnparseableFrontMatterDateFallsBackToTheFileTime() throws Exception {
        Path file = Files.writeString(directory.resolve("bad-date.md"), """
                ---
                title: 日期无法解析
                date: 昨天下午
                tags: 测试
                ---

                内容。
                """);
        Instant stamp = Instant.parse("2026-09-01T04:05:06Z");
        Files.setLastModifiedTime(file, FileTime.from(stamp));
        try (var blog = runningBlog()) {
            var post = blog.article("bad-date").orElseThrow().post();
            assertThat(post.title()).isEqualTo("日期无法解析");
            assertThat(post.publishedAt()).isEqualTo(stamp.toString());
        }
    }

    @Test
    void frontMatterParsingToleratesQuotesHashesAndCommas() {
        var parsed = MarkdownBlog.parseFrontMatter("""
                ---
                title: "带引号的标题"
                date: 2026-09-12 21:30:00
                tags: #技术, 笔记，Design
                ---

                内容
                """);
        assertThat(parsed.title()).isEqualTo("带引号的标题");
        assertThat(parsed.tags()).containsExactly("技术", "笔记", "Design");
        assertThat(parsed.date()).isEqualTo("2026-09-12T21:30:00Z");
    }

    @Test
    void frontMatterHelpersHandleMissingAndOddInput() {
        assertThat(MarkdownBlog.parseFrontMatter("# 没有 front matter").title()).isNull();
        assertThat(MarkdownBlog.parseFrontMatter("# 没有 front matter").tags()).isEmpty();
        // A block that never closes is treated as ordinary content.
        assertThat(MarkdownBlog.stripFrontMatter("---\ntitle: x\n\n正文")).startsWith("---");
        assertThat(MarkdownBlog.splitTags("   ")).isEmpty();
        assertThat(MarkdownBlog.splitTags("-")).isEmpty();
        assertThat(MarkdownBlog.splitTags("a  b   c")).containsExactly("a", "b", "c");
        // Only the first directory segment counts as a category.
        assertThat(MarkdownBlog.categories(java.util.List.of(), "notes/deep/post.md"))
                .containsExactly("notes");
        assertThat(MarkdownBlog.categories(java.util.List.of(), "root.md")).containsExactly("随笔");
    }

    @Test
    void imagePathsCannotEscapeTheBlogDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("blog"));
        Files.write(root.resolve("image.png"), new byte[] {1, 2});
        Files.write(directory.resolve("private.png"), new byte[] {3, 4});
        try (var blog = new MarkdownBlog(root.toString())) {
            blog.start();
            assertThat(blog.asset("image.png")).isEqualTo(root.resolve("image.png"));
            assertThatThrownBy(() -> blog.asset("../private.png")).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> blog.asset(directory.resolve("private.png").toString())).isInstanceOf(java.io.IOException.class);
        }
    }

    private MarkdownBlog runningBlog() throws Exception {
        var blog = new MarkdownBlog(directory.toString());
        blog.start();
        return blog;
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("file watcher should refresh the article index").isTrue();
    }
}
