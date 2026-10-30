package com.moveinsync.roster.api;

import com.moveinsync.roster.api.dto.EmployeeView;
import com.moveinsync.roster.api.dto.TransportPatch;
import com.moveinsync.roster.error.BadRequestException;
import com.moveinsync.roster.repository.BookingRepository;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.EmployeeQueryService;
import com.moveinsync.roster.service.TransportProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeQueryService query;
    private final TransportProfileService transport;
    private final BookingService bookings;
    private final CurrentUser user;

    public EmployeeController(EmployeeQueryService query, TransportProfileService transport, BookingService bookings,
                              CurrentUser user) {
        this.query = query;
        this.transport = transport;
        this.bookings = bookings;
        this.user = user;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','OPERATOR')")
    public List<EmployeeView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 500) {
            throw new BadRequestException("PAGING_INVALID", "page >= 0 and 1 <= size <= 500");
        }
        return query.page(user.tenant(), page, size).stream().map(EmployeeView::of).toList();
    }

    /** ETag = admin version; send it back as If-Match when editing transport fields. */
    @GetMapping("/{externalId}")
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','OPERATOR','EMPLOYEE')")
    public ResponseEntity<EmployeeView> get(@PathVariable String externalId) {
        user.requireSelfIfOnlyEmployee(externalId);
        var e = query.get(user.tenant(), externalId);
        return ResponseEntity.ok().eTag("\"" + e.adminVersion() + "\"").body(EmployeeView.of(e));
    }

    @PatchMapping("/{externalId}/transport")
    @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public ResponseEntity<EmployeeView> patchTransport(@PathVariable String externalId,
                                                       @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                       @RequestBody TransportPatch patch) {
        var e = transport.update(user.tenant(), externalId, parseEtag(ifMatch), patch.pickupPointId(), patch.specialAssistance());
        return ResponseEntity.ok().eTag("\"" + e.adminVersion() + "\"").body(EmployeeView.of(e));
    }

    @GetMapping("/{externalId}/bookings")
    @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','EMPLOYEE')")
    public List<BookingRepository.Booking> bookings(@PathVariable String externalId) {
        user.requireSelfIfOnlyEmployee(externalId);
        return bookings.forEmployee(user.tenant(), externalId);
    }

    private static Long parseEtag(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("IF_MATCH_INVALID", "If-Match must be the ETag from GET");
        }
    }
}
