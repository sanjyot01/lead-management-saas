package com.leadmanagement.lead.infrastructure;

import com.leadmanagement.lead.domain.LeadActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for LeadActivity entity.
 *
 * Stores activities like form resubmissions, status changes, etc.
 */
@Repository
public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {
}
