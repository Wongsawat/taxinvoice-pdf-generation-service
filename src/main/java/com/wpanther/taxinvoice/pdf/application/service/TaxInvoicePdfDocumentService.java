package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.JpaTaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.TaxInvoicePdfDocumentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Application service for Tax Invoice PDF document operations.
 * Stores generated PDFs in MinIO (S3-compatible) storage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoicePdfDocumentService {

    private final JpaTaxInvoicePdfDocumentRepository repository;
    private final TaxInvoicePdfGenerationService pdfGenerationService;
    private final S3Client s3Client;

    @Value("${app.minio.bucket-name}")
    private String bucketName;

    @Value("${app.minio.base-url}")
    private String baseUrl;

    /**
     * Generate PDF document for tax invoice and upload to MinIO.
     */
    @Transactional
    public TaxInvoicePdfDocument generatePdf(
        String taxInvoiceId,
        String taxInvoiceNumber,
        String xmlContent,
        String taxInvoiceDataJson
    ) {
        log.info("Generating PDF for tax invoice: {}", taxInvoiceNumber);

        // Create PDF document aggregate
        TaxInvoicePdfDocument document = TaxInvoicePdfDocument.builder()
            .taxInvoiceId(taxInvoiceId)
            .taxInvoiceNumber(taxInvoiceNumber)
            .build();

        // Save initial state
        document = saveDomain(document);

        try {
            // Start generation
            document.startGeneration();
            document = saveDomain(document);

            // Generate PDF bytes
            byte[] pdfBytes = pdfGenerationService.generatePdf(
                taxInvoiceNumber, xmlContent, taxInvoiceDataJson);

            // Upload to MinIO
            String s3Key = uploadToMinIO(taxInvoiceNumber, pdfBytes);
            String fileUrl = baseUrl + "/" + s3Key;

            // Mark as completed
            document.markCompleted(s3Key, fileUrl, pdfBytes.length);
            document.markXmlEmbedded();
            document = saveDomain(document);

            log.info("Successfully generated and uploaded PDF for tax invoice: {} (size: {} bytes, key: {})",
                taxInvoiceNumber, pdfBytes.length, s3Key);

            return document;

        } catch (Exception e) {
            log.error("Failed to generate PDF for tax invoice: {}", taxInvoiceNumber, e);
            document.markFailed(e.getMessage());
            saveDomain(document);
            throw new RuntimeException("Tax invoice PDF generation failed", e);
        }
    }

    /**
     * Upload PDF bytes to MinIO and return the S3 key.
     */
    private String uploadToMinIO(String taxInvoiceNumber, byte[] pdfBytes) {
        LocalDate now = LocalDate.now();
        String fileName = String.format("taxinvoice-%s-%s.pdf",
            taxInvoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_"),
            UUID.randomUUID());
        String s3Key = String.format("%04d/%02d/%02d/%s",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);

        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType("application/pdf")
            .contentLength((long) pdfBytes.length)
            .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(pdfBytes));
        log.debug("Uploaded PDF to MinIO: bucket={}, key={}", bucketName, s3Key);

        return s3Key;
    }

    /**
     * Delete a PDF from MinIO (used for compensation).
     * The documentPath column stores the S3 key.
     */
    public void deletePdfFile(String s3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
            s3Client.deleteObject(deleteRequest);
            log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, s3Key);
        } catch (Exception e) {
            log.error("Failed to delete PDF from MinIO: key={}", s3Key, e);
            throw new RuntimeException("Failed to delete PDF from MinIO", e);
        }
    }

    /**
     * Save domain model to database.
     */
    private TaxInvoicePdfDocument saveDomain(TaxInvoicePdfDocument document) {
        TaxInvoicePdfDocumentEntity entity = TaxInvoicePdfDocumentEntity.builder()
            .id(document.getId())
            .taxInvoiceId(document.getTaxInvoiceId())
            .taxInvoiceNumber(document.getTaxInvoiceNumber())
            .documentPath(document.getDocumentPath())
            .documentUrl(document.getDocumentUrl())
            .fileSize(document.getFileSize())
            .mimeType(document.getMimeType())
            .xmlEmbedded(document.isXmlEmbedded())
            .status(document.getStatus())
            .errorMessage(document.getErrorMessage())
            .retryCount(document.getRetryCount())
            .createdAt(document.getCreatedAt())
            .completedAt(document.getCompletedAt())
            .build();

        entity = repository.save(entity);

        return TaxInvoicePdfDocument.builder()
            .id(entity.getId())
            .taxInvoiceId(entity.getTaxInvoiceId())
            .taxInvoiceNumber(entity.getTaxInvoiceNumber())
            .documentPath(entity.getDocumentPath())
            .documentUrl(entity.getDocumentUrl())
            .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0)
            .mimeType(entity.getMimeType())
            .xmlEmbedded(entity.getXmlEmbedded())
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .retryCount(entity.getRetryCount() != null ? entity.getRetryCount() : 0)
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .build();
    }
}
