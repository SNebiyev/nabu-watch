package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebsiteControllerIntegrationTest extends AbstractControllerIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/v1/websites — returns empty page when no websites")
    void listWebsites_returnsEmptyPage() throws Exception {
        User user = seedUser("list@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/websites")
                        .with(userToken("list@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.stats.totalSites", is(0)));
    }

    @Test
    @DisplayName("POST /api/v1/websites — creates website and returns 201")
    void createWebsite_returns201() throws Exception {
        seedUser("create@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new CreateWebsiteRequestDto("https://example.com", "DE", true));

        mockMvc.perform(post("/api/v1/websites")
                        .with(userToken("create@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.url", containsString("example.com")))
                .andExpect(jsonPath("$.updated", is(false)));
    }

    @Test
    @DisplayName("POST /api/v1/websites — missing ownership attestation returns 400")
    void createWebsite_withoutAttestation_returns400() throws Exception {
        seedUser("noattest@test.com", User.Role.USER);

        mockMvc.perform(post("/api/v1/websites")
                        .with(userToken("noattest@test.com"))
                        .contentType("application/json")
                        .content("{\"url\":\"https://example.com\",\"region\":\"DE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/websites")
                        .with(userToken("noattest@test.com"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateWebsiteRequestDto("https://example.com", "DE", false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/websites — blank URL returns 400")
    void createWebsite_invalidUrl_returns400() throws Exception {
        seedUser("invalid@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new CreateWebsiteRequestDto("", "DE", true));

        mockMvc.perform(post("/api/v1/websites")
                        .with(userToken("invalid@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/websites/{id} — returns website details")
    void getWebsiteById_returns200() throws Exception {
        User user = seedUser("get@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://example.com");

        mockMvc.perform(get("/api/v1/websites/" + website.getId())
                        .with(userToken("get@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(website.getId().toString())))
                .andExpect(jsonPath("$.url", containsString("example.com")));
    }

    @Test
    @DisplayName("GET /api/v1/websites/{id} — not found returns 404")
    void getWebsiteById_notFound_returns404() throws Exception {
        seedUser("notfound@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/websites/" + UUID.randomUUID())
                        .with(userToken("notfound@test.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/websites/{id}/health — returns health snapshot")
    void healthView_returns200() throws Exception {
        User user = seedUser("health@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://health.example.com");

        mockMvc.perform(get("/api/v1/websites/" + website.getId() + "/health")
                        .with(userToken("health@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus", notNullValue()))
                .andExpect(jsonPath("$.monitorsByStatus", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/v1/websites/{id}/latest-scans — returns scan list")
    void latestScans_returns200() throws Exception {
        User user = seedUser("scans@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://scans.example.com");

        mockMvc.perform(get("/api/v1/websites/" + website.getId() + "/latest-scans")
                        .with(userToken("scans@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(java.util.ArrayList.class)));
    }

    @Test
    @DisplayName("GET /api/v1/websites/{id}/scan-history — returns batches")
    void scanHistory_returns200() throws Exception {
        User user = seedUser("history@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://history.example.com");

        mockMvc.perform(get("/api/v1/websites/" + website.getId() + "/scan-history")
                        .with(userToken("history@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(java.util.ArrayList.class)));
    }

    @Test
    @DisplayName("PUT /api/v1/websites/{id}/monitors — syncs monitors")
    void updateMonitors_returns200() throws Exception {
        User user = seedUser("monitors@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://monitors.example.com");

        String request = objectMapper.writeValueAsString(
                new UpdateMonitorsRequestDto(java.util.List.of("SSL", "IMPRESSUM")));

        mockMvc.perform(put("/api/v1/websites/" + website.getId() + "/monitors")
                        .with(userToken("monitors@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activated", isA(java.util.ArrayList.class)));
    }

    @Test
    @DisplayName("DELETE /api/v1/websites/{id} — hard-deletes website")
    void deleteWebsite_selfOwned_returns204() throws Exception {
        User user = seedUser("delete@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();
        Website website = seedWebsite(org, user, "https://delete.example.com");

        mockMvc.perform(delete("/api/v1/websites/" + website.getId())
                        .with(userToken("delete@test.com")))
                .andExpect(status().isNoContent());

        assertThat(websiteRepository.findById(website.getId())).isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/websites — unauthenticated returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/websites"))
                .andExpect(status().isUnauthorized());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record CreateWebsiteRequestDto(String url, String region, boolean ownershipAttestation) {
    }

    record UpdateMonitorsRequestDto(java.util.List<String> enabledSubTypes) {
    }
}
