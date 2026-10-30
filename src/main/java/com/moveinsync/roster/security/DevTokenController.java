package com.moveinsync.roster.security;

import com.moveinsync.roster.error.BadRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Demo only: mints tokens. Disabled unless roster.security.dev-tokens-enabled=true. */
@RestController
@ConditionalOnProperty(name = "roster.security.dev-tokens-enabled", havingValue = "true")
public class DevTokenController {

    public record TokenRequest(String tenantId, List<String> roles, String subject, String employeeId) {
    }

    private final JwtEncoder encoder;

    public DevTokenController(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    @PostMapping("/dev/token")
    public Map<String, String> token(@RequestBody TokenRequest req) {
        if (req.tenantId() == null || req.roles() == null || req.roles().isEmpty()
                || !Roles.ALL.containsAll(req.roles())) {
            throw new BadRequestException("INVALID_TOKEN_REQUEST", "tenantId and roles from " + Roles.ALL + " are required");
        }
        try {
            UUID.fromString(req.tenantId());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_TOKEN_REQUEST", "tenantId must be a UUID");
        }
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("roster-sync-dev")
                .subject(req.subject() == null ? "dev-user" : req.subject())
                .issuedAt(now)
                .expiresAt(now.plus(12, ChronoUnit.HOURS))
                .claim(CurrentUser.TENANT_CLAIM, req.tenantId())
                .claim(CurrentUser.ROLES_CLAIM, req.roles());
        if (req.employeeId() != null) {
            claims.claim(CurrentUser.EMPLOYEE_CLAIM, req.employeeId());
        }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
        return Map.of("token", token);
    }
}
