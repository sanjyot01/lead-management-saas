package com.leadmanagement.user.domain;

/**
 * User roles for authorization.
 *
 * Role Hierarchy:
 * - ADMIN: Full access to tenant data, can manage users
 * - MEMBER: Can create and manage leads, limited admin functions
 * - VIEWER: Read-only access to leads and reports
 */
public enum UserRole {
    /**
     * Administrator - Full access to all tenant resources
     */
    ADMIN,

    /**
     * Member - Can create and manage leads
     */
    MEMBER,

    /**
     * Viewer - Read-only access
     */
    VIEWER
}

