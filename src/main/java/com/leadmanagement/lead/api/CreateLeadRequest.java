package com.leadmanagement.lead.api;

import com.leadmanagement.lead.domain.LeadSource;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request body for creating a new lead.
 *
 * Uses Java record for immutability and automatic getters.
 * Validation annotations ensure data quality.
 *
 * Accepts both structured fields (firstName, lastName) and a convenience
 * "name" field which is split on first space if firstName/lastName are absent.
 * Accepts rawJsonPayload for passthrough of raw source payloads.
 */
public record CreateLeadRequest(

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    String email,

    /**
     * Convenience full-name field. If provided and firstName is blank,
     * the service will split on the first space to populate firstName/lastName.
     */
    String name,

    String firstName,

    String lastName,

    String company,

    String phone,

    String title,

    /**
     * Source of the lead. Defaults to MANUAL if not provided.
     */
    LeadSource source,

    /**
     * Raw JSON payload from the originating system (e.g. a web form or integration).
     * Stored verbatim in source_details JSONB column for audit and reprocessing.
     */
    String rawJsonPayload,

    /**
     * Custom fields stored as JSONB in database.
     * Example: {"industry": "SaaS", "employees": 50}
     */
    Map<String, Object> customFields
) {
    /**
     * Resolved first name: uses firstName field, or splits name on first space.
     */
    public String resolvedFirstName() {
        if (firstName != null && !firstName.isBlank()) return firstName;
        if (name != null && !name.isBlank()) {
            int space = name.indexOf(' ');
            return space > 0 ? name.substring(0, space) : name;
        }
        return null;
    }

    /**
     * Resolved last name: uses lastName field, or the remainder after first space in name.
     */
    public String resolvedLastName() {
        if (lastName != null && !lastName.isBlank()) return lastName;
        if (name != null && !name.isBlank()) {
            int space = name.indexOf(' ');
            return space > 0 ? name.substring(space + 1) : null;
        }
        return null;
    }

    /**
     * Resolved source: uses source if provided, otherwise defaults to MANUAL.
     */
    public LeadSource resolvedSource() {
        return source != null ? source : LeadSource.MANUAL;
    }
}

