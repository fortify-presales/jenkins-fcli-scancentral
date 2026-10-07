package com.example.vulnapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final JdbcTemplate jdbcTemplate;

    public UserController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Intentional: SQL Injection
    @GetMapping("/users")
    public List<Map<String, Object>> findUsers(@RequestParam String name) {
        String sql = "SELECT id, username, email FROM users WHERE username = '" + name + "'";
        return jdbcTemplate.queryForList(sql);
    }
}
