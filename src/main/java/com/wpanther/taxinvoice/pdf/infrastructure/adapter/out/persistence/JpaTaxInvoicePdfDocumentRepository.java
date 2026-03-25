package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface JpaTaxInvoicePdfDocumentRepository extends JpaRepository<TaxInvoicePdfDocumentEntity, UUID> {

    Optional<TaxInvoicePdfDocumentEntity> findByTaxInvoiceId(String taxInvoiceId);

    /**
     * Find all non-null document paths for orphaned PDF detection.
     * <p>
     * Used by periodic cleanup job to reconcile MinIO objects against database records.
     *
     * @return set of all S3 keys stored in the database
     */
    @Query("SELECT e.documentPath FROM TaxInvoicePdfDocumentEntity e WHERE e.documentPath IS NOT NULL")
    Set<String> findAllDocumentPaths();
}
