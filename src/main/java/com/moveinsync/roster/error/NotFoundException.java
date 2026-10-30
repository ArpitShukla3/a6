package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends RosterException {
    public NotFoundException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
