package com.leadmanagement.infrastructure.web;

import com.leadmanagement.infrastructure.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Health check controller for testing tenant context.
 *
 * DEVELOPMENT ONLY - Remove in production.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());

        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId != null) {
            response.put("tenantId", tenantId.toString());
            response.put("tenantContextSet", true);
        } else {
            response.put("tenantContextSet", false);
            response.put("message", "No tenant context (endpoint requires a JWT since Phase 2)");
        }

        return ResponseEntity.ok(response);
    }
}

