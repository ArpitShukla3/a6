package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

public class PreconditionFailedException extends RosterException {
    public PreconditionFailedException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.PRECONDITION_FAILED;
    }
}
