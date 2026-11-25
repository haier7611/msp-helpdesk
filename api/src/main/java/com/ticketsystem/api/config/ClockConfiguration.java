package com.ticketsystem.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One application clock, so expiry and session-limit tests can control time. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
