package com.example.vulnapp.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RedirectController {

    // Intentional: Open Redirect
    @GetMapping("/redirect")
    public void redirect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String target = request.getParameter("target");
        // Only allow relative URLs (no scheme or host) to prevent open redirect
        if (target == null || target.contains("://") || target.startsWith("//")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid redirect target");
            return;
        }
        response.sendRedirect(target);
}
