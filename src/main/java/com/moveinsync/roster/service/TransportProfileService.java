package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.error.BadRequestException;
import com.moveinsync.roster.error.NotFoundException;
import com.moveinsync.roster.error.PreconditionFailedException;
import com.moveinsync.roster.error.PreconditionRequiredException;
import com.moveinsync.roster.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The only writer of transport-owned fields. Optimistic concurrency via admin_version
 * (HTTP If-Match), independent of the HR version, so neither side can make the other stale.
 */
@Service
public class TransportProfileService {

    private static final Logger log = LoggerFactory.getLogger(TransportProfileService.class);
    private static final Pattern PICKUP = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    public static final Set<String> ASSISTANCE_CODES =
            Set.of("NONE", "WHEELCHAIR", "ESCORT", "MEDICAL", "HEARING", "VISUAL", "OTHER");

    private final EmployeeRepository employees;
    private final OutboxWriter outbox;

    public TransportProfileService(EmployeeRepository employees, OutboxWriter outbox) {
        this.employees = employees;
        this.outbox = outbox;
    }

    @Transactional
    public EmployeeState update(TenantId tenant, String externalId, Long expectedAdminVersion,
                                String pickupPointId, String specialAssistance) {
        if (expectedAdminVersion == null) {
            throw new PreconditionRequiredException("IF_MATCH_REQUIRED", "If-Match header with the admin version is required");
        }
        if (pickupPointId != null && !PICKUP.matcher(pickupPointId).matches()) {
            throw new BadRequestException("PICKUP_POINT_INVALID", "pickupPointId is invalid");
        }
        if (specialAssistance != null && !ASSISTANCE_CODES.contains(specialAssistance)) {
            throw new BadRequestException("SPECIAL_ASSISTANCE_INVALID", "specialAssistance must be one of " + ASSISTANCE_CODES);
        }
        EmployeeState cur = employees.lockByExternalId(tenant, externalId)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        if (cur.adminVersion() != expectedAdminVersion) {
            throw new PreconditionFailedException("ADMIN_VERSION_MISMATCH", "Transport profile was changed by someone else");
        }
        EmployeeState next = cur.withTransport(pickupPointId, specialAssistance);
        employees.update(next);
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(cur.pickupPointId(), pickupPointId)) changed.add("pickupPointId");
        if (!Objects.equals(cur.specialAssistance(), specialAssistance)) changed.add("specialAssistance");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", next.externalId());
        body.put("adminVersion", next.adminVersion());
        body.put("changedFields", changed);
        outbox.write(tenant, next.employeeId(), "EMPLOYEE_TRANSPORT_UPDATED", body);
        log.info("transport profile updated employeeId={} adminVersion={}", next.employeeId(), next.adminVersion());
        return next;
    }
}
