package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.domain.event.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.ProcessTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.JpaTaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.TaxInvoicePdfDocumentEntity;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.EventPublisher;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.SagaReplyPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final JpaTaxInvoicePdfDocumentRepository repository;
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
        log.info("Handling ProcessTaxInvoicePdfCommand for saga {} taxInvoice {}",
                command.getSagaId(), command.getTaxInvoiceNumber());

        try {
            // Check if already generated (idempotency)
            Optional<TaxInvoicePdfDocumentEntity> existing =
                    repository.findByTaxInvoiceId(command.getTaxInvoiceId());

            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus.COMPLETED) {
                log.warn("Tax invoice PDF already generated for {}, sending SUCCESS reply",
                        command.getTaxInvoiceNumber());

                // Publish events for already-completed document
                TaxInvoicePdfDocumentEntity entity = existing.get();
                publishEvents(entity, command);

                sagaReplyPublisher.publishSuccess(
                        command.getSagaId(),
                        command.getSagaStep(),
                        command.getCorrelationId(),
                        entity.getDocumentUrl(),
                        entity.getFileSize()
                );
                return;
            }

            // Check retry limit for previously failed attempts
            if (existing.isPresent() && existing.get().getStatus() == com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus.FAILED) {
                TaxInvoicePdfDocumentEntity entity = existing.get();
                int retryCount = entity.getRetryCount() != null ? entity.getRetryCount() : 0;
                if (retryCount >= maxRetries) {
                    log.error("Max retries ({}) exceeded for saga {} taxInvoice {}",
                            maxRetries, command.getSagaId(), command.getTaxInvoiceNumber());
                    sagaReplyPublisher.publishFailure(
                            command.getSagaId(),
                            command.getSagaStep(),
                            command.getCorrelationId(),
                            "Maximum retry attempts exceeded"
                    );
                    return;
                }
                // Delete the failed entity so generatePdf can create a new one
                repository.deleteById(entity.getId());
                repository.flush();
            }

            // Download signed XML from MinIO
            String signedXmlUrl = command.getSignedXmlUrl();
            String signedXml = restTemplate.getForObject(signedXmlUrl, String.class);
            if (signedXml == null || signedXml.isBlank()) {
                throw new IllegalStateException("Failed to download signed XML from " + signedXmlUrl);
            }

            // Generate PDF (calls existing service)
            TaxInvoicePdfDocument document = pdfDocumentService.generatePdf(
                    command.getTaxInvoiceId(),
                    command.getTaxInvoiceNumber(),
                    signedXml,
                    command.getTaxInvoiceDataJson()
            );

            // Update retry count if retrying
            if (existing.isPresent()) {
                repository.findByTaxInvoiceId(command.getTaxInvoiceId()).ifPresent(entity -> {
                    int retryCount = existing.get().getRetryCount() != null ? existing.get().getRetryCount() : 0;
                    entity.setRetryCount(retryCount + 1);
                    repository.save(entity);
                });
            }

            // Publish events via outbox
            repository.findByTaxInvoiceId(command.getTaxInvoiceId()).ifPresent(entity ->
                    publishEvents(entity, command));

            // Send SUCCESS reply with MinIO URL for the PDF_STORAGE step
            sagaReplyPublisher.publishSuccess(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    document.getDocumentUrl(),
                    (long) document.getFileSize()
            );

            log.info("Successfully processed tax invoice PDF generation for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceNumber());

        } catch (Exception e) {
            log.error("Failed to process tax invoice PDF generation for saga {} taxInvoice {}: {}",
                    command.getSagaId(), command.getTaxInvoiceNumber(), e.getMessage(), e);

            // Send FAILURE reply
            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    e.getMessage()
            );
        }
    }

    /**
     * Handle a CompensateTaxInvoicePdfCommand from saga orchestrator.
     * Deletes generated PDF document and sends a COMPENSATED reply.
     */
    @Transactional
    public void handleCompensation(CompensateTaxInvoicePdfCommand command) {
        log.info("Handling compensation for saga {} taxInvoice {}",
                command.getSagaId(), command.getTaxInvoiceId());

        try {
            Optional<TaxInvoicePdfDocumentEntity> existing =
                    repository.findByTaxInvoiceId(command.getTaxInvoiceId());

            if (existing.isPresent()) {
                TaxInvoicePdfDocumentEntity entity = existing.get();
                // Delete PDF file from filesystem
                if (entity.getDocumentPath() != null) {
                    pdfDocumentService.deletePdfFile(entity.getDocumentPath());
                }
                // Delete database record
                repository.deleteById(entity.getId());
                log.info("Deleted TaxInvoicePdfDocument {} for compensation", entity.getId());
            } else {
                log.info("No TaxInvoicePdfDocument found for taxInvoiceId {} - already compensated or never processed",
                        command.getTaxInvoiceId());
            }

            // Send COMPENSATED reply (idempotent)
            sagaReplyPublisher.publishCompensated(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId()
            );

        } catch (Exception e) {
            log.error("Failed to compensate tax invoice PDF generation for saga {} taxInvoice {}: {}",
                    command.getSagaId(), command.getTaxInvoiceId(), e.getMessage(), e);

            // Send FAILURE reply
            sagaReplyPublisher.publishFailure(
                    command.getSagaId(),
                    command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation failed: " + e.getMessage()
            );
        }
    }

    /**
     * Publish PDF generated event to notification service.
     * PDF signing is handled by the orchestrator via saga commands.
     */
    private void publishEvents(TaxInvoicePdfDocumentEntity entity, ProcessTaxInvoicePdfCommand command) {
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                command.getDocumentId(),
                entity.getTaxInvoiceId(),
                entity.getTaxInvoiceNumber(),
                entity.getDocumentUrl(),
                entity.getFileSize() != null ? entity.getFileSize() : 0,
                entity.getXmlEmbedded(),
                command.getCorrelationId()
        );

        eventPublisher.publishPdfGenerated(event);
    }
}
