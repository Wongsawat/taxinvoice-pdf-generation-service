package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaTaxInvoicePdfDocumentRepositoryImplTest {

    @Mock
    private JpaTaxInvoicePdfDocumentRepository jpaRepository;

    @InjectMocks
    private TaxInvoicePdfDocumentRepositoryAdapter repository;

    private UUID id;
    private TaxInvoicePdfDocument domain;
    private TaxInvoicePdfDocumentEntity entity;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        domain = TaxInvoicePdfDocument.builder()
                .id(id)
                .taxInvoiceId("tax-inv-123")
                .taxInvoiceNumber("TXINV-2024-001")
                .documentPath("2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .fileSize(12345L)
                .mimeType("application/pdf")
                .xmlEmbedded(true)
                .status(GenerationStatus.COMPLETED)
                .retryCount(1)
                .createdAt(now)
                .completedAt(now)
                .build();

        entity = TaxInvoicePdfDocumentEntity.builder()
                .id(id)
                .taxInvoiceId("tax-inv-123")
                .taxInvoiceNumber("TXINV-2024-001")
                .documentPath("2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .fileSize(12345L)
                .mimeType("application/pdf")
                .xmlEmbedded(true)
                .status(GenerationStatus.COMPLETED)
                .errorMessage(null)
                .retryCount(1)
                .createdAt(now)
                .completedAt(now)
                .build();
    }

    // -------------------------------------------------------------------------
    // toEntity mapping
    // -------------------------------------------------------------------------

    @Test
    void save_mapsAllDomainFieldsToEntity() {
        when(jpaRepository.save(any())).thenReturn(entity);

        repository.save(domain);

        ArgumentCaptor<TaxInvoicePdfDocumentEntity> captor =
                ArgumentCaptor.forClass(TaxInvoicePdfDocumentEntity.class);
        verify(jpaRepository).save(captor.capture());

        TaxInvoicePdfDocumentEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getTaxInvoiceId()).isEqualTo("tax-inv-123");
        assertThat(saved.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(saved.getDocumentPath()).isEqualTo("2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf");
        assertThat(saved.getDocumentUrl()).contains("taxinvoice-TXINV-2024-001-abc.pdf");
        assertThat(saved.getFileSize()).isEqualTo(12345L);
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
        assertThat(saved.getXmlEmbedded()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(saved.getRetryCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    @Test
    void save_mapsAllEntityFieldsToDomain() {
        when(jpaRepository.save(any())).thenReturn(entity);

        TaxInvoicePdfDocument result = repository.save(domain);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getTaxInvoiceId()).isEqualTo("tax-inv-123");
        assertThat(result.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(result.getDocumentPath()).isEqualTo("2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf");
        assertThat(result.getDocumentUrl()).contains("taxinvoice-TXINV-2024-001-abc.pdf");
        assertThat(result.getFileSize()).isEqualTo(12345L);
        assertThat(result.getMimeType()).isEqualTo("application/pdf");
        assertThat(result.isXmlEmbedded()).isTrue();
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getRetryCount()).isEqualTo(1);
    }

    @Test
    void toDomain_nullFileSize_defaultsToZero() {
        entity.setFileSize(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        TaxInvoicePdfDocument result = repository.save(domain);

        assertThat(result.getFileSize()).isZero();
    }

    @Test
    void toDomain_nullXmlEmbedded_defaultsToFalse() {
        entity.setXmlEmbedded(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        TaxInvoicePdfDocument result = repository.save(domain);

        assertThat(result.isXmlEmbedded()).isFalse();
    }

    @Test
    void toDomain_nullRetryCount_defaultsToZero() {
        entity.setRetryCount(null);
        when(jpaRepository.save(any())).thenReturn(entity);

        TaxInvoicePdfDocument result = repository.save(domain);

        assertThat(result.getRetryCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // findById
    // -------------------------------------------------------------------------

    @Test
    void findById_found_returnsMappedDomain() {
        when(jpaRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<TaxInvoicePdfDocument> result = repository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }

    @Test
    void findById_notFound_returnsEmpty() {
        when(jpaRepository.findById(id)).thenReturn(Optional.empty());

        Optional<TaxInvoicePdfDocument> result = repository.findById(id);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // findByTaxInvoiceId
    // -------------------------------------------------------------------------

    @Test
    void findByTaxInvoiceId_found_returnsMappedDomain() {
        when(jpaRepository.findByTaxInvoiceId("tax-inv-123")).thenReturn(Optional.of(entity));

        Optional<TaxInvoicePdfDocument> result = repository.findByTaxInvoiceId("tax-inv-123");

        assertThat(result).isPresent();
        assertThat(result.get().getTaxInvoiceId()).isEqualTo("tax-inv-123");
    }

    @Test
    void findByTaxInvoiceId_notFound_returnsEmpty() {
        when(jpaRepository.findByTaxInvoiceId("unknown")).thenReturn(Optional.empty());

        Optional<TaxInvoicePdfDocument> result = repository.findByTaxInvoiceId("unknown");

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deleteById
    // -------------------------------------------------------------------------

    @Test
    void deleteById_delegatesToJpaRepository() {
        repository.deleteById(id);

        verify(jpaRepository).deleteById(id);
    }

    // -------------------------------------------------------------------------
    // flush
    // -------------------------------------------------------------------------

    @Test
    void flush_delegatesToJpaRepository() {
        repository.flush();

        verify(jpaRepository).flush();
    }
}
