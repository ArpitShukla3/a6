package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.error.NotFoundException;
import com.moveinsync.roster.repository.EmployeeRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Reads always go to the database (no read cache), scoped by the caller's tenant. */
@Service
public class EmployeeQueryService {

    private final EmployeeRepository employees;

    public EmployeeQueryService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public EmployeeState get(TenantId tenant, String externalId) {
        return employees.findByExternalId(tenant, externalId)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
    }

    public List<EmployeeState> page(TenantId tenant, int page, int size) {
        return employees.page(tenant, size, page * size);
    }
}
