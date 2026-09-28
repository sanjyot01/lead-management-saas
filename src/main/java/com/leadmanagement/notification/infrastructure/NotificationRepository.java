package com.leadmanagement.notification.infrastructure;

import com.leadmanagement.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Tenant-scoped automatically via @TenantId on BaseEntity. */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findAllByOrderByCreatedAtDesc();

    List<Notification> findByReadAtIsNullOrderByCreatedAtDesc();

    boolean existsBySourceEventId(UUID sourceEventId);
}
