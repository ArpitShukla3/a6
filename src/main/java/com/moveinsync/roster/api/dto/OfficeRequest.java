package com.moveinsync.roster.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OfficeRequest(@NotNull @Pattern(regexp = "[A-Z0-9-]{2,16}") String officeCode) {
}
