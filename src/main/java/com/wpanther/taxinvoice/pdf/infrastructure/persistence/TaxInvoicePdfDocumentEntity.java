package com.wpanther.taxinvoice.pdf.infrastructure.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tax_invoice_pdf_documents", indexes = {
    @Index(name = "idx_tax_invoice_pdf_tax_invoice_id", columnList = "tax_invoice_id"),
    @Index(name = "idx_tax_invoice_pdf_tax_invoice_number", columnList = "tax_invoice_number"),
    @Index(name = "idx_tax_invoice_pdf_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxInvoicePdfDocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tax_invoice_id", nullable = false, length = 100)
    private String taxInvoiceId;

    @Column(name = "tax_invoice_number", nullable = false, length = 50)
    private String taxInvoiceNumber;

    @Column(name = "document_path", length = 500)
    private String documentPath;

    @Column(name = "document_url", length = 1000)
    private String documentUrl;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "xml_embedded", nullable = false)
    private Boolean xmlEmbedded;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = GenerationStatus.PENDING;
        }
        if (mimeType == null) {
            mimeType = "application/pdf";
        }
        if (xmlEmbedded == null) {
            xmlEmbedded = false;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}
