package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDashboardControllerIntegrationTest extends AbstractControllerIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    // ── Happy path (admin only) ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/admin/stats — admin gets stats")
    void getStats_returnsStatsJson() throws Exception {
        seedUser("admin-stats@test.com", User.Role.ADMIN);

        mockMvc.perform(get("/api/v1/admin/stats")
                        .with(adminToken("admin-stats@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers", isA(Integer.class)))
                .andExpect(jsonPath("$.totalWebsites", isA(Integer.class)))
                .andExpect(jsonPath("$.totalMonitors", isA(Integer.class)))
                .andExpect(jsonPath("$.activeScansLastHour", isA(Integer.class)))
                .andExpect(jsonPath("$.systemFailureRate", isA(Number.class)));
    }

    @Test
    @DisplayName("GET /api/v1/admin/users — admin lists users")
    void listUsers_returnsPage() throws Exception {
        seedUser("admin-users@test.com", User.Role.ADMIN);
        seedUser("user1@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(adminToken("admin-users@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.content[*].email", hasItems(
                        containsString("admin-users@test.com"),
                        containsString("user1@test.com"))));
    }

    @Test
    @DisplayName("GET /api/v1/admin/users/{id}/websites — empty list for new user")
    void getUserWebsites_returnsEmptyList() throws Exception {
        User admin = seedUser("admin-user-sites@test.com", User.Role.ADMIN);
        User user = seedUser("user-sites@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/admin/users/" + user.getId() + "/websites")
                        .with(adminToken("admin-user-sites@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/admin/users/{id}/scans — empty list for new user")
    void getUserScans_returnsEmptyList() throws Exception {
        seedUser("admin-user-scans@test.com", User.Role.ADMIN);
        User user = seedUser("user-scans@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/admin/users/" + user.getId() + "/scans")
                        .with(adminToken("admin-user-scans@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{id}/status — suspends user")
    void updateUserStatus_suspend_returns204() throws Exception {
        seedUser("admin-suspend@test.com", User.Role.ADMIN);
        User user = seedUser("user-suspend@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new UpdateUserStatusRequestDto("SUSPENDED"));

        mockMvc.perform(patch("/api/v1/admin/users/" + user.getId() + "/status")
                        .with(adminToken("admin-suspend@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{id}/status — invalid status returns 400")
    void updateUserStatus_invalidStatus_returns400() throws Exception {
        seedUser("admin-invalid-status@test.com", User.Role.ADMIN);
        User user = seedUser("user-invalid-status@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new UpdateUserStatusRequestDto("INVALID_STATUS"));

        mockMvc.perform(patch("/api/v1/admin/users/" + user.getId() + "/status")
                        .with(adminToken("admin-invalid-status@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/admin/scans/global — returns scan feed")
    void globalScanFeed_returnsEmptyList() throws Exception {
        seedUser("admin-feed@test.com", User.Role.ADMIN);

        mockMvc.perform(get("/api/v1/admin/scans/global")
                        .with(adminToken("admin-feed@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0))); // no scans yet
    }

    @Test
    @DisplayName("GET /api/v1/admin/organizations — lists orgs")
    void listOrganizations_returnsPage() throws Exception {
        seedUser("admin-orgs@test.com", User.Role.ADMIN);
        seedUser("user-org@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/admin/organizations")
                        .with(adminToken("admin-orgs@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.page", is(0)));
    }

    @Test
    @DisplayName("GET /api/v1/admin/organizations/{id}/members — lists members")
    void getOrgMembers_returnsList() throws Exception {
        User admin = seedUser("admin-org-members@test.com", User.Role.ADMIN);
        User user = seedUser("user-org-members@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();

        mockMvc.perform(get("/api/v1/admin/organizations/" + org.getId() + "/members")
                        .with(adminToken("admin-org-members@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[*].email", hasItems(
                        containsString("user-org-members@test.com"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/organizations/{id}/status — disables org")
    void updateOrgStatus_disable_returns204() throws Exception {
        seedUser("admin-org-disable@test.com", User.Role.ADMIN);
        User user = seedUser("user-org-disable@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();

        String request = objectMapper.writeValueAsString(
                new UpdateOrgStatusRequestDto(false));

        mockMvc.perform(patch("/api/v1/admin/organizations/" + org.getId() + "/status")
                        .with(adminToken("admin-org-disable@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isNoContent());
    }

    // ── Access control (admin-only endpoints) ─────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/admin/stats — regular user returns 403")
    void adminEndpoint_asUser_returns403() throws Exception {
        seedUser("regular-user@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/admin/stats")
                        .with(userToken("regular-user@test.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/admin/stats — no auth returns 401")
    void adminEndpoint_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats"))
                .andExpect(status().isUnauthorized());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record UpdateUserStatusRequestDto(String status) {
    }

    record UpdateOrgStatusRequestDto(boolean enabled) {
    }
}
