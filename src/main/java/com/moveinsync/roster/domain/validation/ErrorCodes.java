package com.moveinsync.roster.domain.validation;

public final class ErrorCodes {
    public static final String REQUIRED = "REQUIRED";
    public static final String TYPE_INVALID = "TYPE_INVALID";
    public static final String EXTERNAL_ID_INVALID = "EXTERNAL_ID_INVALID";
    public static final String VERSION_INVALID = "VERSION_INVALID";
    public static final String PHONE_INVALID = "PHONE_INVALID";
    public static final String EMAIL_INVALID = "EMAIL_INVALID";
    public static final String OFFICE_UNKNOWN = "OFFICE_UNKNOWN";
    public static final String SHIFT_CODE_INVALID = "SHIFT_CODE_INVALID";
    public static final String MANAGER_INVALID = "MANAGER_INVALID";
    public static final String NAME_INVALID = "NAME_INVALID";
    public static final String DUPLICATE_ROW = "DUPLICATE_ROW";
    public static final String MALFORMED_ROW = "MALFORMED_ROW";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String FIELD_OWNED_BY_TRANSPORT = "FIELD_OWNED_BY_TRANSPORT";

    private ErrorCodes() {
    }
}
