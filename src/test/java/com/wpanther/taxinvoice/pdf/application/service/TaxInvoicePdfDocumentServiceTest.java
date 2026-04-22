package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.infrastructure.metrics.PdfGenerationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfDocumentService Unit Tests")
class TaxInvoicePdfDocumentServiceTest {

    @Mock
    private TaxInvoicePdfDocumentRepository repository;

    @Mock
    private PdfEventPort pdfEventPort;

    @Mock
    private SagaReplyPort sagaReplyPort;

    @Mock
    private PdfGenerationMetrics pdfGenerationMetrics;

    // Note: Using reflection to instantiate because Lombok @RequiredArgsConstructor
    // is scope=provided, not available during test compilation
    private TaxInvoicePdfDocumentService getService() {
        try {
            return TaxInvoicePdfDocumentService.class
                    .getDeclaredConstructor(TaxInvoicePdfDocumentRepository.class,
                                           PdfEventPort.class, SagaReplyPort.class,
                                           PdfGenerationMetrics.class)
                    .newInstance(repository, pdfEventPort, sagaReplyPort, pdfGenerationMetrics);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate TaxInvoicePdfDocumentService", e);
        }
    }

    private TaxInvoicePdfDocument createCompletedDocument() {
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.GENERATING) // Start in GENERATING
                .mimeType("application/pdf")
                .build();
        // Now transition to COMPLETED via the proper method
        doc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/taxinvoices/test.pdf", 5000L);
        doc.markXmlEmbedded();
        return doc;
    }

    @Test
    @DisplayName("findByTaxInvoiceId() delegates to repository")
    void testFindByTaxInvoiceId() {
        // Given
        TaxInvoicePdfDocument doc = createCompletedDocument();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(doc));

        // When
        var service = getService();
        Optional<TaxInvoicePdfDocument> result = service.findByTaxInvoiceId("tax-inv-001");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getTaxInvoiceNumber()).isEqualTo("TXINV-001");
    }

    @Test
    @DisplayName("beginGeneration() creates GENERATING document")
    void testBeginGeneration() {
        // Given
        TaxInvoicePdfDocument savedDoc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .status(GenerationStatus.GENERATING)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .mimeType("application/pdf")
                .build();
        when(repository.save(any())).thenReturn(savedDoc);

        // When
        var service = getService();
        service.beginGeneration("tax-inv-001", "TXINV-001");

        // Then
        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument toSave = captor.getValue();
        assertThat(toSave.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(toSave.getTaxInvoiceNumber()).isEqualTo("TXINV-001");
    }

    @Test
    @DisplayName("deleteById() deletes document and flushes")
    void testDeleteById() {
        // When
        var service = getService();
        service.deleteById(UUID.randomUUID());

        // Then
        verify(repository).deleteById(any(UUID.class));
        verify(repository).flush();
    }

    @Test
    @DisplayName("publishIdempotentSuccess() publishes events for already completed document")
    void testPublishIdempotentSuccess() {
        // Given
        TaxInvoicePdfDocument doc = createCompletedDocument();
        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "TXINV-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.publishIdempotentSuccess(doc, command);

        // Then
        verify(pdfEventPort).publishPdfGenerated(any(TaxInvoicePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "http://localhost:9000/taxinvoices/test.pdf", 5000L);
    }

    @Test
    @DisplayName("publishRetryExhausted() publishes failure reply")
    void testPublishRetryExhausted() {
        // Given
        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "TXINV-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.publishRetryExhausted(command);

        // Then
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("publishGenerationFailure() publishes failure with error message")
    void testPublishGenerationFailure() {
        // Given
        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "TXINV-001",
                "http://minio:9000/signed.xml");
        String errorMessage = "Invalid XML format";

        // When
        var service = getService();
        service.publishGenerationFailure(command, errorMessage);

        // Then
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                errorMessage);
    }

    @Test
    @DisplayName("publishCompensated() publishes COMPENSATED reply")
    void testPublishCompensated() {
        // Given
        KafkaTaxInvoiceCompensateCommand command = new KafkaTaxInvoiceCompensateCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1");

        // When
        var service = getService();
        service.publishCompensated(command);

        // Then
        verify(sagaReplyPort).publishCompensated("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1");
    }

    @Test
    @DisplayName("publishCompensationFailure() publishes failure for compensation error")
    void testPublishCompensationFailure() {
        // Given
        KafkaTaxInvoiceCompensateCommand command = new KafkaTaxInvoiceCompensateCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1");
        String error = "Failed to delete PDF file";

        // When
        var service = getService();
        service.publishCompensationFailure(command, error);

        // Then
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1", error);
    }

    @Test
    @DisplayName("completeGenerationAndPublish() marks COMPLETED and publishes events")
    void testCompleteGenerationAndPublish() {
        // Given
        UUID documentId = UUID.randomUUID();
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();

        // Create a completed document with the same ID for the mock return
        TaxInvoicePdfDocument savedDoc = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        savedDoc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/taxinvoices/test.pdf", 5000L);
        savedDoc.markXmlEmbedded();

        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(savedDoc);

        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "TXINV-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.completeGenerationAndPublish(documentId, "2024/01/15/test.pdf",
                "http://localhost:9000/taxinvoices/test.pdf", 5000L, 0, command);

        // Then
        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(saved.isXmlEmbedded()).isTrue();

        verify(pdfEventPort).publishPdfGenerated(any(TaxInvoicePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "http://localhost:9000/taxinvoices/test.pdf", 5000L);
    }

    @Test
    @DisplayName("failGenerationAndPublish() marks FAILED and publishes failure")
    void testFailGenerationAndPublish() {
        // Given
        UUID documentId = UUID.randomUUID();
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        TaxInvoicePdfDocument savedDoc = TaxInvoicePdfDocument.builder()
                .id(documentId)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.FAILED)
                .errorMessage("PDF generation failed")
                .mimeType("application/pdf")
                .build();
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(savedDoc);

        KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "TXINV-001",
                "http://minio:9000/signed.xml");
        String errorMessage = "PDF generation failed";

        // When
        var service = getService();
        service.failGenerationAndPublish(documentId, errorMessage, 0, command);

        // Then
        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo(errorMessage);

        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1", errorMessage);
    }
}
