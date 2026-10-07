package com.example.vulnapp.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.vulnapp.util.CryptoUtil;

@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JdbcTemplate jdbcTemplate;

    public AuthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestParam String username, @RequestParam String password) throws Exception {
        // Intentional: Log Forging
        log.info("Login attempt for user: " + username);

        if ("admin".equals(username) && CryptoUtil.ADMIN_PASSWORD.equals(password)) {
            return ResponseEntity.ok(CryptoUtil.generateSessionToken());
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE username = ? AND password_hash = ?",
                username, CryptoUtil.hashPassword(password));
        if (rows.isEmpty()) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }
        return ResponseEntity.ok(CryptoUtil.generateSessionToken());
    }

    @PostMapping("/encrypt")
    public String encrypt(@RequestParam String value) throws Exception {
        return CryptoUtil.encrypt(value);
    }
}
