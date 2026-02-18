package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.wpanther.taxinvoice.pdf.infrastructure.persistence.outbox.JpaOutboxEventRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.outbox.SpringDataOutboxRepository;
import com.wpanther.saga.domain.outbox.OutboxEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

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

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
