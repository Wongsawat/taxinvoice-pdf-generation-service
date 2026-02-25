package com.wpanther.taxinvoice.pdf.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaTaxInvoicePdfDocumentRepository extends JpaRepository<TaxInvoicePdfDocumentEntity, UUID> {

    Optional<TaxInvoicePdfDocumentEntity> findByTaxInvoiceId(String taxInvoiceId);
}
