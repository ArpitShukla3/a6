package com.moveinsync.roster.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Drives the main flows (success, validation failure, conflict, stale, crash, reconciliation) with
 * recognisable personal data and asserts none of it appears in logs or error responses.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PiiLeakIT extends AbstractIT {

    private static final String NAME = "Zorawar Leakcheck";
    private static final String EMAIL = "zorawar.leakcheck@acme.test";
    private static final String PHONE_RAW = "98111 22333";
    private static final String PHONE = "9811122333";
    private static final String BAD_PHONE = "77-LEAK-77";

    @Autowired MockMvc mvc;

    @Test
    void noPersonalDataInLogsOrErrors(CapturedOutput output) throws Exception {
        String token = "Bearer " + token(ACME, "hr-client", null, "HR_SYSTEM");
        StringBuilder responses = new StringBuilder();
        for (String[] e : new String[][]{
                {"CREATE", "P1", "1", PHONE_RAW}, {"UPDATE", "P1", "2", PHONE_RAW}, {"UPDATE", "P1", "1", PHONE_RAW},
                {"UPDATE", "P1", "2", "+91 98111 00000"}, {"CREATE", "P2", "1", BAD_PHONE}}) {
            String body = "{\"batchId\":\"" + UUID.randomUUID() + "\",\"events\":[{\"type\":\"" + e[0] + "\",\"externalId\":\"" + e[1]
                    + "\",\"sourceVersion\":" + e[2] + ",\"occurredAt\":\"" + Instant.now() + "\",\"hr\":{\"name\":\"" + NAME
                    + "\",\"email\":\"" + EMAIL + "\",\"phone\":\"" + e[3] + "\",\"officeCode\":\"BLR-01\"}}]}";
            responses.append(mvc.perform(post("/api/v1/roster/events").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());
        }
        // malformed body containing PII
        responses.append(mvc.perform(post("/api/v1/roster/events").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":\"" + EMAIL + "\"}")).andReturn().getResponse().getContentAsString());
        // crash inside the ingest transaction
        faults.arm(com.moveinsync.roster.fault.FaultPoint.INGEST_AFTER_EMPLOYEE_WRITE);
        ingest(ACME, new com.moveinsync.roster.domain.validation.IncomingRecord(
                com.moveinsync.roster.domain.validation.RecordType.UPDATE, "P1", 9L, Instant.now(), NAME, EMAIL, PHONE_RAW,
                null, "BLR-01", null, java.util.Set.of()));
        drainOutbox();

        String logs = output.getAll();
        assertThat(logs).contains("P1");                    // IDs are logged...
        for (String pii : new String[]{NAME, "Zorawar", EMAIL, PHONE, "98111", BAD_PHONE}) {
            assertThat(logs).as("logs must not contain personal data").doesNotContain(pii);
            assertThat(responses.toString()).as("error responses must not contain personal data").doesNotContain(pii);
        }
        String outboxPayloads = String.join("", jdbc.queryForList("SELECT payload::text FROM outbox_event", String.class));
        assertThat(outboxPayloads).doesNotContain(NAME, EMAIL, PHONE);
    }
}
