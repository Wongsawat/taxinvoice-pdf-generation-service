package com.wpanther.taxinvoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes integration events via outbox pattern for reliable delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private static final String AGGREGATE_TYPE = "TaxInvoicePdfDocument";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Publish PDF generated event to pdf.generated.tax-invoice topic (for Notification Service).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPdfGenerated(TaxInvoicePdfGeneratedEvent event) {
        Map<String, String> headers = Map.of(
            "documentType", "TAX_INVOICE",
            "correlationId", event.getCorrelationId()
        );

        outboxService.saveWithRouting(
            event,
            AGGREGATE_TYPE,
            event.getTaxInvoiceId(),
            "pdf.generated.tax-invoice",
            event.getTaxInvoiceId(),
            toJson(headers)
        );

        log.info("Published TaxInvoicePdfGeneratedEvent to outbox for notification: {}", event.getTaxInvoiceNumber());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers to JSON", e);
            return null;
        }
    }
}
