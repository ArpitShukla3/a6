package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

/**
 * Base of all expected errors. The message is a fixed, safe text: it is returned to clients
 * and logged, so it must never contain personal data or input values.
 */
public abstract class RosterException extends RuntimeException {

    private final String code;

    protected RosterException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public abstract HttpStatus status();
}
