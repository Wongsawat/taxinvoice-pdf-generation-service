package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Apache Camel route configuration for Tax Invoice PDF generation.
 *
 * <p>This route:</p>
 * <ul>
 *   <li>Consumes from: xml.signed.tax-invoice</li>
 *   <li>Produces to: pdf.generated (for Notification Service)</li>
 *   <li>Produces to: pdf.signing.requested (for PDF Signing Service)</li>
 *   <li>DLQ: pdf.generation.tax-invoice.dlq</li>
 * </ul>
 */
@Component
@Slf4j
public class CamelRouteConfig extends RouteBuilder {

    private static final String DOCUMENT_TYPE_HEADER = "documentType";
    private static final String DOCUMENT_TYPE_TAX_INVOICE = "TAX_INVOICE";

    private final TaxInvoicePdfDocumentService documentService;
    private final ObjectMapper objectMapper;

    private final String xmlSignedTaxInvoiceTopic;
    private final String pdfGeneratedTopic;
    private final String pdfSigningRequestedTopic;
    private final String dlqTopic;
    private final String kafkaBootstrapServers;
    private final String consumerGroupId;

    public CamelRouteConfig(
            TaxInvoicePdfDocumentService documentService,
            ObjectMapper objectMapper,
            @Value("${app.kafka.topics.xml-signed-tax-invoice}") String xmlSignedTaxInvoiceTopic,
            @Value("${app.kafka.topics.pdf-generated}") String pdfGeneratedTopic,
            @Value("${app.kafka.topics.pdf-signing-requested}") String pdfSigningRequestedTopic,
            @Value("${app.kafka.topics.dlq}") String dlqTopic,
            @Value("${app.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${app.kafka.consumer.group-id}") String consumerGroupId) {

        this.documentService = documentService;
        this.objectMapper = objectMapper;
        this.xmlSignedTaxInvoiceTopic = xmlSignedTaxInvoiceTopic;
        this.pdfGeneratedTopic = pdfGeneratedTopic;
        this.pdfSigningRequestedTopic = pdfSigningRequestedTopic;
        this.dlqTopic = dlqTopic;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.consumerGroupId = consumerGroupId;
    }

    @Override
    public void configure() throws Exception {

        // Global error handler with Dead Letter Queue
        errorHandler(deadLetterChannel(buildKafkaUri(dlqTopic))
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .useExponentialBackOff()
                .backOffMultiplier(2)
                .maximumRedeliveryDelay(30000)
                .logExhausted(true)
                .logRetryAttempted(true)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .onExceptionOccurred(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    String taxInvoiceNumber = exchange.getIn().getHeader("taxInvoiceNumber", String.class);
                    log.error("Error processing tax invoice PDF generation for: {} - {}",
                            taxInvoiceNumber, cause != null ? cause.getMessage() : "Unknown error");
                }));

        // Main route: Consume signed XML, generate PDF, produce to both output topics
        from(buildKafkaConsumerUri(xmlSignedTaxInvoiceTopic))
                .routeId("taxinvoice-pdf-generation-route")
                .log(LoggingLevel.INFO, "Received XML signed tax invoice event (partition: ${header.kafka.PARTITION}, offset: ${header.kafka.OFFSET})")

                // Step 1: Parse incoming event JSON
                .process(exchange -> {
                    String message = exchange.getIn().getBody(String.class);
                    log.debug("Processing message: {}", message);

                    JsonNode event = objectMapper.readTree(message);

                    // Extract required fields
                    String documentId = getJsonField(event, "documentId");
                    String taxInvoiceId = getJsonField(event, "taxInvoiceId");
                    String taxInvoiceNumber = getJsonField(event, "taxInvoiceNumber");
                    String signedXmlContent = getJsonField(event, "signedXmlContent");
                    String taxInvoiceDataJson = event.has("taxInvoiceDataJson")
                            ? event.get("taxInvoiceDataJson").asText() : "{}";
                    String correlationId = event.has("correlationId")
                            ? event.get("correlationId").asText() : UUID.randomUUID().toString();

                    log.info("Processing PDF generation for signed tax invoice: {}", taxInvoiceNumber);

                    // Store in headers for downstream processors
                    exchange.getIn().setHeader("documentId", documentId);
                    exchange.getIn().setHeader("taxInvoiceId", taxInvoiceId);
                    exchange.getIn().setHeader("taxInvoiceNumber", taxInvoiceNumber);
                    exchange.getIn().setHeader("correlationId", correlationId);
                    exchange.setProperty("signedXmlContent", signedXmlContent);
                    exchange.setProperty("taxInvoiceDataJson", taxInvoiceDataJson);
                })

