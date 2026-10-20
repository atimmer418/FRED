package com.investingapp.backend.service;

/** One row of Alpaca's GET /accounts/{id}/ach_relationships, as much of it as FRED reads. */
public record AchRelationshipSummary(String id, String status, String createdAt, String bankAccountNumber) {
}
