package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BookingRepository {

    public record Booking(UUID bookingId, UUID employeeId, UUID seatId, Instant pickupAt, String status, String cancelReason) {
    }

    public record Cancelled(UUID bookingId, UUID seatId) {
    }

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> lockFreeSeat(TenantId tenant) {
        return jdbc.sql("""
                SELECT seat_id FROM seat WHERE tenant_id = :t AND employee_id IS NULL
                ORDER BY trip_code LIMIT 1 FOR UPDATE SKIP LOCKED""")
                .param("t", tenant.value()).query(UUID.class).optional();
    }

    public void assignSeat(UUID seatId, UUID employeeId) {
        jdbc.sql("UPDATE seat SET employee_id = :e WHERE seat_id = :s").param("e", employeeId).param("s", seatId).update();
    }

    public void insert(TenantId tenant, UUID bookingId, UUID employeeId, UUID seatId, Instant pickupAt) {
        jdbc.sql("""
                INSERT INTO booking (booking_id, tenant_id, employee_id, seat_id, pickup_at, status)
                VALUES (:b, :t, :e, :s, :p, 'CONFIRMED')""")
                .param("b", bookingId).param("t", tenant.value()).param("e", employeeId)
                .param("s", seatId).param("p", Sql.ts(pickupAt))
                .update();
    }

    /** Cancels all confirmed bookings with pickup after {@code now}. Idempotent. */
    public List<Cancelled> cancelFuture(TenantId tenant, UUID employeeId, Instant now, String reason) {
        return jdbc.sql("""
                UPDATE booking SET status = 'CANCELLED', cancel_reason = :r
                WHERE tenant_id = :t AND employee_id = :e AND status = 'CONFIRMED' AND pickup_at > :now
                RETURNING booking_id, seat_id""")
                .param("r", reason).param("t", tenant.value()).param("e", employeeId).param("now", Sql.ts(now))
                .query((rs, n) -> new Cancelled(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .list();
    }

    public void freeSeats(List<UUID> seatIds, UUID employeeId) {
        if (seatIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE seat SET employee_id = NULL WHERE seat_id IN (:s) AND employee_id = :e")
                .param("s", seatIds).param("e", employeeId).update();
    }

    public List<Booking> forEmployee(TenantId tenant, UUID employeeId) {
        return jdbc.sql("""
                SELECT booking_id, employee_id, seat_id, pickup_at, status, cancel_reason FROM booking
                WHERE tenant_id = :t AND employee_id = :e ORDER BY pickup_at""")
                .param("t", tenant.value()).param("e", employeeId)
                .query((rs, n) -> new Booking(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), Sql.instant(rs, "pickup_at"), rs.getString(5), rs.getString(6)))
                .list();
    }

    public int seatsHeldBy(UUID employeeId) {
        return jdbc.sql("SELECT count(*) FROM seat WHERE employee_id = :e").param("e", employeeId)
                .query(Integer.class).single();
    }
}
