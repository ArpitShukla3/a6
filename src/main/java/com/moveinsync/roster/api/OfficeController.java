package com.moveinsync.roster.api;

import com.moveinsync.roster.api.dto.OfficeRequest;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.OfficeDirectory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.TreeSet;

@RestController
public class OfficeController {

    private final OfficeDirectory offices;
    private final CurrentUser user;

    public OfficeController(OfficeDirectory offices, CurrentUser user) {
        this.offices = offices;
        this.user = user;
    }

    @GetMapping("/api/v1/offices")
    @PreAuthorize("hasAnyRole('OPERATOR','TRANSPORT_ADMIN','HR_SYSTEM')")
    public Set<String> list() {
        return new TreeSet<>(offices.codes(user.tenant()));
    }

    @PostMapping("/api/v1/offices")
    @PreAuthorize("hasRole('OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public Set<String> add(@Valid @RequestBody OfficeRequest req) {
        offices.add(user.tenant(), req.officeCode());
        return new TreeSet<>(offices.codes(user.tenant()));
    }
}
