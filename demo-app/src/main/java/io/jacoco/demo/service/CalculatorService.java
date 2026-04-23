package io.jacoco.demo.service;

import org.springframework.stereotype.Service;

/**
 * Partially exercised — only add/subtract are called via the demo endpoint,
 * leaving multiply/divide uncovered and contributing to MEDIUM coverage.
 */
@Service
public class CalculatorService {

    public double calculate(String operation, double a, double b) {
        switch (operation.toLowerCase()) {
            case "add":
                return add(a, b);
            case "sub":
            case "subtract":
                return subtract(a, b);
            case "mul":
            case "multiply":
                return multiply(a, b);
            case "div":
            case "divide":
                return divide(a, b);
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    public double add(double a, double b) {
        return a + b;
    }

    public double subtract(double a, double b) {
        return a - b;
    }

    /** Not called by the demo endpoint — intentionally uncovered. */
    public double multiply(double a, double b) {
        return a * b;
    }

    /** Not called by the demo endpoint — intentionally uncovered. */
    public double divide(double a, double b) {
        if (b == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return a / b;
    }

    /** Not called at all — intentionally uncovered. */
    public double power(double base, int exponent) {
        double result = 1.0;
        for (int i = 0; i < Math.abs(exponent); i++) {
            result *= base;
        }
        return exponent < 0 ? 1.0 / result : result;
    }
}
