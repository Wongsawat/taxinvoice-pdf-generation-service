package com.wpanther.taxinvoice.pdf.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuration for RestTemplate with timeout and circuit breaker settings.
 * <p>
 * The RestTemplate is configured with connect and read timeouts to prevent
 * thread exhaustion when the signed XML service is unresponsive. A circuit
 * breaker is also configured to fail fast when the upstream service is degraded.
 */
@Configuration
@Slf4j
public class RestTemplateConfig {

    @Value("${app.rest-client.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${app.rest-client.read-timeout:30000}")
    private int readTimeout;

    /**
     * RestTemplate with configured timeouts for fetching signed XML documents.
     * <p>
     * Timeouts are configurable via:
     * - app.rest-client.connect-timeout (default: 5000ms)
     * - app.rest-client.read-timeout (default: 30000ms)
     */
    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        log.info("Configured RestTemplate with connectTimeout={}ms, readTimeout={}ms",
                connectTimeout, readTimeout);

        return new RestTemplate(factory);
    }

    /**
     * Circuit breaker for signed XML fetch operations.
     * <p>
     * Opens after 50% failure rate with minimum 5 calls, and remains open
     * for 60 seconds before attempting a half-open state.
     */
    @Bean
    public CircuitBreaker signedXmlFetchCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .build();

        CircuitBreaker circuitBreaker = registry.circuitBreaker("signedXmlFetch", config);

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("Circuit breaker 'signedXmlFetch' state transition: {}",
                        event.getStateTransition()));

        log.info("Configured circuit breaker 'signedXmlFetch' with failureRateThreshold=50%, " +
                "waitDurationInOpenState=60s, slowCallDurationThreshold=3s");

        return circuitBreaker;
    }
}
