package com.moveinsync.roster.fault;

public class InjectedFault extends RuntimeException {
    public InjectedFault(FaultPoint point) {
        super("injected fault at " + point);
    }
}
