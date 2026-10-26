package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.notification.OrgNotificationSettingsService;
import watch.nabu.application.organization.OrganizationService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Organization management endpoints.
 *
 * <p>All mutating operations (create, update, member management) require
 * OWNER role within the target organization.  Read operations require
 * membership.
 */
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrgNotificationSettingsService orgNotificationSettingsService;
    private final SecurityContextService securityContext;

    // ── DTOs (inner records) ──────────────────────────────────────────────────

    /**
     * GET /api/v1/organizations — list all organizations the caller belongs to.
     */
    @GetMapping
    public List<OrgResponse> list(@AuthenticationPrincipal UserDetails principal) {
        User user = securityContext.resolveUser(principal.getUsername());
        return organizationService.listForUser(user.getId()).stream()
                .map(org -> new OrgResponse(org.getId(), org.getName(), org.getCreatedAt()))
                .toList();
    }

    /**
     * POST /api/v1/organizations — create a new organization (caller becomes OWNER).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrgResponse create(
            @RequestBody @Valid CreateOrgRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        User user = securityContext.resolveUser(principal.getUsername());
        Organization org = organizationService.create(request.name(), user.getId());
        return new OrgResponse(org.getId(), org.getName(), org.getCreatedAt());
    }

    /**
     * GET /api/v1/organizations/{id} — org details (requires membership).
     */
    @GetMapping("/{id}")
    public OrgResponse get(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        requireMembership(id, principal);
        Organization org = organizationService.findById(id);
        return new OrgResponse(org.getId(), org.getName(), org.getCreatedAt());
    }

    /**
     * PATCH /api/v1/organizations/{id} — update org name (owner only).
     */
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateOrgRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        organizationService.updateName(id, request.name());
    }

    /**
     * GET /api/v1/organizations/{id}/members — list members (requires membership).
     */
    @GetMapping("/{id}/members")
    public List<MemberResponse> listMembers(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        requireMembership(id, principal);
        return organizationService.listMembers(id).stream()
                .map(m -> new MemberResponse(
                        m.getUser().getId(),
                        m.getUser().getEmail(),
                        m.getUser().getFullName(),
                        m.getRole().name(),
                        m.getJoinedAt()))
                .toList();
    }

    /**
     * POST /api/v1/organizations/{id}/members — add member by email (owner only).
     */
    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(
            @PathVariable UUID id,
            @RequestBody @Valid AddMemberRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        organizationService.addMember(id, request.email());
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * DELETE /api/v1/organizations/{id}/members/{uid} — remove member (owner only).
     */
    @DeleteMapping("/{id}/members/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable UUID id,
            @PathVariable UUID uid,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        organizationService.removeMember(id, uid);
    }

    /**
     * PATCH /api/v1/organizations/{id}/members/{uid} — change role (owner only).
     */
    @PatchMapping("/{id}/members/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeMemberRole(
            @PathVariable UUID id,
            @PathVariable UUID uid,
            @RequestBody @Valid ChangeMemberRoleRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        OrganizationMember.Role role;
        try {
            role = OrganizationMember.Role.valueOf(request.role().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid role: " + request.role() + ". Must be OWNER or MEMBER.");
        }
        organizationService.changeMemberRole(id, uid, role);
    }

    /**
     * GET /api/v1/organizations/{id}/notification-settings — list all members' notification
     * settings (owner only). Members see their own setting via the user-level endpoint.
     */
    @GetMapping("/{id}/notification-settings")
    public List<MemberNotificationSettingsResponse> listNotificationSettings(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        return orgNotificationSettingsService.listForOrganization(id).stream()
                .map(s -> new MemberNotificationSettingsResponse(
                        s.getUser().getId(),
                        s.isAlertsEnabled(),
                        s.isDigestEnabled()))
                .toList();
    }

    /**
     * PUT /api/v1/organizations/{id}/members/{uid}/notification-settings — owner toggles
     * a member's org-level notification flags. Partial updates supported (null = unchanged).
     */
    @PutMapping("/{id}/members/{uid}/notification-settings")
    public MemberNotificationSettingsResponse updateMemberNotificationSettings(
            @PathVariable UUID id,
            @PathVariable UUID uid,
            @RequestBody UpdateMemberNotificationSettingsRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        requireOwnership(id, principal);
        var setting = orgNotificationSettingsService.updateByOwner(
                id, uid, request.alertsEnabled(), request.digestEnabled());
        return new MemberNotificationSettingsResponse(
                uid, setting.isAlertsEnabled(), setting.isDigestEnabled());
    }

    private void requireMembership(UUID orgId, UserDetails principal) {
        UUID userId = securityContext.resolveUserId(principal.getUsername());
        organizationService.requireMembership(orgId, userId);
    }

    private void requireOwnership(UUID orgId, UserDetails principal) {
        UUID userId = securityContext.resolveUserId(principal.getUsername());
        try {
            organizationService.requireOwner(orgId, userId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    public record CreateOrgRequest(
            @NotBlank @Size(max = 40) String name) {
    }

    public record UpdateOrgRequest(
            @NotBlank @Size(max = 40) String name) {
    }

    public record AddMemberRequest(
            @NotBlank String email) {
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public record ChangeMemberRoleRequest(
            @NotBlank String role) {
    }

    public record OrgResponse(
            UUID id, String name, Instant createdAt) {
    }

    public record MemberResponse(
            UUID userId, String email, String fullName, String role, Instant joinedAt) {
    }

    public record MemberNotificationSettingsResponse(
            UUID userId, boolean alertsEnabled, boolean digestEnabled) {
    }

    public record UpdateMemberNotificationSettingsRequest(
            Boolean alertsEnabled, Boolean digestEnabled) {
    }
}
