package com.codealpha.meetapp.common.exception;

/**
 * Encapsulates a validation error for a specific request field without echoing rejected values.
 */
public record FieldErrorItem(String field, String message) {
}
