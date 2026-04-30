package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.SagaCommandHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SagaCommandHandler} in its new location
 * (infrastructure/adapter/in/kafka).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    private static final String DOC_ID         = "doc-123";
    private static final String DOC_NUMBER     = "TXINV-2024-001";
    private static final String SAGA_ID        = "saga-001";
    private static final String CORR_ID        = "corr-456";
    private static final SagaStep SAGA_STEP    = SagaStep.GENERATE_TAX_INVOICE_PDF;
    private static final String SIGNED_XML_URL = "http://minio:9000/signed/taxinvoice-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<TaxInvoice>signed</TaxInvoice>";
    private static final String S3_KEY         = "2024/01/15/taxinvoice-TXINV-2024-001-uuid.pdf";
    private static final String FILE_URL       = "http://localhost:9000/taxinvoices/" + S3_KEY;

    @Mock private TaxInvoicePdfDocumentService pdfDocumentService;
    @Mock private TaxInvoicePdfGenerationService pdfGenerationService;
    @Mock private PdfStoragePort pdfStoragePort;
    @Mock private SagaReplyPort sagaReplyPort;
    @Mock private SignedXmlFetchPort signedXmlFetchPort;

    private SagaCommandHandler sagaCommandHandler;

    @BeforeEach
    void setUp() {
        sagaCommandHandler = new SagaCommandHandler(
                pdfDocumentService, pdfGenerationService,
                pdfStoragePort, sagaReplyPort, signedXmlFetchPort, 3);
    }

    private TaxInvoicePdfDocument generatingDoc() {
        return TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId(DOC_ID)
                .taxInvoiceNumber(DOC_NUMBER)
                .status(GenerationStatus.GENERATING)
                .retryCount(0)
                .build();
    }

    private TaxInvoicePdfDocument completedDoc() {
        return TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId(DOC_ID)
                .taxInvoiceNumber(DOC_NUMBER)
                .status(GenerationStatus.COMPLETED)
                .documentPath(S3_KEY)
                .documentUrl(FILE_URL)
                .fileSize(12345L)
                .xmlEmbedded(true)
                .retryCount(0)
                .build();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handle(process): success — beginGeneration → fetch → generate → upload → completeGenerationAndPublish")
    void testHandleProcessCommand_Success() throws Exception {
        byte[] pdfBytes = new byte[5000];
        TaxInvoicePdfDocument doc = generatingDoc();

        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfGenerationService.generatePdf(anyString(), anyString())).thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any())).thenReturn(S3_KEY);
        when(pdfStoragePort.resolveUrl(S3_KEY)).thenReturn(FILE_URL);
        when(pdfDocumentService.beginGeneration(DOC_ID, DOC_NUMBER)).thenReturn(doc);

        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, SIGNED_XML_URL, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).beginGeneration(DOC_ID, DOC_NUMBER);
        verify(pdfGenerationService).generatePdf(DOC_NUMBER, SIGNED_XML_CONTENT);
        verify(pdfStoragePort).store(DOC_NUMBER, pdfBytes);
        verify(pdfDocumentService).completeGenerationAndPublish(
                eq(doc.getId()), eq(S3_KEY), eq(FILE_URL), eq(5000L), eq(-1),
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID), eq(DOC_ID), eq(DOC_NUMBER));
    }

    @Test
    @DisplayName("handle(process): idempotent SUCCESS for already-completed document")
    void testHandleProcessCommand_AlreadyCompleted() {
        TaxInvoicePdfDocument doc = completedDoc();
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.of(doc));

        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, SIGNED_XML_URL, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verify(pdfDocumentService).publishIdempotentSuccess(doc, SAGA_ID, SAGA_STEP, CORR_ID);
    }

    @Test
    @DisplayName("handle(process): FAILURE when max retries exceeded")
    void testHandleProcessCommand_MaxRetriesExceeded() {
        TaxInvoicePdfDocument failedDoc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId(DOC_ID)
                .taxInvoiceNumber(DOC_NUMBER)
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.of(failedDoc));

        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, SIGNED_XML_URL, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).publishRetryExhausted(SAGA_ID, SAGA_STEP, CORR_ID, DOC_ID, DOC_NUMBER);
    }

    @Test
    @DisplayName("handle(process): FAILURE when signedXmlUrl is null")
    void testHandleProcessCommand_NullSignedXmlUrl() {
        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, null, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).publishGenerationFailure(
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID), eq("signedXmlUrl is null or blank"));
    }

    @Test
    @DisplayName("handle(process): FAILURE when PDF generation throws exception")
    void testHandleProcessCommand_GenerationFails() throws Exception {
        TaxInvoicePdfDocument doc = generatingDoc();
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);
        when(pdfDocumentService.beginGeneration(DOC_ID, DOC_NUMBER)).thenReturn(doc);
        when(pdfGenerationService.generatePdf(anyString(), anyString()))
                .thenThrow(new TaxInvoicePdfGenerationException("FOP failed"));

        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, SIGNED_XML_URL, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).failGenerationAndPublish(
                eq(doc.getId()),
                eq("TaxInvoicePdfGenerationException: FOP failed"),
                eq(-1),
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID));
    }

    @Test
    @DisplayName("handle(process): FAILURE on circuit breaker open")
    void testHandleProcessCommand_CircuitBreakerOpen() {
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL))
                .thenThrow(new RuntimeException("Circuit breaker open"));

        sagaCommandHandler.handle(DOC_ID, DOC_NUMBER, SIGNED_XML_URL, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).publishGenerationFailure(
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID), anyString());
    }

    // -------------------------------------------------------------------------
    // Compensation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("handle(compensate): deletes document and publishes COMPENSATED")
    void testHandleCompensation_Success() {
        TaxInvoicePdfDocument doc = completedDoc();
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.of(doc));

        sagaCommandHandler.handle(DOC_ID, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfStoragePort).delete(S3_KEY);
        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(SAGA_ID, SAGA_STEP, CORR_ID);
    }

    @Test
    @DisplayName("handle(compensate): COMPENSATED even when document not found (idempotent)")
    void testHandleCompensation_NoDocumentFound() {
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.empty());

        sagaCommandHandler.handle(DOC_ID, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfStoragePort, never()).delete(anyString());
        verify(pdfDocumentService, never()).deleteById(any());
        verify(pdfDocumentService).publishCompensated(SAGA_ID, SAGA_STEP, CORR_ID);
    }

    @Test
    @DisplayName("handle(compensate): COMPENSATED even when MinIO deletion fails")
    void testHandleCompensation_StorageFailure() {
        TaxInvoicePdfDocument doc = completedDoc();
        when(pdfDocumentService.findByTaxInvoiceId(DOC_ID)).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("MinIO unavailable")).when(pdfStoragePort).delete(anyString());

        sagaCommandHandler.handle(DOC_ID, SAGA_ID, SAGA_STEP, CORR_ID);

        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(SAGA_ID, SAGA_STEP, CORR_ID);
    }

    // -------------------------------------------------------------------------
    // DLQ failure handlers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publishOrchestrationFailure() publishes FAILURE reply for DLQ events")
    void testPublishOrchestrationFailure() {
        Throwable cause = new RuntimeException("DLQ error");

        sagaCommandHandler.publishOrchestrationFailure(SAGA_ID, SAGA_STEP, CORR_ID, cause);

        verify(sagaReplyPort).publishFailure(
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID),
                contains("Message routed to DLQ"));
    }

    @Test
    @DisplayName("publishCompensationOrchestrationFailure() publishes FAILURE for compensation DLQ")
    void testPublishCompensationOrchestrationFailure() {
        Throwable cause = new RuntimeException("Compensation DLQ error");

        sagaCommandHandler.publishCompensationOrchestrationFailure(SAGA_ID, SAGA_STEP, CORR_ID, cause);

        verify(sagaReplyPort).publishFailure(
                eq(SAGA_ID), eq(SAGA_STEP), eq(CORR_ID),
                contains("Compensation DLQ"));
    }
}