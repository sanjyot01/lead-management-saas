package com.leadmanagement;

import com.leadmanagement.infrastructure.config.JwtConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(JwtConfig.class)
public class LeadManagementSaasApplication {

    private static final Logger log = LoggerFactory.getLogger(LeadManagementSaasApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(LeadManagementSaasApplication.class, args);
	}

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("APPLICATION IS READY AND RUNNING ON PORT 8080");
    }
}
