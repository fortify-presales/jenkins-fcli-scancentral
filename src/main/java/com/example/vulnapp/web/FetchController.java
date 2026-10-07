package com.example.vulnapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FetchController {

    // Intentional: Server-Side Request Forgery
    @SuppressWarnings("deprecation")
    @GetMapping("/fetch")
    public String fetch(@RequestParam String url) throws IOException {
        try (InputStream in = new URL(url).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
