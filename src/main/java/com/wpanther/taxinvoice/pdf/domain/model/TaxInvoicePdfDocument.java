package com.wpanther.taxinvoice.pdf.domain.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing a Tax Invoice PDF document
 *
 * This aggregate encapsulates the PDF generation lifecycle including
 * generation and XML embedding for tax invoices.
 */
public class TaxInvoicePdfDocument {

    // Identity
    private final UUID id;

    // Tax Invoice Reference
    private final String taxInvoiceId;
    private final String taxInvoiceNumber;

    // Document Location
    private String documentPath;
    private String documentUrl;

    // Document Metadata
    private long fileSize;
    private final String mimeType;
    private boolean xmlEmbedded;

    // Status
    private GenerationStatus status;
    private String errorMessage;

    // Timestamps
    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;

    private TaxInvoicePdfDocument(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.taxInvoiceId = Objects.requireNonNull(builder.taxInvoiceId, "Tax Invoice ID is required");
        this.taxInvoiceNumber = Objects.requireNonNull(builder.taxInvoiceNumber, "Tax Invoice number is required");
        this.documentPath = builder.documentPath;
        this.documentUrl = builder.documentUrl;
        this.fileSize = builder.fileSize;
        this.mimeType = builder.mimeType != null ? builder.mimeType : "application/pdf";
        this.xmlEmbedded = builder.xmlEmbedded;
        this.status = builder.status != null ? builder.status : GenerationStatus.PENDING;
        this.errorMessage = builder.errorMessage;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.completedAt = builder.completedAt;

        validateInvariant();
    }

    /**
     * Validate business invariants
     */
    private void validateInvariant() {
        if (taxInvoiceId.isBlank()) {
            throw new IllegalStateException("Tax Invoice ID cannot be blank");
        }

        if (taxInvoiceNumber.isBlank()) {
            throw new IllegalStateException("Tax Invoice number cannot be blank");
        }
    }

    /**
     * Start PDF generation
     */
    public void startGeneration() {
        if (this.status != GenerationStatus.PENDING) {
            throw new IllegalStateException("Can only start generation from PENDING status");
        }
        this.status = GenerationStatus.GENERATING;
    }

    /**
     * Mark generation as completed
     */
    public void markCompleted(String documentPath, String documentUrl, long fileSize) {
        if (this.status != GenerationStatus.GENERATING) {
            throw new IllegalStateException("Can only complete from GENERATING status");
        }

        Objects.requireNonNull(documentPath, "Document path is required");
        Objects.requireNonNull(documentUrl, "Document URL is required");

        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }

        this.documentPath = documentPath;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.status = GenerationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark generation as failed
     */
    public void markFailed(String errorMessage) {
        this.status = GenerationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark XML as embedded
     */
    public void markXmlEmbedded() {
        this.xmlEmbedded = true;
    }

    /**
     * Check if generation is successful
     */
    public boolean isSuccessful() {
        return status == GenerationStatus.COMPLETED;
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getTaxInvoiceId() {
        return taxInvoiceId;
    }

    public String getTaxInvoiceNumber() {
        return taxInvoiceNumber;
    }

    public String getDocumentPath() {
        return documentPath;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isXmlEmbedded() {
        return xmlEmbedded;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    /**
     * Builder for TaxInvoicePdfDocument
     */
    public static class Builder {
        private UUID id;
        private String taxInvoiceId;
        private String taxInvoiceNumber;
        private String documentPath;
        private String documentUrl;
        private long fileSize;
        private String mimeType;
        private boolean xmlEmbedded;
        private GenerationStatus status;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder taxInvoiceId(String taxInvoiceId) {
            this.taxInvoiceId = taxInvoiceId;
            return this;
        }

        public Builder taxInvoiceNumber(String taxInvoiceNumber) {
            this.taxInvoiceNumber = taxInvoiceNumber;
            return this;
        }

        public Builder documentPath(String documentPath) {
            this.documentPath = documentPath;
            return this;
        }

        public Builder documentUrl(String documentUrl) {
            this.documentUrl = documentUrl;
            return this;
        }

        public Builder fileSize(long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder xmlEmbedded(boolean xmlEmbedded) {
            this.xmlEmbedded = xmlEmbedded;
            return this;
        }

        public Builder status(GenerationStatus status) {
            this.status = status;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder completedAt(LocalDateTime completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public TaxInvoicePdfDocument build() {
            return new TaxInvoicePdfDocument(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
