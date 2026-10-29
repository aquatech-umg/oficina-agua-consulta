package com.aquatech.consulta_service;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RelojConfig {

    @Bean
    public Clock reloj() {
        return Clock.system(CalculoMora.ZONA);
    }
}