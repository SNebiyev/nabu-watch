package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import watch.nabu.domain.model.ComplianceMonitorType;

import java.util.Optional;
import java.util.UUID;

public interface ComplianceMonitorTypeRepository extends JpaRepository<ComplianceMonitorType, UUID> {

    Optional<ComplianceMonitorType> findBySubTypeCode(String subTypeCode);
}
