package com.moveinsync.roster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "roster")
public record RosterProperties(
        @DefaultValue("256") int buckets,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue Outbox outbox,
        @DefaultValue Cache cache,
        @DefaultValue Security security) {

    public record Reconciliation(
            /** Global default; a tenant can override it (tenant.brake_missing_ratio). */
            @DefaultValue("0.30") double brakeMissingRatio,
            @DefaultValue("0.05") double brakeRejectedRatio,
            @DefaultValue("/tmp/roster-data") String dataDir,
            @DefaultValue("true") boolean async) {
    }

    public record Outbox(
            @DefaultValue("log") String publisher,
            @DefaultValue("roster.employee.changed") String topic,
            @DefaultValue("500") int batchSize,
            @DefaultValue("30s") Duration maxBackoff,
            @DefaultValue("5s") Duration publishTimeout,
            @DefaultValue("true") boolean relayEnabled) {
    }

    public record Cache(
            @DefaultValue("5m") Duration officeTtl,
            @DefaultValue("10000") long officeMaxTenants) {
    }

    public record Security(
            @DefaultValue("change-me-change-me-change-me-change-me-0123456789") String jwtSecret,
            @DefaultValue("false") boolean devTokensEnabled) {
    }
}
