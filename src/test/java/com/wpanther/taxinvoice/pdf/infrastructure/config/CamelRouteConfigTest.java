package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.event.XmlSignedTaxInvoiceEvent;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("CamelRouteConfig Unit Tests")
class CamelRouteConfigTest {

    @Mock
    private TaxInvoicePdfDocumentService documentService;

    private ObjectMapper objectMapper;
    private CamelRouteConfig camelRouteConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        camelRouteConfig = new CamelRouteConfig(
                documentService,
                objectMapper,
                "xml.signed.tax-invoice",
                "pdf.generated",
                "pdf.signing.requested",
                "pdf.generation.tax-invoice.dlq",
                "localhost:9092",
                "taxinvoice-pdf-generation-service"
        );
    }

    @Test
    @DisplayName("Should create PDF generated event with all required fields")
    void testCreatePdfGeneratedEventContainsAllFields() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        TaxInvoicePdfDocument document = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .documentPath("/var/documents/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001.pdf")
                .documentUrl("http://localhost:8084/documents/2024/01/15/taxinvoice-TXINV-2024-001.pdf")
                .fileSize(12345L)
                .mimeType("application/pdf")
                .xmlEmbedded(true)
                .status(GenerationStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .build();

        // When
        Method createEventMethod = CamelRouteConfig.class.getDeclaredMethod(
                "createPdfGeneratedEvent",
                TaxInvoicePdfDocument.class,
                String.class,
                String.class
        );
        createEventMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) createEventMethod.invoke(
                camelRouteConfig, document, "doc-123", "corr-456"
        );

        // Then
        assertThat(event).containsKeys(
                "eventId", "eventType", "occurredAt", "version",
                "documentId", "taxInvoiceId", "taxInvoiceNumber", "documentType",
                "pdfDocumentId", "documentUrl", "documentPath",
                "fileSize", "mimeType", "xmlEmbedded",
                "correlationId", "generatedAt"
        );
        assertThat(event.get("eventType")).isEqualTo("pdf.generated.tax-invoice");
        assertThat(event.get("version")).isEqualTo(1);
        assertThat(event.get("documentId")).isEqualTo("doc-123");
        assertThat(event.get("taxInvoiceId")).isEqualTo("tax-inv-001");
        assertThat(event.get("taxInvoiceNumber")).isEqualTo("TXINV-2024-001");
        assertThat(event.get("documentType")).isEqualTo("TAX_INVOICE");
        assertThat(event.get("pdfDocumentId")).isEqualTo(documentId.toString());
        assertThat(event.get("fileSize")).isEqualTo(12345L);
        assertThat(event.get("xmlEmbedded")).isEqualTo(true);
        assertThat(event.get("correlationId")).isEqualTo("corr-456");
    }

    @Test
    @DisplayName("Should use document ID as fallback when documentId parameter is null")
    void testCreatePdfGeneratedEventUsesDocumentIdFallback() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        TaxInvoicePdfDocument document = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .build();

        // When
        Method createEventMethod = CamelRouteConfig.class.getDeclaredMethod(
                "createPdfGeneratedEvent",
                TaxInvoicePdfDocument.class,
                String.class,
                String.class
        );
        createEventMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) createEventMethod.invoke(
                camelRouteConfig, document, null, "corr-456"
        );

        // Then
        assertThat(event.get("documentId")).isEqualTo(documentId.toString());
    }

    @Test
    @DisplayName("Should build correct Kafka consumer URI")
    void testBuildKafkaConsumerUri() throws Exception {
        // When
        Method buildUriMethod = CamelRouteConfig.class.getDeclaredMethod(
                "buildKafkaConsumerUri", String.class);
        buildUriMethod.setAccessible(true);

        String uri = (String) buildUriMethod.invoke(camelRouteConfig, "test-topic");

        // Then
        assertThat(uri).contains("kafka:test-topic");
        assertThat(uri).contains("brokers=localhost:9092");
        assertThat(uri).contains("groupId=taxinvoice-pdf-generation-service");
        assertThat(uri).contains("autoOffsetReset=earliest");
        assertThat(uri).contains("autoCommitEnable=false");
        assertThat(uri).contains("allowManualCommit=true");
        assertThat(uri).contains("breakOnFirstError=true");
    }

    @Test
    @DisplayName("Should build correct Kafka producer URI")
    void testBuildKafkaProducerUri() throws Exception {
        // When
        Method buildUriMethod = CamelRouteConfig.class.getDeclaredMethod(
                "buildKafkaUri", String.class);
        buildUriMethod.setAccessible(true);

        String uri = (String) buildUriMethod.invoke(camelRouteConfig, "output-topic");

        // Then
        assertThat(uri).isEqualTo("kafka:output-topic?brokers=localhost:9092");
    }

    @Test
    @DisplayName("Should serialize and deserialize TaxInvoicePdfGeneratedEvent")
    void testTaxInvoicePdfGeneratedEventSerialization() throws Exception {
        // Given
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                "http://example.com/doc.pdf", 12345L, true, "corr-456"
        );

        // When
        String json = objectMapper.writeValueAsString(event);

        // Then
        assertThat(json).contains("\"eventType\":\"pdf.generated.tax-invoice\"");
        assertThat(json).contains("\"eventId\"");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should deserialize XmlSignedTaxInvoiceEvent from JSON")
    void testXmlSignedTaxInvoiceEventDeserialization() throws Exception {
        // Given
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "xml.signed.tax-invoice",
                "version": 1,
                "documentId": "doc-123",
                "taxInvoiceId": "tax-inv-001",
                "taxInvoiceNumber": "TXINV-2024-001",
                "signedXmlContent": "<TaxInvoice>...</TaxInvoice>",
                "taxInvoiceDataJson": "{}",
                "correlationId": "corr-456"
            }
            """;

        // When
        XmlSignedTaxInvoiceEvent event = objectMapper.readValue(json, XmlSignedTaxInvoiceEvent.class);

        // Then
        assertThat(event.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(event.getDocumentId()).isEqualTo("doc-123");
        assertThat(event.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(event.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(event.getSignedXmlContent()).isEqualTo("<TaxInvoice>...</TaxInvoice>");
        assertThat(event.getTaxInvoiceDataJson()).isEqualTo("{}");
        assertThat(event.getCorrelationId()).isEqualTo("corr-456");
    }
}
