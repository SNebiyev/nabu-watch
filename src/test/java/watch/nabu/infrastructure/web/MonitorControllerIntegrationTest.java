package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitorControllerIntegrationTest extends AbstractControllerIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("PATCH /api/v1/monitors/{id} — sets manual target URL")
    void updateTargetUrl_setsManualMode() throws Exception {
        User user = seedUser("monitor-manual@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://example.com");
        Monitor monitor = monitorRepository.findByWebsiteId(website.getId()).stream()
                .filter(m -> m.getType() == Monitor.MonitorType.SSL)
                .findFirst()
                .orElseThrow();

        String request = objectMapper.writeValueAsString(
                new UpdateMonitorRequestDto("https://ssl-check.example.com"));

        mockMvc.perform(patch("/api/v1/monitors/" + monitor.getId())
                        .with(userToken("monitor-manual@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrlSource", is("MANUAL")))
                .andExpect(jsonPath("$.effectiveUrl", containsString("ssl-check.example.com")));
    }

    @Test
    @DisplayName("PATCH /api/v1/monitors/{id} — null target URL resets to AUTO mode")
    void updateTargetUrl_clearUrl_setsAutoMode() throws Exception {
        User user = seedUser("monitor-auto@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://example.com");
        Monitor monitor = monitorRepository.findByWebsiteId(website.getId()).stream()
                .filter(m -> m.getType() == Monitor.MonitorType.SSL)
                .findFirst()
                .orElseThrow();

        String request = objectMapper.writeValueAsString(
                new UpdateMonitorRequestDto(null));

        mockMvc.perform(patch("/api/v1/monitors/" + monitor.getId())
                        .with(userToken("monitor-auto@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrlSource", is("AUTO")));
    }

    @Test
    @DisplayName("PATCH /api/v1/monitors/{id} — non-existent monitor returns 404")
    void updateTargetUrl_notFound_returns404() throws Exception {
        seedUser("monitor-notfound@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new UpdateMonitorRequestDto("https://example.com"));

        mockMvc.perform(patch("/api/v1/monitors/" + UUID.randomUUID())
                        .with(userToken("monitor-notfound@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/monitors/{id}/scan — rescan returns 202")
    void rescan_returns202() throws Exception {
        User user = seedUser("monitor-rescan@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://example.com");
        Monitor monitor = monitorRepository.findByWebsiteId(website.getId()).stream()
                .filter(m -> m.getType() == Monitor.MonitorType.SSL)
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/api/v1/monitors/" + monitor.getId() + "/scan")
                        .with(userToken("monitor-rescan@test.com")))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("PATCH /api/v1/monitors/{id} — unauthenticated returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/monitors/" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record UpdateMonitorRequestDto(String targetUrl) {
    }
}
