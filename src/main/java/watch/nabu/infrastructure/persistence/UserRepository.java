package watch.nabu.infrastructure.persistence;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.application.admin.UserAdminView;
import watch.nabu.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByOauthProviderAndOauthProviderId(String oauthProvider, String oauthProviderId);

    /**
     * Returns all users with the given role — used to fan-out security alerts.
     */
    List<User> findByRole(User.Role role);

    boolean existsByRole(User.Role role);

    /**
     * Active users who have opted into the weekly digest. Consumed by the
     * digest scheduler so the filter runs at the database rather than loading
     * every user row.
     */
    List<User> findAllByWeeklyDigestEnabledTrueAndStatus(User.AccountStatus status);

    /**
     * Returns every user together with their website count in a single query.
     * Avoids N+1 by using a LEFT JOIN + GROUP BY at the database level.
     */
    @Query("""
            SELECT new watch.nabu.application.admin.UserAdminView(
                u.id, u.fullName, u.email, u.role, u.status, u.createdAt,
                COUNT(w.id), u.lastLoginAt
            )
            FROM User u
            LEFT JOIN Website w ON w.addedBy = u
            GROUP BY u.id, u.fullName, u.email, u.role, u.status, u.createdAt, u.lastLoginAt
            ORDER BY u.createdAt DESC
            """)
    List<UserAdminView> findAllWithSiteCount();

    /**
     * Paginated variant of {@link #findAllWithSiteCount}.
     * Used by the admin user directory which applies server-side pagination.
     */
    @Query(value = """
            SELECT new watch.nabu.application.admin.UserAdminView(
                u.id, u.fullName, u.email, u.role, u.status, u.createdAt,
                COUNT(w.id), u.lastLoginAt
            )
            FROM User u
            LEFT JOIN Website w ON w.addedBy = u
            GROUP BY u.id, u.fullName, u.email, u.role, u.status, u.createdAt, u.lastLoginAt
            """,
            countQuery = "SELECT COUNT(u) FROM User u")
    Page<UserAdminView> findAllWithSiteCountPaged(Pageable pageable);

    List<User> findByStatusAndDeletionRequestedAtBefore(User.AccountStatus status, Instant cutoff);

    /**
     * Case-insensitive lookup for the public trust profile slug.  Honors the
     * {@code uq_users_public_username} expression index (LOWER(public_username)).
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.publicUsername) = LOWER(:username) AND u.publicProfileEnabled = true")
    Optional<User> findByPublicUsernameIgnoreCaseAndPublicProfileEnabled(@Param("username") String username);

    /**
     * Case-insensitive match on the slug regardless of the enabled flag.
     * Used during slug edits in settings to detect conflicts with disabled
     * profiles — the unique index also covers disabled rows.
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.publicUsername) = LOWER(:username)")
    Optional<User> findByPublicUsernameIgnoreCase(@Param("username") String username);
}
