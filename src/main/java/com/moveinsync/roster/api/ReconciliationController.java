package com.moveinsync.roster.api;

import com.moveinsync.roster.api.dto.RunView;
import com.moveinsync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationRunRepository runs;
    private final CurrentUser user;

    public ReconciliationController(ReconciliationService service, ReconciliationRunRepository runs, CurrentUser user) {
        this.service = service;
        this.runs = runs;
        this.user = user;
    }

    /** Raw CSV body (curl --data-binary @file), streamed to disk. 202: processing is asynchronous. */
    @PostMapping(path = "/api/v1/roster/snapshots", consumes = {"text/csv", "text/plain", "application/octet-stream"})
    @PreAuthorize("hasRole('HR_SYSTEM')")
    public ResponseEntity<RunView> upload(HttpServletRequest request) throws IOException {
        var run = service.submit(user.tenant(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunView.of(run, List.of()));
    }

    @GetMapping("/api/v1/reconciliation/runs")
    @PreAuthorize("hasAnyRole('OPERATOR','HR_SYSTEM')")
    public List<RunView> list() {
        return runs.list(user.tenant(), 50).stream().map(r -> RunView.of(r, List.of())).toList();
    }

    @GetMapping("/api/v1/reconciliation/runs/{runId}")
    @PreAuthorize("hasAnyRole('OPERATOR','HR_SYSTEM')")
    public RunView get(@PathVariable UUID runId) {
        var run = service.find(user.tenant(), runId);
        return RunView.of(run, service.sampleMissing(runId));
    }

    @PostMapping("/api/v1/reconciliation/runs/{runId}/approve")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView approve(@PathVariable UUID runId) {
        return RunView.of(service.approve(user.tenant(), runId, user.subject()), List.of());
    }

    @PostMapping("/api/v1/reconciliation/runs/{runId}/reject")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView reject(@PathVariable UUID runId) {
        return RunView.of(service.reject(user.tenant(), runId, user.subject()), List.of());
    }

    @PostMapping("/api/v1/reconciliation/runs/{runId}/retry")
    @PreAuthorize("hasRole('OPERATOR')")
    public RunView retry(@PathVariable UUID runId) {
        return RunView.of(service.retry(user.tenant(), runId, user.subject()), List.of());
    }
}
