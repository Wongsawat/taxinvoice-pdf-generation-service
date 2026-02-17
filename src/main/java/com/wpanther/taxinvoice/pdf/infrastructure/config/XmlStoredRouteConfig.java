package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.wpanther.taxinvoice.pdf.application.service.StorageFetchService;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.domain.event.XmlStoredEvent;
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
 * Apache Camel route configuration for consuming XmlStoredEvent.
 * Consumes from xml.stored topic, fetches XML from MinIO, generates PDF.
 *
 * <p>This route:</p>
 * <ul>
 *   <li>Consumes from: xml.stored</li>
 *   <li>Filters for: documentType == "TAX_INVOICE"</li>
 *   <li>Fetches XML from: storageUrl in the event</li>
 *   <li>Produces to: pdf.generated.tax-invoice (for Notification Service)</li>
 *   <li>Produces to: pdf.signing.requested (for PDF Signing Service)</li>
 *   <li>DLQ: pdf.generation.tax-invoice.dlq</li>
 * </ul>
 */
@Component
@Slf4j
public class XmlStoredRouteConfig extends RouteBuilder {

    private static final String DOCUMENT_TYPE_HEADER = "documentType";
    private static final String DOCUMENT_TYPE_TAX_INVOICE = "TAX_INVOICE";

    private final TaxInvoicePdfDocumentService documentService;
    private final StorageFetchService storageFetchService;

    private final String xmlStoredTopic;
    private final String pdfGeneratedTopic;
    private final String pdfSigningRequestedTopic;
    private final String dlqTopic;
    private final String kafkaBootstrapServers;
    private final String consumerGroupId;

    public XmlStoredRouteConfig(
            TaxInvoicePdfDocumentService documentService,
            StorageFetchService storageFetchService,
            @Value("${app.kafka.topics.xml-stored:xml.stored}") String xmlStoredTopic,
            @Value("${app.kafka.topics.pdf-generated-tax-invoice}") String pdfGeneratedTopic,
            @Value("${app.kafka.topics.pdf-signing-requested}") String pdfSigningRequestedTopic,
            @Value("${app.kafka.topics.dlq}") String dlqTopic,
            @Value("${app.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${app.kafka.consumer.group-id}") String consumerGroupId) {

        this.documentService = documentService;
        this.storageFetchService = storageFetchService;
        this.xmlStoredTopic = xmlStoredTopic;
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
                    String invoiceNumber = exchange.getIn().getHeader("invoiceNumber", String.class);
                    log.error("Error processing XML stored event for tax invoice: {} - {}",
                            invoiceNumber, cause != null ? cause.getMessage() : "Unknown error");
                }));

        // Main route: Consume XML stored event, fetch from MinIO, generate PDF
        from(buildKafkaConsumerUri(xmlStoredTopic))
                .routeId("xml-stored-tax-invoice-pdf-route")
                .log(LoggingLevel.INFO, "Received XML stored event (partition: ${header.kafka.PARTITION}, offset: ${header.kafka.OFFSET})")

                // Step 1: Parse incoming event
                .unmarshal().json(JsonLibrary.Jackson, XmlStoredEvent.class)

                // Step 2: Filter for TAX_INVOICE document type
                .filter(simple("${body.documentType} == '" + DOCUMENT_TYPE_TAX_INVOICE + "'"))
                .log(LoggingLevel.DEBUG, "Processing TAX_INVOICE document from storage: ${body.invoiceNumber}")

                // Step 3: Fetch XML content from MinIO
                .process(exchange -> {
                    XmlStoredEvent event = exchange.getIn().getBody(XmlStoredEvent.class);
                    log.info("Fetching signed XML from MinIO for tax invoice: {}", event.getInvoiceNumber());

                    String xmlContent = storageFetchService.fetchXmlFromStorage(event.getStorageUrl());

                    // Store in exchange for PDF generation
                    exchange.setProperty("invoiceId", event.getInvoiceId());
                    exchange.setProperty("invoiceNumber", event.getInvoiceNumber());
                    exchange.setProperty("correlationId", event.getCorrelationId());
                    exchange.setProperty("signedXmlContent", xmlContent);
                    exchange.setProperty("objectName", event.getObjectName());
                    exchange.setProperty("storageUrl", event.getStorageUrl());

                    log.info("Successfully fetched signed XML for tax invoice: {} (size: {} bytes)",
                            event.getInvoiceNumber(), xmlContent.length());
                })

                // Step 4: Generate PDF using existing service
                .process(exchange -> {
                    String invoiceId = exchange.getProperty("invoiceId", String.class);
                    String invoiceNumber = exchange.getProperty("invoiceNumber", String.class);
                    String signedXmlContent = exchange.getProperty("signedXmlContent", String.class);
                    String invoiceDataJson = "{}"; // Invoice data is embedded in the XML

                    log.info("Generating PDF for tax invoice: {}", invoiceNumber);

                    // Call existing business service
                    TaxInvoicePdfDocument document = documentService.generatePdf(
                            invoiceId, invoiceNumber, signedXmlContent, invoiceDataJson);

                    // Store result for event creation
                    exchange.setProperty("pdfDocument", document);

                    log.info("Successfully generated PDF for tax invoice: {} (size: {} bytes, path: {})",
                            document.getTaxInvoiceNumber(),
                            document.getFileSize(),
                            document.getDocumentPath());
                })

                // Step 5: Create output event and set headers
                .process(exchange -> {
                    TaxInvoicePdfDocument document = exchange.getProperty("pdfDocument", TaxInvoicePdfDocument.class);
                    String correlationId = exchange.getProperty("correlationId", String.class);

                    // Create event payload
                    Map<String, Object> event = createPdfGeneratedEvent(document, correlationId);
                    exchange.getIn().setBody(event);

                    // Set Kafka headers
                    exchange.getIn().setHeader(KafkaConstants.KEY, document.getTaxInvoiceId());
                    exchange.getIn().setHeader(DOCUMENT_TYPE_HEADER, DOCUMENT_TYPE_TAX_INVOICE);
                    exchange.getIn().setHeader("invoiceNumber", document.getTaxInvoiceNumber());
                })

                // Step 6: Marshal to JSON
                .marshal().json(JsonLibrary.Jackson)

                // Step 7: Multicast to both output topics in parallel
                .multicast()
                .parallelProcessing()
                .to(buildKafkaUri(pdfGeneratedTopic))
                .to(buildKafkaUri(pdfSigningRequestedTopic))
                .end()

                // Step 8: Log success and commit offset
                .log(LoggingLevel.INFO, "Published PDF generated events for tax invoice: ${header.invoiceNumber} from XML storage")
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
     * Create PDF generated event payload.
     */
    private Map<String, Object> createPdfGeneratedEvent(
            TaxInvoicePdfDocument document,
            String correlationId) {

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "pdf.generated.tax-invoice");
        event.put("occurredAt", Instant.now().toString());
        event.put("version", 1);
        event.put("documentId", document.getId().toString());
        event.put("invoiceId", document.getTaxInvoiceId());
        event.put("invoiceNumber", document.getTaxInvoiceNumber());
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
