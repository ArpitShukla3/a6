package com.moveinsync.roster.api;

import com.moveinsync.roster.api.dto.BookingRequest;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookings;
    private final CurrentUser user;

    public BookingController(BookingService bookings, CurrentUser user) {
        this.bookings = bookings;
        this.user = user;
    }

    @PostMapping("/api/v1/bookings")
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingRepository.Booking create(@Valid @RequestBody BookingRequest req) {
        return bookings.create(user.tenant(), req.externalId(), req.pickupAt());
    }
}
