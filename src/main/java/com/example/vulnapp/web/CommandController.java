
package com.example.vulnapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommandController {

    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-]{1,253}$");

    @GetMapping("/ping")
    public String ping(@RequestParam String host) throws IOException, InterruptedException {
        if (!SAFE_HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("Invalid host parameter");
        }
        Process process = Runtime.getRuntime().exec(new String[] {"ping", "-c", "1", host});
        try (InputStream in = process.getInputStream()) {
            String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        }
    }
}