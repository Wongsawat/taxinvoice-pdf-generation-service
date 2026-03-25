package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.application.usecase.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.usecase.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.domain.constants.PdfGenerationConstants;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
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
 * No @Transactional here — all DB work is in short focused transactions via TaxInvoicePdfDocumentService.
 */
@Service
@Slf4j
public class SagaCommandHandler implements ProcessTaxInvoicePdfUseCase, CompensateTaxInvoicePdfUseCase {

    private static final String MDC_SAGA_ID        = "sagaId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TAX_INVOICE_NUM = "taxInvoiceNumber";
    private static final String MDC_TAX_INVOICE_ID  = "taxInvoiceId";

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
    public void handle(KafkaTaxInvoiceProcessCommand command) {
        MDC.put(MDC_SAGA_ID,         command.getSagaId());
        MDC.put(MDC_CORRELATION_ID,  command.getCorrelationId());
        MDC.put(MDC_TAX_INVOICE_NUM, command.getTaxInvoiceNumber());
        MDC.put(MDC_TAX_INVOICE_ID,  command.getTaxInvoiceId());
        try {
            log.info("Handling ProcessCommand for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceNumber());
            try {
                String signedXmlUrl  = command.getSignedXmlUrl();
                String taxInvoiceId  = command.getTaxInvoiceId();
                String taxInvoiceNum = command.getTaxInvoiceNumber();

                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "signedXmlUrl is null or blank");
                    return;
                }
                if (taxInvoiceId == null || taxInvoiceId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "taxInvoiceId is null or blank");
                    return;
                }
                if (taxInvoiceNum == null || taxInvoiceNum.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "taxInvoiceNumber is null or blank");
                    return;
                }

                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(taxInvoiceId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), command);
                    return;
                }

                int previousRetryCount = existing.map(TaxInvoicePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    if (existing.get().isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(command);
                        return;
                    }
                }

                // TX1: create GENERATING record
                TaxInvoicePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, taxInvoiceId, taxInvoiceNum);
                } else {
                    document = pdfDocumentService.beginGeneration(taxInvoiceId, taxInvoiceNum);
                }

                String s3Key = null;
                try {
                    // NO TRANSACTION: download, generate, upload
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes  = pdfGenerationService.generatePdf(
                            taxInvoiceNum, signedXml, command.getTaxInvoiceDataJson());
                    s3Key = pdfStoragePort.store(taxInvoiceNum, pdfBytes);
                    String fileUrl   = pdfStoragePort.resolveUrl(s3Key);

                    // TX2: mark COMPLETED + write outbox
                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length, previousRetryCount, command);

                } catch (CallNotPermittedException e) {
                    // Circuit breaker is OPEN - upstream service is degraded
                    log.warn("Circuit breaker OPEN for saga {} taxInvoice {}: {}",
                            command.getSagaId(), taxInvoiceNum, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "Circuit breaker open: " + e.getMessage(),
                            previousRetryCount, command);

                } catch (RestClientException e) {
                    // HTTP 4xx/5xx from signed XML fetch - upstream service error
                    log.warn("HTTP error fetching signed XML for saga {} taxInvoice {}: {}",
                            command.getSagaId(), taxInvoiceNum, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "HTTP error fetching signed XML: " + describeThrowable(e),
                            previousRetryCount, command);

                } catch (Exception e) {
                    // PDF generation failure or other unexpected error
                    if (s3Key != null) {
                        try { pdfStoragePort.delete(s3Key); }
                        catch (Exception del) {
                            log.error("[ORPHAN_PDF] s3Key={} saga={} error={}", s3Key, command.getSagaId(),
                                    describeThrowable(del));
                        }
                    }
                    log.error("PDF generation failed for saga {} taxInvoice {}: {}",
                            command.getSagaId(), taxInvoiceNum, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, command);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification for saga {}: {}", command.getSagaId(), e.getMessage());
                pdfDocumentService.publishGenerationFailure(command, "Concurrent modification: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(command, describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(KafkaTaxInvoiceCompensateCommand command) {
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID,  command.getCorrelationId());
        MDC.put(MDC_TAX_INVOICE_ID,  command.getTaxInvoiceId());
        try {
            log.info("Handling compensation for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceId());
            try {
                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(command.getTaxInvoiceId());

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument doc = existing.get();
                    pdfDocumentService.deleteById(doc.getId());
                    if (doc.getDocumentPath() != null) {
                        try { pdfStoragePort.delete(doc.getDocumentPath()); }
                        catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    command.getSagaId(), doc.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated TaxInvoicePdfDocument {} for saga {}",
                            doc.getId(), command.getSagaId());
                } else {
                    log.info("No document for taxInvoiceId {} — already compensated",
                            command.getTaxInvoiceId());
                }
                pdfDocumentService.publishCompensated(command);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(
                        command, "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(KafkaTaxInvoiceProcessCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(KafkaTaxInvoiceCompensateCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
