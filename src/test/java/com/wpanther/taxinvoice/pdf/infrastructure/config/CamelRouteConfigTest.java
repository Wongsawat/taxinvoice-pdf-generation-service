package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.dto.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.application.port.in.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.port.in.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.SagaRouteConfig;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.ProcessTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfReplyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JSON serialization/deserialization of Kafka command DTOs
 * and event objects used in the Camel routes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CamelRouteConfig Unit Tests")
class CamelRouteConfigTest {

    @Mock
    private ProcessTaxInvoicePdfUseCase processUseCase;

    @Mock
    private CompensateTaxInvoicePdfUseCase compensateUseCase;

    @Mock
    private SagaCommandHandler sagaCommandHandler;

    private ObjectMapper objectMapper;
    private SagaRouteConfig sagaRouteConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        sagaRouteConfig = new SagaRouteConfig(processUseCase, compensateUseCase, sagaCommandHandler, objectMapper);
    }

    @Test
    @DisplayName("Should serialize and deserialize ProcessTaxInvoicePdfCommand")
    void testProcessTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        ProcessTaxInvoicePdfCommand command = new ProcessTaxInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123", "TXINV-2024-001",
                "http://minio/taxinvoice-signed.xml"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        ProcessTaxInvoicePdfCommand deserialized = objectMapper.readValue(json, ProcessTaxInvoicePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getDocumentNumber()).isEqualTo("TXINV-2024-001");
        assertThat(deserialized.getSignedXmlUrl()).isEqualTo("http://minio/taxinvoice-signed.xml");
        assertThat(deserialized.getEventId()).isNotNull();
    }

    @Test
    @DisplayName("Should serialize and deserialize CompensateTaxInvoicePdfCommand")
    void testCompensateTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        CompensateTaxInvoicePdfCommand command = new CompensateTaxInvoicePdfCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        CompensateTaxInvoicePdfCommand deserialized = objectMapper.readValue(json, CompensateTaxInvoicePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
    }

    @Test
    @DisplayName("Should serialize and deserialize TaxInvoicePdfGeneratedEvent")
    void testTaxInvoicePdfGeneratedEventSerialization() throws Exception {
        // Given
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "saga-001", "doc-123", "TXINV-2024-001",
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
    @DisplayName("Should create TaxInvoicePdfReplyEvent with correct status")
    void testTaxInvoicePdfReplyEventCreation() throws Exception {
        // Given
        TaxInvoicePdfReplyEvent successReply = TaxInvoicePdfReplyEvent.success(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "http://localhost:9000/taxinvoices/test.pdf", 12345L);
        TaxInvoicePdfReplyEvent failureReply = TaxInvoicePdfReplyEvent.failure(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456", "error msg");
        TaxInvoicePdfReplyEvent compensatedReply = TaxInvoicePdfReplyEvent.compensated(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456");

        // Then
        assertThat(successReply.isSuccess()).isTrue();
        assertThat(successReply.getSagaId()).isEqualTo("saga-001");

        assertThat(failureReply.isFailure()).isTrue();
        assertThat(failureReply.getErrorMessage()).isEqualTo("error msg");

        assertThat(compensatedReply.isCompensated()).isTrue();

        // Verify serialization
        String json = objectMapper.writeValueAsString(successReply);
        assertThat(json).contains("\"sagaId\":\"saga-001\"");
        assertThat(json).contains("\"status\":\"SUCCESS\"");
    }

    @Test
    @DisplayName("Should deserialize ProcessTaxInvoicePdfCommand from JSON with kebab-case sagaStep")
    void testProcessCommandDeserialization() throws Exception {
        // Given - sagaStep uses kebab-case code as serialized by SagaStep @JsonValue
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "saga.command.tax-invoice-pdf",
                "version": 1,
                "sagaId": "saga-001",
                "sagaStep": "generate-tax-invoice-pdf",
                "correlationId": "corr-456",
                "documentId": "doc-123",
                "documentNumber": "TXINV-2024-001",
                "signedXmlUrl": "<TaxInvoice>signed</TaxInvoice>"
            }
            """;

        // When
        ProcessTaxInvoicePdfCommand cmd = objectMapper.readValue(json, ProcessTaxInvoicePdfCommand.class);

        // Then
        assertThat(cmd.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(cmd.getSagaId()).isEqualTo("saga-001");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-123");
        assertThat(cmd.getDocumentNumber()).isEqualTo("TXINV-2024-001");
        assertThat(cmd.getSignedXmlUrl()).isEqualTo("<TaxInvoice>signed</TaxInvoice>");
    }
}