package com.imzako.blog;

import com.imzako.auth.RequestAuthorization;
import com.imzako.auth.UserAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;

/** Level-3 owner interface for creating, editing and deleting Markdown posts. */
@RestController
@RequestMapping("/api/blog/manage")
public class BlogManagementController {
    private final MarkdownBlog blog;
    private final RequestAuthorization authorization;

    public BlogManagementController(MarkdownBlog blog, RequestAuthorization authorization) {
        this.blog = blog;
        this.authorization = authorization;
    }

    @GetMapping("/posts")
    public List<MarkdownBlog.ManagedArticle> posts(HttpServletRequest request) {
        owner(request);
        try {
            return blog.managedPosts();
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    @GetMapping("/post")
    public MarkdownBlog.ManagedArticle post(@RequestParam String slug, HttpServletRequest request) {
        owner(request);
        try {
            return blog.managedArticle(slug);
        } catch (NoSuchFileException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "博客不存在");
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkdownBlog.CreatedPost create(@Valid @RequestBody CreatePost body, HttpServletRequest request) {
        writableOwner(request);
        try {
            return blog.createPost(body.title(), body.tags(), body.draft(), body.directory());
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    @PutMapping("/post")
    public MarkdownBlog.ManagedArticle update(@Valid @RequestBody UpdatePost body, HttpServletRequest request) {
        writableOwner(request);
        try {
            return blog.updatePost(body.slug(), body.title(), body.tags(), body.draft(), body.markdown());
        } catch (NoSuchFileException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "博客不存在");
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    @DeleteMapping("/post")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam String slug, HttpServletRequest request) {
        writableOwner(request);
        try {
            blog.deletePost(slug);
        } catch (NoSuchFileException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "博客不存在");
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private void owner(HttpServletRequest request) {
        authorization.requirePermission(request, UserAccessService.OWNER);
    }

    private void writableOwner(HttpServletRequest request) {
        authorization.requireWritablePermission(request, UserAccessService.OWNER);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(IllegalArgumentException error) {
        return java.util.Map.of("code", "INVALID_BLOG", "message", error.getMessage());
    }

    private static ResponseStatusException unavailable(IOException error) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "博客文件暂时不可写", error);
    }

    public record CreatePost(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String tags,
            boolean draft,
            @Size(max = 200) String directory
    ) {}

    public record UpdatePost(
            @NotBlank @Size(max = 500) String slug,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) String tags,
            boolean draft,
            @NotNull @Size(max = 1_000_000) String markdown
    ) {}
}
