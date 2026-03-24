package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.application.usecase.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.usecase.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.SagaRouteConfig;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfReplyEvent;
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
        sagaRouteConfig = new SagaRouteConfig(processUseCase, compensateUseCase, sagaCommandHandler);
    }

    @Test
    @DisplayName("Should serialize and deserialize KafkaTaxInvoiceProcessCommand")
    void testProcessTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                "<TaxInvoice>...</TaxInvoice>", "{}"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        KafkaTaxInvoiceProcessCommand deserialized = objectMapper.readValue(json, KafkaTaxInvoiceProcessCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(deserialized.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(deserialized.getSignedXmlUrl()).isEqualTo("<TaxInvoice>...</TaxInvoice>");
        assertThat(deserialized.getTaxInvoiceDataJson()).isEqualTo("{}");
        assertThat(deserialized.getEventId()).isNotNull();
    }

    @Test
    @DisplayName("Should serialize and deserialize KafkaTaxInvoiceCompensateCommand")
    void testCompensateTaxInvoicePdfCommandSerialization() throws Exception {
        // Given
        KafkaTaxInvoiceCompensateCommand command = new KafkaTaxInvoiceCompensateCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123", "tax-inv-001"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        KafkaTaxInvoiceCompensateCommand deserialized = objectMapper.readValue(json, KafkaTaxInvoiceCompensateCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getTaxInvoiceId()).isEqualTo("tax-inv-001");
    }

    @Test
    @DisplayName("Should serialize and deserialize TaxInvoicePdfGeneratedEvent")
    void testTaxInvoicePdfGeneratedEventSerialization() throws Exception {
        // Given
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "saga-001", "doc-123", "tax-inv-001", "TXINV-2024-001",
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
    @DisplayName("Should deserialize KafkaTaxInvoiceProcessCommand from JSON")
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
                "taxInvoiceId": "tax-inv-001",
                "taxInvoiceNumber": "TXINV-2024-001",
                "signedXmlUrl": "<TaxInvoice>signed</TaxInvoice>",
                "taxInvoiceDataJson": "{\\"key\\": \\"value\\"}"
            }
            """;

        // When
        KafkaTaxInvoiceProcessCommand cmd = objectMapper.readValue(json, KafkaTaxInvoiceProcessCommand.class);

        // Then
        assertThat(cmd.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(cmd.getSagaId()).isEqualTo("saga-001");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_TAX_INVOICE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-123");
        assertThat(cmd.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(cmd.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(cmd.getSignedXmlUrl()).isEqualTo("<TaxInvoice>signed</TaxInvoice>");
        assertThat(cmd.getTaxInvoiceDataJson()).isEqualTo("{\"key\": \"value\"}");
    }
}
