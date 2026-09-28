package com.leadmanagement.notification.domain;

public enum NotificationType {
    /** A new tenant finished registration (welcome message). */
    TENANT_WELCOME,

    /** A new lead was created. */
    LEAD_CREATED,

    /** A lead moved to a different pipeline stage. */
    LEAD_STAGE_CHANGED
}
