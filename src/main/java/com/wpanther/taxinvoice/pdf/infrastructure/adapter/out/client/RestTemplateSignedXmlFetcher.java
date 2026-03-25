package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.client;

import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort.SignedXmlFetchException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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

    @Override
    @CircuitBreaker(name = "signedXmlFetch", fallbackMethod = "fallbackOnFailure")
    public String fetch(String signedXmlUrl) {
        log.debug("Fetching signed XML from {}", signedXmlUrl);

        String response = restTemplate.getForObject(signedXmlUrl, String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException(
                    "Received null or empty signed XML response from: " + signedXmlUrl);
        }

        log.debug("Successfully fetched signed XML, size: {} bytes", response.length());
        return response;
    }

    /**
     * Fallback method for circuit breaker.
     * <p>
     * Called when the circuit breaker is OPEN or HALF_OPEN and calls are being rejected.
     * Throws an exception to trigger the saga retry mechanism.
     *
     * @param signedXmlUrl the URL being fetched
     * @param throwable the cause of the circuit breaker activation
     * @return never returns; always throws
     * @throws SignedXmlFetchException indicating circuit breaker is open
     */
    private String fallbackOnFailure(String signedXmlUrl, Throwable throwable) {
        throw new SignedXmlFetchException(
                "Circuit breaker 'signedXmlFetch' is OPEN — " +
                "document-storage-service is degraded. URL: " + signedXmlUrl, throwable);
    }
}
