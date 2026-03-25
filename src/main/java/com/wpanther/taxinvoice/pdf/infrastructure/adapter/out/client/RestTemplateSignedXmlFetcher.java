package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.client;

import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Fetches signed XML documents via HTTP with circuit breaker protection.
 * <p>
 * Uses Resilience4j circuit breaker to prevent cascading failures when the
 * signed XML service is unresponsive or returns errors. Timeouts are configured
 * via RestTemplateConfig (connectTimeout: 5000ms, readTimeout: 30000ms).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private final RestTemplate restTemplate;
    private final CircuitBreaker signedXmlFetchCircuitBreaker;

    @Override
    public String fetch(String signedXmlUrl) {
        log.debug("Fetching signed XML from {}", signedXmlUrl);

        String xml = CircuitBreaker.decorateSupplier(signedXmlFetchCircuitBreaker, () -> {
            String response = restTemplate.getForObject(signedXmlUrl, String.class);
            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "Received null or empty signed XML response from: " + signedXmlUrl);
            }
            return response;
        }).get();

        log.debug("Successfully fetched signed XML, size: {} bytes", xml.length());
        return xml;
    }
}
