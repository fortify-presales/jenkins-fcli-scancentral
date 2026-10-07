package com.example.vulnapp.web;

import java.io.IOException;
import java.io.ObjectInputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeserializationController {

    // Intentional: Insecure Deserialization of untrusted data
    @PostMapping("/deserialize")
    public String deserialize(HttpServletRequest request) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(request.getInputStream())) {
            Object obj = in.readObject();
            return "Deserialized: " + obj.getClass().getName();
        }
    }
}
