package com.storyverse.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga o agendador (usado pelo "batimento" das conexões de presença). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
