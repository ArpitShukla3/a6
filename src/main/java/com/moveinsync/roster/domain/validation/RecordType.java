package com.moveinsync.roster.domain.validation;

public enum RecordType {
    CREATE, UPDATE, DEACTIVATE, SNAPSHOT;

    public boolean carriesHrFields() {
        return this != DEACTIVATE;
    }

    public static RecordType parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            RecordType t = valueOf(s.trim().toUpperCase());
            return t == SNAPSHOT ? null : t; // SNAPSHOT is internal, not accepted from the API
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
