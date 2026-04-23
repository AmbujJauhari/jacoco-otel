package io.jacoco.demo.service;

import org.springframework.stereotype.Service;

/**
 * Fully exercised by the demo — contributes to HIGH line coverage.
 */
@Service
public class GreetingService {

    public String greet(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Hello, stranger!";
        }
        return "Hello, " + name.trim() + "!";
    }

    public String greetFormal(String title, String name) {
        if (title == null || title.isEmpty()) {
            return greet(name);
        }
        return "Good day, " + title + " " + name + ".";
    }

    public String farewell(String name) {
        return "Goodbye, " + (name == null ? "friend" : name) + "!";
    }
}
