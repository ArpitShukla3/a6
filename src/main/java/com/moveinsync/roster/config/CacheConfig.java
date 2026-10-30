package com.moveinsync.roster.config;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {
    /** Replaced by a fake ticker in tests to prove the staleness bound. */
    @Bean
    Ticker cacheTicker() {
        return Ticker.systemTicker();
    }
}
