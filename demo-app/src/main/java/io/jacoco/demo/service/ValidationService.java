package io.jacoco.demo.service;

import org.springframework.stereotype.Service;

/**
 * Barely exercised — only the happy-path email validation is called,
 * leaving most branches uncovered. Contributes to LOW overall coverage.
 */
@Service
public class ValidationService {

    public ValidationResult validateEmail(String email) {
        if (email == null) {
            return ValidationResult.invalid("Email must not be null");
        }
        if (email.trim().isEmpty()) {
            return ValidationResult.invalid("Email must not be blank");
        }
        if (!email.contains("@")) {
            return ValidationResult.invalid("Email must contain '@'");
        }
        String[] parts = email.split("@", 2);
        if (parts[0].isEmpty()) {
            return ValidationResult.invalid("Local part must not be empty");
        }
        if (parts[1].isEmpty() || !parts[1].contains(".")) {
            return ValidationResult.invalid("Domain part is invalid");
        }
        return ValidationResult.valid();
    }

    /** Never called in the demo — fully uncovered. */
    public ValidationResult validatePhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return ValidationResult.invalid("Phone must not be blank");
        }
        String digits = phone.replaceAll("[\\s\\-().+]", "");
        if (!digits.matches("\\d+")) {
            return ValidationResult.invalid("Phone must contain only digits");
        }
        if (digits.length() < 7 || digits.length() > 15) {
            return ValidationResult.invalid("Phone length must be between 7 and 15 digits");
        }
        return ValidationResult.valid();
    }

    /** Never called in the demo — fully uncovered. */
    public ValidationResult validateAge(int age) {
        if (age < 0) {
            return ValidationResult.invalid("Age cannot be negative");
        }
        if (age > 150) {
            return ValidationResult.invalid("Age is unrealistically high");
        }
        return ValidationResult.valid();
    }

    // -------------------------------------------------------------------------
    // Inner result type
    // -------------------------------------------------------------------------

    public static class ValidationResult {
        private final boolean valid;
        private final String  message;

        private ValidationResult(boolean valid, String message) {
            this.valid   = valid;
            this.message = message;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, "OK");
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid()    { return valid; }
        public String  getMessage() { return message; }
    }
}
