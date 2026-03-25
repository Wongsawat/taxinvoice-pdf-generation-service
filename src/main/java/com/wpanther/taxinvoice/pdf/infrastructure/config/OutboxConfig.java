package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence.outbox.JpaOutboxEventRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence.outbox.SpringDataOutboxRepository;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for OutboxEventRepository bean.
 * <p>
 * Registers the JPA implementation of saga-commons OutboxEventRepository,
 * enabling taxinvoice-pdf-generation-service to use the outbox pattern for reliable
 * event publishing via Debezium CDC.
 */
@Configuration
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository.class)
    public OutboxEventRepository outboxEventRepository(SpringDataOutboxRepository springRepository) {
        return new JpaOutboxEventRepository(springRepository);
    }
}
