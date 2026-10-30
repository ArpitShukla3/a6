package com.moveinsync.roster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.repository.OfficeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/**
 * Hot data for validation: a tenant's office codes. Read on every ingested record.
 *
 * <p>Cache: Caffeine, expire-after-write TTL (default 5 min), size-bounded with W-TinyLFU eviction.
 * Staleness: a newly added office can be unknown to another node for at most the TTL (on this
 * node the entry is evicted when the office is added). The effect is a retryable OFFICE_UNKNOWN
 * rejection, never a wrong write. Version decisions and booking eligibility are never cached.
 */
@Service
public class OfficeDirectory {

    private final OfficeRepository repo;
    private final Cache<TenantId, Set<String>> cache;

    public OfficeDirectory(OfficeRepository repo, RosterProperties props, Ticker ticker, MeterRegistry registry) {
        this.repo = repo;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.cache().officeTtl())
                .maximumSize(props.cache().officeMaxTenants())
                .ticker(ticker)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "officeCodes");
    }

    public Set<String> codes(TenantId tenant) {
        return cache.get(tenant, t -> Set.copyOf(repo.codes(t)));
    }

    public boolean add(TenantId tenant, String officeCode) {
        boolean added = repo.insert(tenant, officeCode);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(tenant);
                }
            });
        } else {
            cache.invalidate(tenant);
        }
        return added;
    }
}
