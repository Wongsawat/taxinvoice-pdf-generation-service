package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence.outbox.JpaOutboxEventRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence.outbox.SpringDataOutboxRepository;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox infrastructure configuration for taxinvoice-pdf-generation-service.
 * <p>
 * Registers both the JPA OutboxEventRepository implementation and the
 * OutboxService. OutboxService is no longer auto-configured by saga-commons —
 * each publishing service must declare it explicitly here.
 */
@Configuration
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository.class)
    public OutboxEventRepository outboxEventRepository(SpringDataOutboxRepository springRepository) {
        return new JpaOutboxEventRepository(springRepository);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxService.class)
    public OutboxService outboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        return new OutboxService(repository, objectMapper);
    }
}
