package com.wpanther.taxinvoice.pdf.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for Tax Invoice PDF generation events
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxInvoicePdfEventListener {

    private final TaxInvoicePdfDocumentService documentService;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${app.kafka.topics.xml-signed-tax-invoice}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleXmlSigned(
        @Payload String message,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment acknowledgment
    ) {
        log.info("Received XML signed event for tax invoice (partition: {}, offset: {})", partition, offset);

        try {
            // Parse event
            JsonNode event = objectMapper.readTree(message);
            String documentId = event.get("documentId").asText();
            String taxInvoiceId = event.get("taxInvoiceId").asText();
            String taxInvoiceNumber = event.get("taxInvoiceNumber").asText();
            String signedXmlContent = event.get("signedXmlContent").asText();
            String taxInvoiceDataJson = event.has("taxInvoiceDataJson") ?
                event.get("taxInvoiceDataJson").asText() : "{}";
            String correlationId = event.has("correlationId") ?
                event.get("correlationId").asText() : null;

            log.info("Processing PDF generation for signed tax invoice: {}", taxInvoiceNumber);

            // Generate PDF with signed XML
            TaxInvoicePdfDocument document = documentService.generatePdf(
                taxInvoiceId, taxInvoiceNumber, signedXmlContent, taxInvoiceDataJson);

            // Publish PDF generated event
            eventPublisher.publishPdfGenerated(document, documentId, correlationId);

            // Acknowledge message
            acknowledgment.acknowledge();
            log.info("Successfully processed PDF generation for signed tax invoice: {}", taxInvoiceNumber);

        } catch (Exception e) {
            log.error("Error processing PDF generation for signed tax invoice", e);
            // Don't acknowledge - message will be retried
        }
    }
}
