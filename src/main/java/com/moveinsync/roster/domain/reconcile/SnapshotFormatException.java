package com.moveinsync.roster.domain.reconcile;

/** The file cannot be processed at all (e.g. no header). Carries a code, never file content. */
public class SnapshotFormatException extends RuntimeException {
    private final String code;

    public SnapshotFormatException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
