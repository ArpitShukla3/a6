package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

public class PreconditionRequiredException extends RosterException {
    public PreconditionRequiredException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.PRECONDITION_REQUIRED;
    }
}
