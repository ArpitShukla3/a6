package com.moveinsync.roster.bench;

import com.moveinsync.roster.it.AbstractIT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP load on the real-time ingest API: concurrent clients posting batches of 100 events. */
@Tag("bench")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "roster.reconciliation.async=false", "roster.outbox.relay-enabled=true", "roster.buckets=64",
        "spring.profiles.active=plainlogs", "logging.level.com.moveinsync=WARN", "logging.level.access=WARN"})
class IngestLoadBenchmark extends AbstractIT {

    static final int CLIENTS = 16;
    static final int REQUESTS_PER_CLIENT = 40;
    static final int BATCH = 100;

    @LocalServerPort int port;
    @Autowired MeterRegistry registry;

    @Test
    void ingestThroughputAndLatency() throws Exception {
        String token = token(ACME, "hr-client", null, "HR_SYSTEM");
        HttpClient http = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(CLIENTS)).build();
        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);

        // warm-up
        post(http, token, body(9999, 0));

        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 0; c < CLIENTS; c++) {
            final int client = c;
            fs.add(pool.submit(() -> {
                for (int r = 0; r < REQUESTS_PER_CLIENT; r++) {
                    long s = System.nanoTime();
                    int code = post(http, token, body(client, r));
                    latenciesMs.add((System.nanoTime() - s) / 1_000_000);
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        double seconds = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();

        int events = CLIENTS * REQUESTS_PER_CLIENT * BATCH;
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        assertThat(count("SELECT count(*) FROM outbox_event")).isGreaterThan(0);

        BenchReport.write("""
                ## Real-time ingest API (HTTP, %d concurrent clients, batches of %d events)
                %s

                | Metric | Value |
                |---|---|
                | Events | %,d (half creates; half updates of those employees, 10%% of them stale) |
                | Throughput | %,.0f events/s (%,.0f requests/s) |
                | Request latency p50 | %d ms |
                | Request latency p95 | %d ms |
                | Request latency p99 | %d ms |
                | Server-side apply per event (lock + gate + write + outbox), p50 / p99 | %.2f / %.2f ms |
                """.formatted(CLIENTS, BATCH, BenchReport.machine(), events, events / seconds,
                CLIENTS * REQUESTS_PER_CLIENT / seconds, BenchReport.percentile(sorted, 0.50),
                BenchReport.percentile(sorted, 0.95), BenchReport.percentile(sorted, 0.99),
                applyPercentile(0.5), applyPercentile(0.99)));
    }

    private double applyPercentile(double p) {
        for (ValueAtPercentile v : registry.get("roster.ingest.apply").timer().takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - p) < 1e-9) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return Double.NaN;
    }

    /** Request r of a client: even requests create 100 employees, odd ones update them (newer, then a stale one). */
    private static String body(int client, int r) {
        StringBuilder sb = new StringBuilder("{\"batchId\":\"" + UUID.randomUUID() + "\",\"events\":[");
        int group = r / 2;
        for (int i = 0; i < BATCH; i++) {
            String ext = "L" + client + "-" + group + "-" + i;
            boolean create = r % 2 == 0;
            long version = create ? 1 : (i % 10 == 0 ? 1 : 2);   // 10% of updates are stale/duplicate
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"").append(create ? "CREATE" : "UPDATE").append("\",\"externalId\":\"").append(ext)
                    .append("\",\"sourceVersion\":").append(version).append(",\"occurredAt\":\"").append(Instant.now())
                    .append("\",\"hr\":{\"name\":\"Load ").append(i).append("\",\"email\":\"l").append(i)
                    .append("@acme.test\",\"phone\":\"+919876500000\",\"officeCode\":\"").append(create ? "BLR-01" : "BLR-02")
                    .append("\",\"shiftCode\":\"S1\"}}");
        }
        return sb.append("]}").toString();
    }

    private int post(HttpClient http, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/roster/events"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
