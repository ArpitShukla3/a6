package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.error.NotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * The caller, from the verified JWT. The tenant always comes from the token, never from the
 * path or body, so a caller can only ever address their own tenant's data.
 */
@Component
public class CurrentUser {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String EMPLOYEE_CLAIM = "employee_id";
    public static final String ROLES_CLAIM = "roles";

    public TenantId tenant() {
        try {
            return TenantId.of(jwt().getClaimAsString(TENANT_CLAIM));
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("tenant claim missing");
        }
    }

    public String subject() {
        return jwt().getSubject();
    }

    public boolean hasRole(String role) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_" + role));
    }

    /**
     * Employees may only see themselves. A mismatch answers 404, like a record of another
     * tenant, so existence of other employees is not revealed.
     */
    public void requireSelfIfOnlyEmployee(String externalId) {
        boolean privileged = hasRole(Roles.TRANSPORT_ADMIN) || hasRole(Roles.OPERATOR);
        if (!privileged && !externalId.equals(jwt().getClaimAsString(EMPLOYEE_CLAIM))) {
            throw new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found");
        }
    }

    private Jwt jwt() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new InvalidBearerTokenException("no token");
    }
}
