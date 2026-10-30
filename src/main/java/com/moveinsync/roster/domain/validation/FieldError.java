package com.moveinsync.roster.domain.validation;

/** One validation problem. Carries the field name and a stable code, never the rejected value. */
public record FieldError(String field, String code) {
}
