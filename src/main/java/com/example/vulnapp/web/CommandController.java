package com.example.vulnapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommandController {

    // Intentional: Command Injection
    @GetMapping("/ping")
    public String ping(@RequestParam String host) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(new String[] {"sh", "-c", "ping -c 1 " + host});
        try (InputStream in = process.getInputStream()) {
            String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        }
    }
}
