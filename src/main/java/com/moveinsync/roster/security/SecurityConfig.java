package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.observability.CorrelationIdFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT auth (HS256). Claims: sub, tenant_id, roles[], employee_id (for EMPLOYEE).
 * In production the tokens would come from the corporate IdP; a dev token endpoint exists
 * only when roster.security.dev-tokens-enabled=true.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecretKey jwtKey(RosterProperties props) {
        byte[] secret = props.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("roster.security.jwt-secret must be at least 32 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> tenantRequired = jwt -> jwt.getClaimAsString(CurrentUser.TENANT_CLAIM) != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "tenant claim missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), tenantRequired));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(CurrentUser.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/dev/token").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, ex) -> problem(res, json, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "A valid bearer token is required")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, json, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, json, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "Not allowed for this role")));
        http.addFilterAfter(new com.moveinsync.roster.observability.TenantMdcFilter(),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }

    static void problem(HttpServletResponse res, ObjectMapper json, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:roster:error:" + code));
        p.setProperty("code", code);
        p.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(res.getOutputStream(), p);
    }
}
