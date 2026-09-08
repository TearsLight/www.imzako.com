package com.imzako.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    // Only known page routes fall back to Vue; missing APIs and assets remain 404.
    @GetMapping({"/", "/blog", "/blog/", "/login", "/login/", "/about", "/about/"})
    public String page() {
        return "forward:/index.html";
    }
}
