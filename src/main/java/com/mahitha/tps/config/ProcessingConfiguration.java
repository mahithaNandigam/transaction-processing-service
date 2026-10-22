package com.mahitha.tps.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
}
