package com.leadmanagement.infrastructure.config;

import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA configuration.
 *
 * Enables:
 * - JPA repositories with LAZY bootstrap (avoids tenant context requirement at startup)
 * - Transaction management
 * - Hibernate 6 @TenantId support (configured via CurrentTenantResolver)
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.leadmanagement",
    bootstrapMode = BootstrapMode.LAZY
)
@EnableTransactionManagement
public class JpaConfig {

    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer(CurrentTenantResolver tenantResolver) {
        return hibernateProperties -> {
            hibernateProperties.put("hibernate.tenant_identifier_resolver", tenantResolver);
        };
    }
}
