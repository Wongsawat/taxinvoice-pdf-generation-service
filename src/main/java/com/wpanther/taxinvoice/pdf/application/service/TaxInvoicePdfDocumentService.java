package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.infrastructure.metrics.PdfGenerationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Application service for TaxInvoicePdfDocument lifecycle.
 *
 * <p>MDC Context: This service relies on MDC context being set by the caller
 * (SagaCommandHandler) for correlation IDs and saga IDs. MDC is thread-local
 * and is preserved across @Transactional boundaries since transactions do not
 * switch threads.</p>
 *
 * <p>Each method is a short, focused @Transactional unit — no CPU or network I/O
 * inside any transaction.</p>
 *
 * <p>TX1: beginGeneration() / replaceAndBeginGeneration()
 * TX2: completeGenerationAndPublish() / failGenerationAndPublish()</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoicePdfDocumentService {

    private final TaxInvoicePdfDocumentRepository repository;
    private final PdfEventPort pdfEventPort;
    private final SagaReplyPort sagaReplyPort;
    private final PdfGenerationMetrics pdfGenerationMetrics;

    @Transactional(readOnly = true)
    public Optional<TaxInvoicePdfDocument> findByTaxInvoiceId(String taxInvoiceId) {
        return repository.findByTaxInvoiceId(taxInvoiceId);
    }

    @Transactional
    public TaxInvoicePdfDocument beginGeneration(String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Initiating PDF generation for tax invoice: {}", taxInvoiceNumber);
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        return repository.save(doc);
    }

    @Transactional
    public TaxInvoicePdfDocument replaceAndBeginGeneration(
            UUID existingId, int previousRetryCount, String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Replacing document {} and re-starting generation for tax invoice: {}", existingId, taxInvoiceNumber);
        repository.deleteById(existingId);
        repository.flush();
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        // Restore retry count state using incrementRetryCountTo for monotonic guarantee
        doc.incrementRetryCountTo(previousRetryCount + 1);
        return repository.save(doc);
    }

    @Transactional
    public void completeGenerationAndPublish(UUID documentId, String s3Key, String fileUrl,
                                             long fileSize, int previousRetryCount,
                                             String sagaId, SagaStep sagaStep, String correlationId,
                                             String documentIdParam, String documentNumber) {
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize);
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(doc, sagaId, documentIdParam, documentNumber, correlationId));
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId, doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation for saga {} tax invoice {}", sagaId, doc.getTaxInvoiceNumber());
    }

    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError);
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, safeError);

        log.warn("PDF generation failed for saga {} tax invoice {}: {}", sagaId, doc.getTaxInvoiceNumber(), safeError);
    }

    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    @Transactional
    public void publishIdempotentSuccess(TaxInvoicePdfDocument existing,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(existing, sagaId, existing.getTaxInvoiceId(), existing.getTaxInvoiceNumber(), correlationId));
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId, existing.getDocumentUrl(), existing.getFileSize());
        log.warn("Tax invoice PDF already generated for saga {} — re-publishing SUCCESS reply", sagaId);
    }

    @Transactional
    public void publishRetryExhausted(String sagaId, SagaStep sagaStep, String correlationId,
                                       String documentId, String documentNumber) {
        pdfGenerationMetrics.recordRetryExhausted(sagaId, documentId, documentNumber);
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} document {}", sagaId, documentNumber);
    }

    @Transactional
    public void publishGenerationFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, errorMessage);
    }

    @Transactional
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
    }

    @Transactional
    public void publishCompensationFailure(String sagaId, SagaStep sagaStep, String correlationId, String error) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
    }

    private TaxInvoicePdfDocument requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> {
                    log.error("TaxInvoicePdfDocument not found for id={}", documentId);
                    return new IllegalStateException("Expected tax invoice PDF document is absent");
                });
    }

    /**
     * Restore retry count state when replacing a document.
     * Uses incrementRetryCountTo() to advance retryCount to previousRetryCount + 1,
     * ensuring monotonic increase (never decreases an existing higher value).
     *
     * @param doc the document to update
     * @param previousRetryCount the retry count from the previous document attempt (-1 if no previous attempt)
     */
    private void applyRetryCount(TaxInvoicePdfDocument doc, int previousRetryCount) {
        if (previousRetryCount < 0) return;
        doc.incrementRetryCountTo(previousRetryCount + 1);
    }

    private TaxInvoicePdfGeneratedEvent buildGeneratedEvent(TaxInvoicePdfDocument doc,
                                                             String sagaId, String documentId,
                                                             String documentNumber, String correlationId) {
        return new TaxInvoicePdfGeneratedEvent(
                sagaId,
                documentId,
                documentNumber,
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                correlationId);
    }
}