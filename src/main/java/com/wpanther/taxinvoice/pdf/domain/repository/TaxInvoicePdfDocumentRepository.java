package com.wpanther.taxinvoice.pdf.domain.repository;

import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for TaxInvoicePdfDocument aggregate
 */
public interface TaxInvoicePdfDocumentRepository {

    /**
     * Save tax invoice PDF document
     */
    TaxInvoicePdfDocument save(TaxInvoicePdfDocument document);

    /**
     * Find by ID
     */
    Optional<TaxInvoicePdfDocument> findById(UUID id);

    /**
     * Find by tax invoice ID
     */
    Optional<TaxInvoicePdfDocument> findByTaxInvoiceId(String taxInvoiceId);

    /**
     * Delete by ID
     */
    void deleteById(UUID id);
}