                // Step 2: Generate PDF using existing service
                .process(exchange -> {
                    String taxInvoiceId = exchange.getIn().getHeader("taxInvoiceId", String.class);
                    String taxInvoiceNumber = exchange.getIn().getHeader("taxInvoiceNumber", String.class);
                    String signedXmlContent = exchange.getProperty("signedXmlContent", String.class);
                    String taxInvoiceDataJson = exchange.getProperty("taxInvoiceDataJson", String.class);

                    log.info("Generating PDF for tax invoice: {}", taxInvoiceNumber);

                    // Call existing business service
                    TaxInvoicePdfDocument document = documentService.generatePdf(
                            taxInvoiceId, taxInvoiceNumber, signedXmlContent, taxInvoiceDataJson);

                    // Store result for event creation
                    exchange.setProperty("pdfDocument", document);

                    log.info("Successfully generated PDF for tax invoice: {} (size: {} bytes, path: {})",
                            document.getTaxInvoiceNumber(),
                            document.getFileSize(),
                            document.getDocumentPath());
                })

                // Step 3: Create output event and set headers
                .process(exchange -> {
                    TaxInvoicePdfDocument document = exchange.getProperty("pdfDocument", TaxInvoicePdfDocument.class);
                    String documentId = exchange.getIn().getHeader("documentId", String.class);
                    String correlationId = exchange.getIn().getHeader("correlationId", String.class);

                    // Create event payload
                    Map<String, Object> event = createPdfGeneratedEvent(document, documentId, correlationId);
                    exchange.getIn().setBody(event);

                    // Set Kafka headers
                    exchange.getIn().setHeader(KafkaConstants.KEY, document.getTaxInvoiceId());
                    exchange.getIn().setHeader(DOCUMENT_TYPE_HEADER, DOCUMENT_TYPE_TAX_INVOICE);
                })

                // Step 4: Marshal to JSON
                .marshal().json(JsonLibrary.Jackson)

                // Step 5: Multicast to both output topics in parallel
                .multicast()
                .parallelProcessing()
                .to(buildKafkaUri(pdfGeneratedTopic))
                .to(buildKafkaUri(pdfSigningRequestedTopic))
                .end()

                // Step 6: Log success and commit offset
                .log(LoggingLevel.INFO, "Published PDF generated events for tax invoice: ${header.taxInvoiceNumber} to ${header.documentType}")
                .process(exchange -> {
                    // Signal manual commit
                    exchange.getIn().setHeader(KafkaConstants.MANUAL_COMMIT, true);
                });
    }

    /**
     * Build Kafka consumer URI with all required parameters.
     */
    private String buildKafkaConsumerUri(String topic) {
        return String.format(
                "kafka:%s?brokers=%s&groupId=%s&autoOffsetReset=earliest&autoCommitEnable=false&allowManualCommit=true&breakOnFirstError=true",
                topic, kafkaBootstrapServers, consumerGroupId);
    }

    /**
     * Build Kafka producer URI.
     */
    private String buildKafkaUri(String topic) {
        return String.format("kafka:%s?brokers=%s", topic, kafkaBootstrapServers);
    }

    /**
     * Safely extract field from JSON node.
     */
    private String getJsonField(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText();
        }
        throw new IllegalArgumentException("Required field missing: " + fieldName);
    }

    /**
     * Create PDF generated event payload.
     */
    private Map<String, Object> createPdfGeneratedEvent(
            TaxInvoicePdfDocument document,
            String documentId,
            String correlationId) {

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "pdf.generated.tax-invoice");
        event.put("occurredAt", Instant.now().toString());
        event.put("version", 1);
        event.put("documentId", documentId != null ? documentId : document.getId().toString());
        event.put("taxInvoiceId", document.getTaxInvoiceId());
        event.put("taxInvoiceNumber", document.getTaxInvoiceNumber());
        event.put("documentType", DOCUMENT_TYPE_TAX_INVOICE);
        event.put("pdfDocumentId", document.getId().toString());
        event.put("documentUrl", document.getDocumentUrl());
        event.put("documentPath", document.getDocumentPath());
        event.put("fileSize", document.getFileSize());
        event.put("mimeType", document.getMimeType());
        event.put("xmlEmbedded", document.isXmlEmbedded());
        event.put("correlationId", correlationId);
        event.put("generatedAt", document.getCompletedAt() != null
                ? document.getCompletedAt().toString()
                : Instant.now().toString());

        return event;
    }
}
