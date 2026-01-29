package com.wpanther.taxinvoice.pdf.infrastructure.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaTaxInvoicePdfDocumentRepository extends JpaRepository<TaxInvoicePdfDocumentEntity, UUID> {

    Optional<TaxInvoicePdfDocumentEntity> findByTaxInvoiceId(String taxInvoiceId);

    List<TaxInvoicePdfDocumentEntity> findByStatus(GenerationStatus status);

    boolean existsByTaxInvoiceId(String taxInvoiceId);
}
