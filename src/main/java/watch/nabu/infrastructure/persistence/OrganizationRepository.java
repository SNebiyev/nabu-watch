package watch.nabu.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.Organization;

import java.util.Collection;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    @Query("SELECT o FROM Organization o WHERE LOWER(o.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Organization> searchByName(@Param("search") String search, Pageable pageable);

    Page<Organization> findAll(Pageable pageable);

    void deleteAllByIdIn(Collection<UUID> ids);
}
