package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublisher Unit Tests")
class EventPublisherTest {

    @Mock
    private OutboxService outboxService;

    private ObjectMapper objectMapper;
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        eventPublisher = new EventPublisher(outboxService, objectMapper);
    }

    @Test
    @DisplayName("publishPdfGenerated() calls OutboxService with correct parameters")
    void testPublishPdfGenerated() {
        // Given
        String documentId = "doc-123";
        String taxInvoiceId = "tax-inv-001";
        String taxInvoiceNumber = "TXINV-2024-001";
        String documentUrl = "http://localhost:9000/taxinvoices/test.pdf";
        long fileSize = 12345L;
        boolean xmlEmbedded = true;
        String correlationId = "corr-456";

        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "saga-001", documentId, taxInvoiceId, taxInvoiceNumber, documentUrl, fileSize, xmlEmbedded, correlationId);

        // When
        eventPublisher.publishPdfGenerated(event);

        // Then
        verify(outboxService).saveWithRouting(
                eq(event),
                eq("TaxInvoicePdfDocument"),
                eq(taxInvoiceId),
                eq("pdf.generated.tax-invoice"),
                eq(taxInvoiceId),
                anyString() // headers JSON
        );
    }

    @Test
    @DisplayName("publishPdfGenerated() includes documentType header")
    void testPublishPdfGenerated_Headers() {
        // Given
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "saga-001", "doc-123", "tax-inv-001", "TXINV-001",
                "http://localhost:9000/taxinvoices/test.pdf", 12345L, true, "corr-456");

        // When
        eventPublisher.publishPdfGenerated(event);

        // Then
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(
                any(), anyString(), anyString(), anyString(), anyString(),
                headersCaptor.capture()
        );

        String headersJson = headersCaptor.getValue();
        assertThat(headersJson).contains("\"documentType\":\"TAX_INVOICE\"");
        assertThat(headersJson).contains("\"correlationId\":\"corr-456\"");
    }

    @Test
    @DisplayName("TaxInvoicePdfGeneratedEvent stores sagaId and correlationId independently")
    void testSagaIdAndCorrelationIdStoredIndependently() {
        // Given
        String sagaId = "saga-001";
        String correlationId = "corr-456";

        // When
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                sagaId,
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                "http://localhost:9000/taxinvoices/test.pdf", 12345L, true,
                correlationId);

        // Then
        assertThat(event.getSagaId()).isEqualTo(sagaId);
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
    }
}
