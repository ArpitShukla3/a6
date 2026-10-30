package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.BookingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs in the same transaction as the deactivation: cancels future bookings and frees their
 * seats. Doing it synchronously (bookings live in the same database) means a broker outage can
 * never leave an exited employee holding seats. Idempotent: a second run finds nothing to cancel.
 */
@Component
public class DeactivationSideEffects {

    private final BookingRepository bookings;
    private final OutboxWriter outbox;
    private final RosterMetrics metrics;
    private final Clock clock;

    public DeactivationSideEffects(BookingRepository bookings, OutboxWriter outbox, RosterMetrics metrics, Clock clock) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int onDeactivated(TenantId tenant, UUID employeeId) {
        List<BookingRepository.Cancelled> cancelled =
                bookings.cancelFuture(tenant, employeeId, clock.instant(), "EMPLOYEE_DEACTIVATED");
        bookings.freeSeats(cancelled.stream().map(BookingRepository.Cancelled::seatId).toList(), employeeId);
        for (BookingRepository.Cancelled c : cancelled) {
            outbox.write(tenant, employeeId, "BOOKING_CANCELLED",
                    Map.of("bookingId", c.bookingId(), "seatId", c.seatId(), "reason", "EMPLOYEE_DEACTIVATED"));
        }
        metrics.bookingsCancelled(cancelled.size());
        return cancelled.size();
    }
}
