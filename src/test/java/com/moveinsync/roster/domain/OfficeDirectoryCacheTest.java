package com.moveinsync.roster.domain;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.repository.OfficeRepository;
import com.moveinsync.roster.service.OfficeDirectory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.moveinsync.roster.domain.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves the staleness bound of the office-code cache with a fake clock. */
class OfficeDirectoryCacheTest {

    @Test
    void newOfficeAddedElsewhereIsVisibleWithinTtl() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        OfficeRepository repo = mock(OfficeRepository.class);
        when(repo.codes(TENANT)).thenReturn(Set.of("BLR-01")).thenReturn(Set.of("BLR-01", "NEW-01"));
        RosterProperties props = new RosterProperties(64, null, null,
                new RosterProperties.Cache(Duration.ofMinutes(5), 100), null);
        OfficeDirectory dir = new OfficeDirectory(repo, props, ticker, new SimpleMeterRegistry());

        assertThat(dir.codes(TENANT)).containsExactly("BLR-01");
        nanos.addAndGet(Duration.ofMinutes(4).toNanos());
        assertThat(dir.codes(TENANT)).doesNotContain("NEW-01");     // stale, within bound
        nanos.addAndGet(Duration.ofMinutes(1).plusSeconds(1).toNanos());
        assertThat(dir.codes(TENANT)).contains("NEW-01");           // bound reached -> refreshed
    }

    @Test
    void addingOfficeOnThisNodeEvictsImmediately() {
        OfficeRepository repo = mock(OfficeRepository.class);
        when(repo.codes(TENANT)).thenReturn(Set.of("BLR-01")).thenReturn(Set.of("BLR-01", "NEW-01"));
        RosterProperties props = new RosterProperties(64, null, null,
                new RosterProperties.Cache(Duration.ofMinutes(5), 100), null);
        OfficeDirectory dir = new OfficeDirectory(repo, props, Ticker.systemTicker(), new SimpleMeterRegistry());
        dir.codes(TENANT);
        dir.add(TENANT, "NEW-01");
        assertThat(dir.codes(TENANT)).contains("NEW-01");
    }
}
