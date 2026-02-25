package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.domain.event.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.ProcessTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.EventPublisher;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.SagaReplyPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Handles saga commands from orchestrator for tax invoice PDF generation.
 * Delegates business logic to TaxInvoicePdfDocumentService and sends replies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

    private final TaxInvoicePdfDocumentRepository repository;
    private final TaxInvoicePdfDocumentService pdfDocumentService;
    private final SagaReplyPublisher sagaReplyPublisher;
    private final EventPublisher eventPublisher;
    private final RestTemplate restTemplate;

    @Value("${app.pdf.generation.max-retries:3}")
    private int maxRetries;

    /**
     * Handle a ProcessTaxInvoicePdfCommand from saga orchestrator.
     * Generates PDF and sends a SUCCESS or FAILURE reply.
     */
    @Transactional
    public void handleProcessCommand(ProcessTaxInvoicePdfCommand command) {
        MDC.put("sagaId", command.getSagaId());
        MDC.put("correlationId", command.getCorrelationId());
        MDC.put("taxInvoiceNumber", command.getTaxInvoiceNumber());
        try {
            log.info("Handling ProcessTaxInvoicePdfCommand for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceNumber());

            try {
                Optional<TaxInvoicePdfDocument> existing =
                        repository.findByTaxInvoiceId(command.getTaxInvoiceId());

                // Idempotency: already completed — re-publish events and reply SUCCESS
                if (existing.isPresent() && existing.get().isCompleted()) {
                    log.warn("Tax invoice PDF already generated for {}, sending SUCCESS reply",
                            command.getTaxInvoiceNumber());
                    TaxInvoicePdfDocument document = existing.get();
                    publishEvents(document, command);
                    sagaReplyPublisher.publishSuccess(
                            command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                            document.getDocumentUrl(), document.getFileSize());
                    return;
                }

                // Retry limit check: use aggregate method
                if (existing.isPresent() && existing.get().isFailed()) {
                    TaxInvoicePdfDocument failedDocument = existing.get();
                    if (failedDocument.isMaxRetriesExceeded(maxRetries)) {
                        log.error("Max retries ({}) exceeded for saga {} taxInvoice {}",
                                maxRetries, command.getSagaId(), command.getTaxInvoiceNumber());
                        sagaReplyPublisher.publishFailure(
                                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                                "Maximum retry attempts exceeded");
                        return;
                    }
                    // Delete the failed record so generatePdf() can create a fresh one;
                    // flush ensures the DELETE precedes the subsequent INSERT on the same taxInvoiceId.
                    repository.deleteById(failedDocument.getId());
                    repository.flush();
                }

                // Validate and download signed XML from MinIO
                String signedXmlUrl = command.getSignedXmlUrl();
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    throw new IllegalStateException("signedXmlUrl is null or blank in saga command");
                }
                String signedXml = restTemplate.getForObject(signedXmlUrl, String.class);
                if (signedXml == null || signedXml.isBlank()) {
                    throw new IllegalStateException("Failed to download signed XML from " + signedXmlUrl);
                }

                // Generate PDF — never throws; returns document in COMPLETED or FAILED state
                TaxInvoicePdfDocument document = pdfDocumentService.generatePdf(
                        command.getTaxInvoiceId(),
                        command.getTaxInvoiceNumber(),
                        signedXml,
                        command.getTaxInvoiceDataJson());

                // If generation failed, persist the correct retry count and send FAILURE reply
                if (document.isFailed()) {
                    if (existing.isPresent()) {
                        int targetCount = existing.get().getRetryCount() + 1;
                        while (document.getRetryCount() < targetCount) {
                            document.incrementRetryCount();
                        }
                        repository.save(document);
                    }
                    sagaReplyPublisher.publishFailure(
                            command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                            document.getErrorMessage());
                    return;
                }

                // Carry forward the retry count from the previous failed attempt.
                // The new document starts at retryCount=0; set it to previousCount+1
                // so isMaxRetriesExceeded() fires correctly on the next saga retry.
                if (existing.isPresent()) {
                    int targetCount = existing.get().getRetryCount() + 1;
                    while (document.getRetryCount() < targetCount) {
                        document.incrementRetryCount();
                    }
                    document = repository.save(document);
                }

                // Publish events and send SUCCESS reply with MinIO URL for the PDF_STORAGE step
                publishEvents(document, command);
                sagaReplyPublisher.publishSuccess(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        document.getDocumentUrl(), document.getFileSize());

                log.info("Successfully processed tax invoice PDF generation for saga {} taxInvoice {}",
                        command.getSagaId(), command.getTaxInvoiceNumber());

            } catch (Exception e) {
                log.error("Failed to process tax invoice PDF generation for saga {} taxInvoice {}: {}",
                        command.getSagaId(), command.getTaxInvoiceNumber(), e.getMessage(), e);
                sagaReplyPublisher.publishFailure(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * Handle a CompensateTaxInvoicePdfCommand from saga orchestrator.
     * Deletes generated PDF document and sends a COMPENSATED reply.
     */
    @Transactional
    public void handleCompensation(CompensateTaxInvoicePdfCommand command) {
        MDC.put("sagaId", command.getSagaId());
        MDC.put("correlationId", command.getCorrelationId());
        MDC.put("taxInvoiceId", command.getTaxInvoiceId());
        try {
            log.info("Handling compensation for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceId());

            try {
                Optional<TaxInvoicePdfDocument> existing =
                        repository.findByTaxInvoiceId(command.getTaxInvoiceId());

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument document = existing.get();
                    // DB delete first: if this fails the transaction rolls back and the S3 object
                    // remains intact — no orphaned DB record pointing to a missing file.
                    // If DB delete succeeds but S3 delete fails below, we have an unreferenced S3
                    // object. That is the lesser evil: it causes no functional harm and can be
                    // cleaned up by a MinIO lifecycle expiry rule.
                    repository.deleteById(document.getId());
                    if (document.getDocumentPath() != null) {
                        pdfDocumentService.deletePdfFile(document.getDocumentPath());
                    }
                    log.info("Compensated TaxInvoicePdfDocument {} for saga {}", document.getId(), command.getSagaId());
                } else {
                    log.info("No TaxInvoicePdfDocument found for taxInvoiceId {} - already compensated or never processed",
                            command.getTaxInvoiceId());
                }

                sagaReplyPublisher.publishCompensated(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId());

            } catch (Exception e) {
                log.error("Failed to compensate tax invoice PDF generation for saga {} taxInvoice {}: {}",
                        command.getSagaId(), command.getTaxInvoiceId(), e.getMessage(), e);
                sagaReplyPublisher.publishFailure(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                        "Compensation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            MDC.clear();
        }
    }

    private void publishEvents(TaxInvoicePdfDocument document, ProcessTaxInvoicePdfCommand command) {
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                command.getDocumentId(),
                document.getTaxInvoiceId(),
                document.getTaxInvoiceNumber(),
                document.getDocumentUrl(),
                document.getFileSize(),
                document.isXmlEmbedded(),
                command.getCorrelationId()
        );
        eventPublisher.publishPdfGenerated(event);
    }
}
