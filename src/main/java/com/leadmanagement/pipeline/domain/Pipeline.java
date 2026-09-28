package com.leadmanagement.pipeline.domain;

import com.leadmanagement.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * A sales pipeline a tenant moves leads through.
 *
 * Every tenant gets exactly one default pipeline at registration
 * (DefaultPipelineCreator); additional custom pipelines can be created by
 * tenant admins. "Exactly one active default per tenant" is enforced by a
 * partial unique index (uq_pipelines_tenant_default), not application logic.
 */
@Entity
@SQLDelete(sql = "UPDATE pipelines SET deleted_at = NOW(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "pipelines",
    indexes = @Index(name = "idx_pipelines_tenant_id", columnList = "tenant_id")
)
public class Pipeline extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    protected Pipeline() {}

    public Pipeline(String name, boolean isDefault) {
        this.name = name;
        this.isDefault = isDefault;
    }

    public Long getVersion() { return version; }
    public String getName() { return name; }
    public boolean isDefault() { return isDefault; }

    public void rename(String name) {
        this.name = name;
    }
}
