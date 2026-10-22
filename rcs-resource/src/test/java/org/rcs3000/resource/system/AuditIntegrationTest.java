package org.rcs3000.resource.system;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.audit.AuditRecordedEvent;
import org.rcs3000.resource.IntegrationTestBase;
import org.rcs3000.resource.system.application.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class AuditIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1/audit-logs";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EventBus events;

    @Autowired
    private AuditLog audit;

    @Test
    void gatewayReportsAreStoredAndSearchable() throws Exception {
        Instant now = Instant.now();
        events.publish(AuditRecordedEvent.TYPE, new AuditRecordedEvent(now, "auditor1", "OPERATOR", "POST",
                "/api/dispatch/v1/orders", null, 200, 12, "10.0.0.5"));
        events.publish(AuditRecordedEvent.TYPE, new AuditRecordedEvent(now.plusSeconds(1), "auditor1", "OPERATOR", "DELETE",
                "/api/resource/v1/users/bob", null, 403, 3, "10.0.0.5"));

        await().atMost(Duration.ofSeconds(10)).until(() -> audit.find("auditor1", null, false, null, null, 10).size() == 2);

        mvc.perform(get(API).param("user", "auditor1"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].method").value("DELETE"));
        mvc.perform(get(API).param("user", "auditor1").param("failedOnly", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value(403));
        mvc.perform(get(API).param("user", "auditor1").param("path", "/api/dispatch"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].clientIp").value("10.0.0.5"));
        mvc.perform(get(API).param("user", "auditor1").param("from", now.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void oldEntriesArePurged() {
        audit.record(new AuditRecordedEvent(Instant.now().minus(Duration.ofDays(400)), "ancient", "ADMIN", "PUT",
                "/api/resource/v1/zones/Z", null, 200, 1, null));
        audit.purge();
        org.assertj.core.api.Assertions.assertThat(audit.find("ancient", null, false, null, null, 10)).isEmpty();
    }
}
