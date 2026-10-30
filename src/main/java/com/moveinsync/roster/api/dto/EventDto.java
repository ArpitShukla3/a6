package com.moveinsync.roster.api.dto;

/** One HR event. Scalars are strings so bad values become per-record validation errors. */
public record EventDto(String type, String externalId, String sourceVersion, String occurredAt, HrFields hr) {

    public record HrFields(String name, String email, String phone, String managerExternalId,
                           String officeCode, String shiftCode,
                           // transport-owned: accepted only to detect and drop them
                           String pickupPointId, String specialAssistance) {
    }
}
