package com.webcompiler.backend;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(origins = "http://localhost:5173") // allow requests from frontend
@RestController
public class HelloController {

    @GetMapping("/")
    public String home() {
        return "Backend is running!";
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Spring Boot!";
    }
}

