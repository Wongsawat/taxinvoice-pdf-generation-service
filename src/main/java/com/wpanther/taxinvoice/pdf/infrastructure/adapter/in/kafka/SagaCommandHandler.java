package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.application.port.in.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.port.in.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.ProcessTaxInvoicePdfCommand;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Orchestrates tax invoice PDF generation in response to saga commands.
 * Lives in infrastructure layer as it is the inbound Kafka adapter.
 * No @Transactional here — all DB work is in short focused transactions via TaxInvoicePdfDocumentService.
 */
@Service
@Slf4j
public class SagaCommandHandler implements ProcessTaxInvoicePdfUseCase, CompensateTaxInvoicePdfUseCase {

    private static final String MDC_SAGA_ID         = "sagaId";
    private static final String MDC_CORRELATION_ID  = "correlationId";
    private static final String MDC_DOCUMENT_NUMBER = "documentNumber";
    private static final String MDC_DOCUMENT_ID     = "documentId";

    private final TaxInvoicePdfDocumentService pdfDocumentService;
    private final TaxInvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;
    private final SignedXmlFetchPort signedXmlFetchPort;
    private final int maxRetries;

    public SagaCommandHandler(TaxInvoicePdfDocumentService pdfDocumentService,
                              TaxInvoicePdfGenerationService pdfGenerationService,
                              PdfStoragePort pdfStoragePort,
                              SagaReplyPort sagaReplyPort,
                              SignedXmlFetchPort signedXmlFetchPort,
                              @Value("${app.pdf.generation.max-retries:3}") int maxRetries) {
        this.pdfDocumentService = pdfDocumentService;
        this.pdfGenerationService = pdfGenerationService;
        this.pdfStoragePort = pdfStoragePort;
        this.sagaReplyPort = sagaReplyPort;
        this.signedXmlFetchPort = signedXmlFetchPort;
        this.maxRetries = maxRetries;
    }

    @Override
    public void handle(String documentId, String documentNumber, String signedXmlUrl,
                      String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,         sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_NUMBER, documentNumber);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling ProcessCommand for saga {} document {}", sagaId, documentNumber);
            try {
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "signedXmlUrl is null or blank");
                    return;
                }
                if (documentId == null || documentId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentId is null or blank");
                    return;
                }
                if (documentNumber == null || documentNumber.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentNumber is null or blank");
                    return;
                }

                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(documentId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), sagaId, sagaStep, correlationId);
                    return;
                }

                int previousRetryCount = existing.map(TaxInvoicePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    if (existing.get().isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(sagaId, sagaStep, correlationId, documentId, documentNumber);
                        return;
                    }
                }

                // TX1: create GENERATING record
                TaxInvoicePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, documentId, documentNumber);
                } else {
                    document = pdfDocumentService.beginGeneration(documentId, documentNumber);
                }

                String s3Key = null;
                try {
                    // NO TRANSACTION: download, generate, upload
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes  = pdfGenerationService.generatePdf(documentNumber, signedXml);
                    s3Key = pdfStoragePort.store(documentNumber, pdfBytes);
                    String fileUrl   = pdfStoragePort.resolveUrl(s3Key);

                    // TX2: mark COMPLETED + write outbox
                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length, previousRetryCount,
                            sagaId, sagaStep, correlationId, documentId, documentNumber);

                } catch (CallNotPermittedException e) {
                    // Circuit breaker is OPEN - upstream service is degraded
                    log.warn("Circuit breaker OPEN for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "Circuit breaker open: " + e.getMessage(),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (RestClientException e) {
                    // HTTP 4xx/5xx from signed XML fetch - upstream service error
                    log.warn("HTTP error fetching signed XML for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "HTTP error fetching signed XML: " + describeThrowable(e),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (Exception e) {
                    // PDF generation failure or other unexpected error
                    if (s3Key != null) {
                        try { pdfStoragePort.delete(s3Key); }
                        catch (Exception del) {
                            log.error("[ORPHAN_PDF] s3Key={} saga={} error={}", s3Key, sagaId,
                                    describeThrowable(del));
                        }
                    }
                    log.error("PDF generation failed for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount,
                            sagaId, sagaStep, correlationId);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification for saga {}: {}", sagaId, e.getMessage());
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId,
                        "Concurrent modification: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {}: {}", sagaId, e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId,
                        describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,        sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling compensation for saga {} document {}", sagaId, documentId);
            try {
                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(documentId);

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument doc = existing.get();
                    pdfDocumentService.deleteById(doc.getId());
                    if (doc.getDocumentPath() != null) {
                        try { pdfStoragePort.delete(doc.getDocumentPath()); }
                        catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    sagaId, doc.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated TaxInvoicePdfDocument {} for saga {}",
                            doc.getId(), sagaId);
                } else {
                    log.info("No document for documentId {} — already compensated", documentId);
                }
                pdfDocumentService.publishCompensated(sagaId, sagaStep, correlationId);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {}: {}", sagaId, e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(sagaId, sagaStep, correlationId,
                        "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        // MDC is thread-local and preserved across @Transactional boundaries.
        // This method is called from SagaRouteConfig DLQ handler with MDC already set.
        try {
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {}", sagaId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        // MDC is thread-local and preserved across @Transactional boundaries.
        // This method is called from SagaRouteConfig DLQ handler with MDC already set.
        try {
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Compensation DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {}", sagaId, e);
        }
    }

    /**
     * Best-effort failure notification when Camel cannot deserialize a <em>process</em> message
     * (e.g., malformed JSON or unknown enum value) and routes it to the DLQ.
     * Accepts raw saga coordinates recovered by the caller from the unparsed JSON payload.
     * Runs in its own transaction (REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailureForUnparsedMessage(
            String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after deserialization failure: "
                    + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing (deserialization failure) for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ deserialization failure for saga {} — orchestrator must timeout",
                    sagaId, e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}