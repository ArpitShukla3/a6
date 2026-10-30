package com.moveinsync.roster.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class ApiSecurityIT extends AbstractIT {

    @Autowired MockMvc mvc;


    private String bearer(Object tenant, String... roles) {
        return "Bearer " + token((com.moveinsync.roster.domain.model.TenantId) tenant, "user-1", null, roles);
    }

    private static String batch(String eventsJson) {
        return "{\"batchId\":\"" + UUID.randomUUID() + "\",\"events\":[" + eventsJson + "]}";
    }

    private static String event(String type, String ext, Object version, String office, String phone) {
        return "{\"type\":\"" + type + "\",\"externalId\":\"" + ext + "\",\"sourceVersion\":" + version
                + ",\"occurredAt\":\"" + Instant.now().minusSeconds(5) + "\",\"hr\":{\"name\":\"Priya Sharma\","
                + "\"email\":\"priya.sharma@acme.test\",\"phone\":\"" + phone + "\",\"officeCode\":\"" + office + "\",\"shiftCode\":\"S1\"}}";
    }

    @Test
    void batchEndpointReportsPerRecord_andMalformedEventDoesNotFailBatch() throws Exception {
        String body = batch(event("CREATE", "E1", 1, "BLR-01", "+919876543210") + ","
                + event("CREATE", "E2", "\"abc\"", "BLR-01", "+919876543210") + ","
                + "\"not-an-object\"" + ","
                + event("CREATE", "E3", 1, "MARS-01", "+919876543210"));
        mvc.perform(post("/api/v1/roster/events").header("Authorization", bearer(ACME, "HR_SYSTEM"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.APPLIED").value(1))
                .andExpect(jsonPath("$.summary.REJECTED").value(3))
                .andExpect(jsonPath("$.results[1].errors[0].code").value("VERSION_INVALID"))
                .andExpect(jsonPath("$.results[2].errors[0].code").value("MALFORMED_ROW"))
                .andExpect(jsonPath("$.results[3].errors[0].code").value("OFFICE_UNKNOWN"))
                .andExpect(content().string(not(containsString("MARS-01"))));
    }

    @Test
    void noTokenIs401_wrongRoleIs403_asProblemJson() throws Exception {
        mvc.perform(get("/api/v1/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/roster/events").header("Authorization", bearer(ACME, "TRANSPORT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(batch(event("CREATE", "E1", 1, "BLR-01", ""))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(patch("/api/v1/employees/E1/transport").header("Authorization", bearer(ACME, "HR_SYSTEM"))
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"pickupPointId\":\"PP-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reconciliation/runs/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", bearer(ACME, "HR_SYSTEM", "TRANSPORT_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedOrTenantlessTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/employees").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oneTenantCanNeverReadAnothersEmployees() throws Exception {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        mvc.perform(get("/api/v1/employees/E1").header("Authorization", bearer(ACME, "TRANSPORT_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/employees/E1").header("Authorization", bearer(GLOBEX, "TRANSPORT_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"));
        mvc.perform(get("/api/v1/employees").header("Authorization", bearer(GLOBEX, "OPERATOR")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void employeeSeesOnlyThemselves() throws Exception {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"), create("E2", 1, "BLR-01", "S1"));
        String me = "Bearer " + token(ACME, "emp", "E1", "EMPLOYEE");
        mvc.perform(get("/api/v1/employees/E1").header("Authorization", me)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/employees/E2").header("Authorization", me)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/employees").header("Authorization", me)).andExpect(status().isForbidden());
    }

    @Test
    void transportPatchUsesOptimisticLocking() throws Exception {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        String admin = bearer(ACME, "TRANSPORT_ADMIN");
        mvc.perform(patch("/api/v1/employees/E1/transport").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPointId\":\"PP-1\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/employees/E1/transport").header("Authorization", admin).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPointId\":\"PP-1\",\"specialAssistance\":\"WHEELCHAIR\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.pickupPointId").value("PP-1"));
        mvc.perform(patch("/api/v1/employees/E1/transport").header("Authorization", admin).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPointId\":\"PP-2\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("ADMIN_VERSION_MISMATCH"));
    }

    @Test
    void snapshotUploadAndOperatorFlowOverHttp() throws Exception {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"), create("E2", 1, "BLR-01", "S1"));
        String csv = "#ROSTER_EXPORT,as_of=" + Instant.now() + ",expected_rows=2\n"
                + "external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code\n"
                + "E1,1,Name E1,e1@acme.test,+919876500000,,BLR-01,S1\n";       // cut: no E2, no trailer
        var res = mvc.perform(post("/api/v1/roster/snapshots").header("Authorization", bearer(ACME, "HR_SYSTEM"))
                        .contentType("text/csv").content(csv))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = res.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/reconciliation/runs/" + runId).header("Authorization", bearer(ACME, "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.appearsMissing").value(1))
                .andExpect(jsonPath("$.fileAppearsShortBy").value(1))
                .andExpect(jsonPath("$.sampleMissingEmployeeIds[0]").value("E2"));
        mvc.perform(get("/api/v1/reconciliation/runs/" + runId).header("Authorization", bearer(GLOBEX, "OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reconciliation/runs/" + runId + "/reject").header("Authorization", bearer(ACME, "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void invalidJsonGivesProblemWithoutEchoingInput() throws Exception {
        mvc.perform(post("/api/v1/roster/events").header("Authorization", bearer(ACME, "HR_SYSTEM"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":\"secret-value-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(content().string(not(containsString("secret-value-123"))));
    }

    @Test
    void metricsEndpointExposesOnCallCounters() throws Exception {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("roster_ingest_events_total")))
                .andExpect(content().string(containsString("roster_outbox_pending")))
                .andExpect(content().string(containsString("roster_outbox_oldest_age_seconds")))
                .andExpect(content().string(containsString("roster_reconciliation_held_runs")));
    }
}
