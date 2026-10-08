package com.example.vulnapp.web;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileController {

    @Value("${app.files.base-dir}")
    private String baseDir;

    // Intentional: Path Traversal
    @GetMapping("/files")
    public byte[] download(@RequestParam String name) throws IOException {
        File file = new File(baseDir, name);
        return Files.readAllBytes(file.toPath());
    }
}
