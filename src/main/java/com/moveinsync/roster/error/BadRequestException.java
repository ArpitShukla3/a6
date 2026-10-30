package com.moveinsync.roster.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends RosterException {
    public BadRequestException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
