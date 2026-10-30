package com.moveinsync.roster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /** Reconciliation runs. Small pool: runs are long and one per tenant at a time. */
    @Bean
    ThreadPoolTaskExecutor reconciliationExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("recon-");
        ex.initialize();
        return ex;
    }
}
