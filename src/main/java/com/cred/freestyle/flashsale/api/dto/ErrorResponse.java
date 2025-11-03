package com.cred.freestyle.flashsale.api.dto;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Standardized error response DTO for all API errors.
 * Provides consistent error structure across the application.
 *
 * @author Flash Sale Team
 */
public class ErrorResponse {

    private Instant timestamp;
    private Integer status;
    private String error;
    private String message;
    private String path;
    private Map<String, Object> details;

    public ErrorResponse() {
        this.timestamp = Instant.now();
        this.details = new HashMap<>();
    }

    public ErrorResponse(Integer status, String error, String message, String path) {
        this();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    /**
     * Add additional error details.
     *
     * @param key Detail key
     * @param value Detail value
     * @return This ErrorResponse for method chaining
     */
    public ErrorResponse addDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    // Getters and setters
    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
