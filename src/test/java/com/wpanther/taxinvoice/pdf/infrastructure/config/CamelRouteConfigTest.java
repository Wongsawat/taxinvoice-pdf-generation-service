package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.taxinvoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.domain.event.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.ProcessTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfReplyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("CamelRouteConfig Unit Tests")
class CamelRouteConfigTest {

    @Mock
    private SagaCommandHandler sagaCommandHandler;

    private ObjectMapper objectMapper;
    private CamelRouteConfig camelRouteConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        camelRouteConfig = new CamelRouteConfig(sagaCommandHandler);
    }

    @Test
    @DisplayName("Should serialize and deserialize ProcessTaxInvoicePdfCommand")
    void testProcessTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        ProcessTaxInvoicePdfCommand command = new ProcessTaxInvoicePdfCommand(
                "saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                "<TaxInvoice>...</TaxInvoice>", "{}"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        ProcessTaxInvoicePdfCommand deserialized = objectMapper.readValue(json, ProcessTaxInvoicePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo("GENERATE_TAX_INVOICE_PDF");
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(deserialized.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(deserialized.getSignedXmlContent()).isEqualTo("<TaxInvoice>...</TaxInvoice>");
        assertThat(deserialized.getTaxInvoiceDataJson()).isEqualTo("{}");
        assertThat(deserialized.getEventId()).isNotNull();
    }

    @Test
    @DisplayName("Should serialize and deserialize CompensateTaxInvoicePdfCommand")
    void testCompensateTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        CompensateTaxInvoicePdfCommand command = new CompensateTaxInvoicePdfCommand(
                "saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "doc-123", "tax-inv-001"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        CompensateTaxInvoicePdfCommand deserialized = objectMapper.readValue(json, CompensateTaxInvoicePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo("GENERATE_TAX_INVOICE_PDF");
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getTaxInvoiceId()).isEqualTo("tax-inv-001");
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
    @DisplayName("Should create TaxInvoicePdfReplyEvent with correct status")
    void testTaxInvoicePdfReplyEventCreation() throws Exception {
        // Given
        TaxInvoicePdfReplyEvent successReply = TaxInvoicePdfReplyEvent.success("saga-001", "step-1", "corr-456",
                "http://localhost:9000/taxinvoices/test.pdf", 12345L);
        TaxInvoicePdfReplyEvent failureReply = TaxInvoicePdfReplyEvent.failure("saga-001", "step-1", "corr-456", "error msg");
        TaxInvoicePdfReplyEvent compensatedReply = TaxInvoicePdfReplyEvent.compensated("saga-001", "step-1", "corr-456");

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
    @DisplayName("Should deserialize ProcessTaxInvoicePdfCommand from JSON")
    void testProcessCommandDeserialization() throws Exception {
        // Given
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "saga.command.tax-invoice-pdf",
                "version": 1,
                "sagaId": "saga-001",
                "sagaStep": "GENERATE_TAX_INVOICE_PDF",
                "correlationId": "corr-456",
                "documentId": "doc-123",
                "taxInvoiceId": "tax-inv-001",
                "taxInvoiceNumber": "TXINV-2024-001",
                "signedXmlContent": "<TaxInvoice>signed</TaxInvoice>",
                "taxInvoiceDataJson": "{\\"key\\": \\"value\\"}"
            }
            """;

        // When
        ProcessTaxInvoicePdfCommand cmd = objectMapper.readValue(json, ProcessTaxInvoicePdfCommand.class);

        // Then
        assertThat(cmd.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(cmd.getSagaId()).isEqualTo("saga-001");
        assertThat(cmd.getSagaStep()).isEqualTo("GENERATE_TAX_INVOICE_PDF");
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-123");
        assertThat(cmd.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(cmd.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(cmd.getSignedXmlContent()).isEqualTo("<TaxInvoice>signed</TaxInvoice>");
        assertThat(cmd.getTaxInvoiceDataJson()).isEqualTo("{\"key\": \"value\"}");
    }
}
