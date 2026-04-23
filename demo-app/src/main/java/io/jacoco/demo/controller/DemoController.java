package io.jacoco.demo.controller;

import io.jacoco.demo.service.CalculatorService;
import io.jacoco.demo.service.GreetingService;
import io.jacoco.demo.service.ValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {

    private final GreetingService    greetingService;
    private final CalculatorService  calculatorService;
    private final ValidationService  validationService;

    public DemoController(GreetingService greetingService,
                          CalculatorService calculatorService,
                          ValidationService validationService) {
        this.greetingService   = greetingService;
        this.calculatorService = calculatorService;
        this.validationService = validationService;
    }

    // -------------------------------------------------------------------------
    // GET /api/greet?name=Alice
    // Exercises: GreetingService.greet() — HIGH coverage
    // -------------------------------------------------------------------------
    @GetMapping("/greet")
    public ResponseEntity<Map<String, String>> greet(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String title) {

        String message = (title != null)
            ? greetingService.greetFormal(title, name)
            : greetingService.greet(name);

        Map<String, String> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // GET /api/calculate?op=add&a=5&b=3
    // Exercises: CalculatorService.add/subtract — MEDIUM coverage (mul/div skipped)
    // -------------------------------------------------------------------------
    @GetMapping("/calculate")
    public ResponseEntity<?> calculate(
            @RequestParam String op,
            @RequestParam double a,
            @RequestParam double b) {
        try {
            double result = calculatorService.calculate(op, a, b);
            Map<String, Object> body = new HashMap<>();
            body.put("operation", op);
            body.put("a", a);
            body.put("b", b);
            body.put("result", result);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/validate?email=user@example.com
    // Exercises: ValidationService.validateEmail() happy path — LOW branch coverage
    // -------------------------------------------------------------------------
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestParam String email) {
        ValidationService.ValidationResult result = validationService.validateEmail(email);
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("valid", result.isValid());
        body.put("message", result.getMessage());
        return ResponseEntity.ok(body);
    }

    // -------------------------------------------------------------------------
    // GET /api/farewell?name=Alice
    // -------------------------------------------------------------------------
    @GetMapping("/farewell")
    public ResponseEntity<Map<String, String>> farewell(@RequestParam(required = false) String name) {
        Map<String, String> body = new HashMap<>();
        body.put("message", greetingService.farewell(name));
        return ResponseEntity.ok(body);
    }
}
