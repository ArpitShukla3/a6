package com.moveinsync.roster.api;

import com.moveinsync.roster.api.dto.EventBatchRequest;
import com.moveinsync.roster.repository.RejectionRepository;
import com.moveinsync.roster.security.CurrentUser;
import com.moveinsync.roster.service.BatchResult;
import com.moveinsync.roster.service.IngestBatchService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roster")
public class RosterIngestController {

    private final IngestBatchService ingest;
    private final RecordMapper mapper;
    private final RejectionRepository rejections;
    private final CurrentUser user;

    public RosterIngestController(IngestBatchService ingest, RecordMapper mapper, RejectionRepository rejections, CurrentUser user) {
        this.ingest = ingest;
        this.mapper = mapper;
        this.rejections = rejections;
        this.user = user;
    }

    /** Always 200 with a per-record report; one bad record never fails the batch. */
    @PostMapping("/events")
    @PreAuthorize("hasRole('HR_SYSTEM')")
    public BatchResult events(@Valid @RequestBody EventBatchRequest req) {
        return ingest.ingest(user.tenant(), req.batchId(), req.events().stream().map(mapper::map).toList());
    }

    /** Stored error report for a batch or a reconciliation run. */
    @GetMapping("/batches/{refId}/errors")
    @PreAuthorize("hasAnyRole('HR_SYSTEM','OPERATOR')")
    public List<RejectionRepository.Rejection> errors(@PathVariable UUID refId) {
        return rejections.list(user.tenant(), refId, 1000);
    }
}
