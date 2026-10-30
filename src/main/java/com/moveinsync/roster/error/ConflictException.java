package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends RosterException {
    public ConflictException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
