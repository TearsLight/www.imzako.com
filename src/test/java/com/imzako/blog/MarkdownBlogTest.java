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
