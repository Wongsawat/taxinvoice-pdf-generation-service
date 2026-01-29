package com.wpanther.taxinvoice.pdf.infrastructure.messaging;

import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publisher for Tax Invoice PDF generation events
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.pdf-generated-tax-invoice}")
    private String pdfGeneratedTopic;

    /**
     * Publish PDF generated event for tax invoice
     */
    public void publishPdfGenerated(TaxInvoicePdfDocument document, String documentId, String correlationId) {
        log.info("Publishing PDF generated event for tax invoice: {}", document.getTaxInvoiceNumber());

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", java.util.UUID.randomUUID().toString());
        event.put("eventType", "pdf.generated.tax-invoice");
        event.put("occurredAt", Instant.now().toString());
        event.put("version", 1);
        event.put("documentId", documentId != null ? documentId : document.getId().toString());
        event.put("taxInvoiceId", document.getTaxInvoiceId());
        event.put("taxInvoiceNumber", document.getTaxInvoiceNumber());
        event.put("pdfDocumentId", document.getId().toString());
        event.put("documentUrl", document.getDocumentUrl());
        event.put("fileSize", document.getFileSize());
        event.put("xmlEmbedded", document.isXmlEmbedded());
        event.put("correlationId", correlationId);

        kafkaTemplate.send(pdfGeneratedTopic, document.getTaxInvoiceId(), event)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Successfully published PDF generated event for tax invoice: {}",
                        document.getTaxInvoiceNumber());
                } else {
                    log.error("Failed to publish PDF generated event for tax invoice: {}",
                        document.getTaxInvoiceNumber(), ex);
                }
            });
    }
}
