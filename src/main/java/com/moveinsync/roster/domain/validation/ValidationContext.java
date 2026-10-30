package com.moveinsync.roster.domain.validation;

import java.util.Set;

/** Tenant reference data needed to validate a record. */
public record ValidationContext(Set<String> officeCodes) {
}
