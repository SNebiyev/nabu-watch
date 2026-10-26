package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrganizationControllerIntegrationTest extends AbstractControllerIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/v1/organizations — lists user's memberships")
    void listOrgs_returnsMembershipList() throws Exception {
        User user = seedUser("list-orgs@test.com", User.Role.USER);

        mockMvc.perform(get("/api/v1/organizations")
                        .with(userToken("list-orgs@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].name", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/organizations — creates org and returns 201")
    void createOrg_returns201() throws Exception {
        seedUser("create-org@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new CreateOrgRequestDto("My New Org"));

        mockMvc.perform(post("/api/v1/organizations")
                        .with(userToken("create-org@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("My New Org")));
    }

    @Test
    @DisplayName("POST /api/v1/organizations — blank name returns 400")
    void createOrg_blankName_returns400() throws Exception {
        seedUser("invalid-org@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new CreateOrgRequestDto(""));

        mockMvc.perform(post("/api/v1/organizations")
                        .with(userToken("invalid-org@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/organizations/{id} — member can view")
    void getOrg_asMember_returns200() throws Exception {
        User user = seedUser("member@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();

        mockMvc.perform(get("/api/v1/organizations/" + org.getId())
                        .with(userToken("member@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(org.getId().toString())))
                .andExpect(jsonPath("$.name", is(org.getName())));
    }

    @Test
    @DisplayName("GET /api/v1/organizations/{id} — non-member returns 403")
    void getOrg_notMember_returns403() throws Exception {
        User user1 = seedUser("notmember1@test.com", User.Role.USER);
        User user2 = seedUser("notmember2@test.com", User.Role.USER);
        Organization org1 = organizationRepository.findById(user1.getCurrentOrganizationId()).orElseThrow();

        mockMvc.perform(get("/api/v1/organizations/" + org1.getId())
                        .with(userToken("notmember2@test.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/organizations/{id} — owner can update")
    void updateOrgName_asOwner_returns204() throws Exception {
        User user = seedUser("owner-update@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(user.getCurrentOrganizationId()).orElseThrow();

        String request = objectMapper.writeValueAsString(
                new UpdateOrgRequestDto("Updated Name"));

        mockMvc.perform(patch("/api/v1/organizations/" + org.getId())
                        .with(userToken("owner-update@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/v1/organizations/{id} — member cannot update")
    void updateOrgName_asMember_returns403() throws Exception {
        User owner = seedUser("owner@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(owner.getCurrentOrganizationId()).orElseThrow();
        seedOrgMember(org, "member-upd@test.com");

        String request = objectMapper.writeValueAsString(
                new UpdateOrgRequestDto("Updated Name"));

        mockMvc.perform(patch("/api/v1/organizations/" + org.getId())
                        .with(userToken("member-upd@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/organizations/{id}/members — lists members")
    void listMembers_returns200() throws Exception {
        User owner = seedUser("owner-members@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(owner.getCurrentOrganizationId()).orElseThrow();
        seedOrgMember(org, "member1@test.com");
        seedOrgMember(org, "member2@test.com");

        mockMvc.perform(get("/api/v1/organizations/" + org.getId() + "/members")
                        .with(userToken("owner-members@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(3)))) // owner + 2 members
                .andExpect(jsonPath("$[*].email", hasItems(
                        containsString("owner-members@test.com"),
                        containsString("member1@test.com"),
                        containsString("member2@test.com"))));
    }

    @Test
    @DisplayName("POST /api/v1/organizations/{id}/members — owner adds member")
    void addMember_asOwner_returns201() throws Exception {
        User owner = seedUser("owner-add@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(owner.getCurrentOrganizationId()).orElseThrow();
        seedUser("newmember@test.com", User.Role.USER);

        String request = objectMapper.writeValueAsString(
                new AddMemberRequestDto("newmember@test.com"));

        mockMvc.perform(post("/api/v1/organizations/" + org.getId() + "/members")
                        .with(userToken("owner-add@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DELETE /api/v1/organizations/{id}/members/{uid} — owner removes member")
    void removeMember_asOwner_returns204() throws Exception {
        User owner = seedUser("owner-remove@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(owner.getCurrentOrganizationId()).orElseThrow();
        User member = seedOrgMember(org, "to-remove@test.com");

        mockMvc.perform(delete("/api/v1/organizations/" + org.getId() + "/members/" + member.getId())
                        .with(userToken("owner-remove@test.com")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/v1/organizations/{id}/members/{uid} — invalid role returns 400")
    void changeMemberRole_invalidRole_returns400() throws Exception {
        User owner = seedUser("owner-role@test.com", User.Role.USER);
        Organization org = organizationRepository.findById(owner.getCurrentOrganizationId()).orElseThrow();
        User member = seedOrgMember(org, "member-role@test.com");

        String request = objectMapper.writeValueAsString(
                new ChangeMemberRoleRequestDto("INVALID_ROLE"));

        mockMvc.perform(patch("/api/v1/organizations/" + org.getId() + "/members/" + member.getId())
                        .with(userToken("owner-role@test.com"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/organizations — unauthenticated returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record CreateOrgRequestDto(String name) {
    }

    record UpdateOrgRequestDto(String name) {
    }

    record AddMemberRequestDto(String email) {
    }

    record ChangeMemberRoleRequestDto(String role) {
    }
}
