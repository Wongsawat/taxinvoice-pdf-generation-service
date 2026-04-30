package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
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

/**
 * Unit tests for {@link TaxInvoicePdfDocumentService} with updated
 * plain-parameter signatures matching the refactored layer separation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfDocumentService Unit Tests")
class TaxInvoicePdfDocumentServiceTest {

    private static final String SAGA_ID = "saga-1";
    private static final SagaStep SAGA_STEP = SagaStep.GENERATE_TAX_INVOICE_PDF;
    private static final String CORR_ID = "corr-1";
    private static final String DOC_ID = "doc-1";
    private static final String DOC_NUMBER = "TXINV-001";

    @Mock
    private TaxInvoicePdfDocumentRepository repository;

    @Mock
    private PdfEventPort pdfEventPort;

    @Mock
    private SagaReplyPort sagaReplyPort;

    @Mock
    private PdfGenerationMetrics pdfGenerationMetrics;

    private TaxInvoicePdfDocumentService getService() {
        return new TaxInvoicePdfDocumentService(repository, pdfEventPort, sagaReplyPort, pdfGenerationMetrics);
    }

    private TaxInvoicePdfDocument createCompletedDocument() {
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        doc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/taxinvoices/test.pdf", 5000L);
        doc.markXmlEmbedded();
        return doc;
    }

    @Test
    @DisplayName("findByTaxInvoiceId() delegates to repository")
    void testFindByTaxInvoiceId() {
        TaxInvoicePdfDocument doc = createCompletedDocument();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(doc));

        var service = getService();
        Optional<TaxInvoicePdfDocument> result = service.findByTaxInvoiceId("tax-inv-001");

        assertThat(result).isPresent();
        assertThat(result.get().getTaxInvoiceNumber()).isEqualTo("TXINV-001");
    }

    @Test
    @DisplayName("beginGeneration() creates GENERATING document")
    void testBeginGeneration() {
        TaxInvoicePdfDocument savedDoc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .status(GenerationStatus.GENERATING)
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .mimeType("application/pdf")
                .build();
        when(repository.save(any())).thenReturn(savedDoc);

        var service = getService();
        service.beginGeneration("tax-inv-001", "TXINV-001");

        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument toSave = captor.getValue();
        assertThat(toSave.getTaxInvoiceId()).isEqualTo("tax-inv-001");
        assertThat(toSave.getTaxInvoiceNumber()).isEqualTo("TXINV-001");
    }

    @Test
    @DisplayName("deleteById() deletes document and flushes")
    void testDeleteById() {
        var service = getService();
        service.deleteById(UUID.randomUUID());

        verify(repository).deleteById(any(UUID.class));
        verify(repository).flush();
    }

    @Test
    @DisplayName("publishIdempotentSuccess() publishes events for already completed document")
    void testPublishIdempotentSuccess() {
        TaxInvoicePdfDocument doc = createCompletedDocument();

        var service = getService();
        service.publishIdempotentSuccess(doc, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfEventPort).publishPdfGenerated(any(TaxInvoicePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess(SAGA_ID, SAGA_STEP, CORR_ID,
                "http://localhost:9000/taxinvoices/test.pdf", 5000L);
    }

    @Test
    @DisplayName("publishRetryExhausted() publishes failure reply")
    void testPublishRetryExhausted() {
        var service = getService();
        service.publishRetryExhausted(SAGA_ID, SAGA_STEP, CORR_ID, DOC_ID, DOC_NUMBER);

        verify(pdfGenerationMetrics).recordRetryExhausted(SAGA_ID, DOC_ID, DOC_NUMBER);
        verify(sagaReplyPort).publishFailure(SAGA_ID, SAGA_STEP, CORR_ID,
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("publishGenerationFailure() publishes failure with error message")
    void testPublishGenerationFailure() {
        String errorMessage = "Invalid XML format";
        var service = getService();
        service.publishGenerationFailure(SAGA_ID, SAGA_STEP, CORR_ID, errorMessage);

        verify(sagaReplyPort).publishFailure(SAGA_ID, SAGA_STEP, CORR_ID, errorMessage);
    }

    @Test
    @DisplayName("publishCompensated() publishes COMPENSATED reply")
    void testPublishCompensated() {
        var service = getService();
        service.publishCompensated(SAGA_ID, SAGA_STEP, CORR_ID);

        verify(sagaReplyPort).publishCompensated(SAGA_ID, SAGA_STEP, CORR_ID);
    }

    @Test
    @DisplayName("publishCompensationFailure() publishes failure for compensation error")
    void testPublishCompensationFailure() {
        String error = "Failed to delete PDF file";
        var service = getService();
        service.publishCompensationFailure(SAGA_ID, SAGA_STEP, CORR_ID, error);

        verify(sagaReplyPort).publishFailure(SAGA_ID, SAGA_STEP, CORR_ID, error);
    }

    @Test
    @DisplayName("completeGenerationAndPublish() marks COMPLETED and publishes events")
    void testCompleteGenerationAndPublish() {
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
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        savedDoc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/taxinvoices/test.pdf", 5000L);
        savedDoc.markXmlEmbedded();

        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(savedDoc);

        var service = getService();
        service.completeGenerationAndPublish(documentId, "2024/01/15/test.pdf",
                "http://localhost:9000/taxinvoices/test.pdf", 5000L, 0,
                SAGA_ID, SAGA_STEP, CORR_ID, DOC_ID, DOC_NUMBER);

        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(saved.isXmlEmbedded()).isTrue();

        verify(pdfEventPort).publishPdfGenerated(any(TaxInvoicePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess(SAGA_ID, SAGA_STEP, CORR_ID,
                "http://localhost:9000/taxinvoices/test.pdf", 5000L);
    }

    @Test
    @DisplayName("failGenerationAndPublish() marks FAILED and publishes failure")
    void testFailGenerationAndPublish() {
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

        String errorMessage = "PDF generation failed";
        var service = getService();
        service.failGenerationAndPublish(documentId, errorMessage, 0, SAGA_ID, SAGA_STEP, CORR_ID);

        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());

        TaxInvoicePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo(errorMessage);

        verify(sagaReplyPort).publishFailure(SAGA_ID, SAGA_STEP, CORR_ID, errorMessage);
    }
}