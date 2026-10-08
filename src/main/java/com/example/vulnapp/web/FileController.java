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
        File base = new File(baseDir).getCanonicalFile();
        File file = new File(base, name).getCanonicalFile();
        if (!file.toPath().startsWith(base.toPath())) {
            throw new SecurityException("Access to the requested path is not allowed.");
        }
        return Files.readAllBytes(file.toPath());
    }
}
