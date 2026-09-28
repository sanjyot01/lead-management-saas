/**
 * Infrastructure module providing cross-cutting concerns for all domain modules.
 *
 * This module contains:
 * - Base entity classes (BaseEntity with multi-tenancy support)
 * - Security infrastructure (JWT, authentication, tenant context)
 * - Persistence configuration (JPA, Hibernate, transaction management)
 * - Outbox pattern implementation (event publishing, processing)
 * - Web infrastructure (CORS, filters, exception handlers)
 *
 * This is an open module - all types are accessible to other modules.
 * Infrastructure is foundational and doesn't enforce strict boundaries.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {},
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.leadmanagement.infrastructure;

