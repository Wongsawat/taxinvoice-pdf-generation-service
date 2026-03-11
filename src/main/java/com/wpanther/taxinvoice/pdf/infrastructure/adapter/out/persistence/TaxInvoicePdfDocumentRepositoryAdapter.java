package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of the domain TaxInvoicePdfDocumentRepository.
 * Owns all entity↔domain mapping, keeping infrastructure details out of
 * the application and domain layers.
 */
@Repository
@RequiredArgsConstructor
public class TaxInvoicePdfDocumentRepositoryAdapter implements TaxInvoicePdfDocumentRepository {

    private final JpaTaxInvoicePdfDocumentRepository jpaRepository;

    @Override
    public TaxInvoicePdfDocument save(TaxInvoicePdfDocument document) {
        TaxInvoicePdfDocumentEntity entity = toEntity(document);
        entity = jpaRepository.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<TaxInvoicePdfDocument> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<TaxInvoicePdfDocument> findByTaxInvoiceId(String taxInvoiceId) {
        return jpaRepository.findByTaxInvoiceId(taxInvoiceId).map(this::toDomain);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private TaxInvoicePdfDocumentEntity toEntity(TaxInvoicePdfDocument document) {
        return TaxInvoicePdfDocumentEntity.builder()
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
    }

    private TaxInvoicePdfDocument toDomain(TaxInvoicePdfDocumentEntity entity) {
        return TaxInvoicePdfDocument.builder()
            .id(entity.getId())
            .taxInvoiceId(entity.getTaxInvoiceId())
            .taxInvoiceNumber(entity.getTaxInvoiceNumber())
            .documentPath(entity.getDocumentPath())
            .documentUrl(entity.getDocumentUrl())
            .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0L)
            .mimeType(entity.getMimeType())
            .xmlEmbedded(entity.getXmlEmbedded() != null && entity.getXmlEmbedded())
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .retryCount(entity.getRetryCount() != null ? entity.getRetryCount() : 0)
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .build();
    }
}
