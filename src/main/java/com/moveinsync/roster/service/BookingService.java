package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.error.BadRequestException;
import com.moveinsync.roster.error.ConflictException;
import com.moveinsync.roster.error.NotFoundException;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Minimal cab booking, enough to prove deactivation side effects. Creating a booking takes a
 * shared lock on the employee row, so it either completes before a deactivation (and is then
 * cancelled by it) or waits and sees the employee as inactive.
 */
@Service
public class BookingService {

    private final EmployeeRepository employees;
    private final BookingRepository bookings;
    private final Clock clock;

    public BookingService(EmployeeRepository employees, BookingRepository bookings, Clock clock) {
        this.employees = employees;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional
    public BookingRepository.Booking create(TenantId tenant, String externalId, Instant pickupAt) {
        if (pickupAt == null || !pickupAt.isAfter(clock.instant())) {
            throw new BadRequestException("PICKUP_NOT_IN_FUTURE", "pickupAt must be in the future");
        }
        EmployeeState e = employees.lockForShareByExternalId(tenant, externalId)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        if (!e.isActive()) {
            throw new ConflictException("EMPLOYEE_INACTIVE", "Employee is not active");
        }
        UUID seat = bookings.lockFreeSeat(tenant)
                .orElseThrow(() -> new ConflictException("NO_SEAT_AVAILABLE", "No free seat"));
        bookings.assignSeat(seat, e.employeeId());
        UUID bookingId = UUID.randomUUID();
        bookings.insert(tenant, bookingId, e.employeeId(), seat, pickupAt);
        return new BookingRepository.Booking(bookingId, e.employeeId(), seat, pickupAt, "CONFIRMED", null);
    }

    public List<BookingRepository.Booking> forEmployee(TenantId tenant, String externalId) {
        EmployeeState e = employees.findByExternalId(tenant, externalId)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return bookings.forEmployee(tenant, e.employeeId());
    }
}
