package com.ai.aicommunity.dto;

/**
 * A short-lived admission token issued before a user enters the enrollment path.
 */
public record TrainingCampQualificationResponse(String token, int expireSeconds) {
}
