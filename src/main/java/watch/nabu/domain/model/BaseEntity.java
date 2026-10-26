package watch.nabu.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.util.UUID;

/**
 * Common identity root for all JPA entities.
 *
 * <p>UUID generation is handled by Hibernate ({@link GenerationType#UUID}) in Java
 * and backed by {@code gen_random_uuid()} at the database level so that Liquibase
 * seed INSERTs that omit the {@code id} column also receive a valid key.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
}
