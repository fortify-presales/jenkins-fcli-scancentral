package com.example.vulnapp.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class GreetingController {

    // Intentional: Reflected Cross-Site Scripting
    @GetMapping("/greet")
    public void greet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String name = request.getParameter("name");
        response.setContentType("text/html");
        response.getWriter().write("<html><body><h1>Hello " + name + "</h1></body></html>");
    }
}
