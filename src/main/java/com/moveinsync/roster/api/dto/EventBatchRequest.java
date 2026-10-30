package com.moveinsync.roster.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Events are kept as raw JSON and mapped one by one, so a single malformed event (e.g. a
 * non-numeric version) is rejected on its own instead of failing the whole request.
 */
public record EventBatchRequest(@NotNull UUID batchId, @NotNull @Size(min = 1, max = 1000) List<JsonNode> events) {
}
