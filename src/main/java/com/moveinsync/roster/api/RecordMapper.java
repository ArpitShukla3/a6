package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.api.dto.EventDto;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/** JSON event &rarr; IncomingRecord. Unparseable values become null and fail validation for that record only. */
@Component
public class RecordMapper {

    private final ObjectMapper json;

    public RecordMapper(ObjectMapper json) {
        this.json = json;
    }

    /** @return null if the event is not even a JSON object of the expected shape */
    public IncomingRecord map(JsonNode node) {
        EventDto dto;
        try {
            dto = json.treeToValue(node, EventDto.class);
        } catch (Exception e) {
            return null;
        }
        if (dto == null) {
            return null;
        }
        EventDto.HrFields hr = dto.hr() == null
                ? new EventDto.HrFields(null, null, null, null, null, null, null, null) : dto.hr();
        Set<String> transport = new HashSet<>();
        if (hr.pickupPointId() != null) transport.add("pickupPointId");
        if (hr.specialAssistance() != null) transport.add("specialAssistance");
        return new IncomingRecord(RecordType.parse(dto.type()), trim(dto.externalId()), parseLong(dto.sourceVersion()),
                parseInstant(dto.occurredAt()), hr.name(), hr.email(), hr.phone(), hr.managerExternalId(),
                hr.officeCode(), hr.shiftCode(), transport);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L; // reported as VERSION_INVALID
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null) return null;
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null; // reported as REQUIRED on occurredAt
        }
    }
}
