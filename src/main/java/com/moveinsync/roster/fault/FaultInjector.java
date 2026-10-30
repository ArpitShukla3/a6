package com.moveinsync.roster.fault;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test hook for failure injection. Nothing arms it in production, where {@link #hit} is a map
 * lookup and nothing else.
 */
@Component
public class FaultInjector {

    private final Map<FaultPoint, AtomicInteger> armed = new ConcurrentHashMap<>();

    /** Throw at the point after it has been passed {@code passes} more times (0 = next time). */
    public void arm(FaultPoint point, int passes) {
        armed.put(point, new AtomicInteger(passes));
    }

    public void arm(FaultPoint point) {
        arm(point, 0);
    }

    public void disarmAll() {
        armed.clear();
    }

    public void hit(FaultPoint point) {
        AtomicInteger remaining = armed.get(point);
        if (remaining == null) {
            return;
        }
        if (remaining.getAndDecrement() <= 0) {
            armed.remove(point);
            throw new InjectedFault(point);
        }
    }
}
