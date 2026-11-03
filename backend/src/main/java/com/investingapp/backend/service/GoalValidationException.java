package com.investingapp.backend.service;

public class GoalValidationException extends RuntimeException {
    public GoalValidationException(String message) {
        super(message);
    }
}
