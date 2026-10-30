package com.moveinsync.roster.domain.policy;

import com.moveinsync.roster.domain.event.Transition;

public record Decision(Outcome outcome, Transition transition) {

    public boolean shouldApply() {
        return outcome == Outcome.APPLIED;
    }
}
