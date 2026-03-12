package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfDocumentRepositoryAdapter Unit Tests")
class JpaTaxInvoicePdfDocumentRepositoryImplTest {

    @Mock
    private JpaTaxInvoicePdfDocumentRepository jpaRepository;

    private TaxInvoicePdfDocumentRepositoryAdapter repository;

    private UUID id;
    private TaxInvoicePdfDocument domain;

    @BeforeEach
    void setUp() {
        repository = new TaxInvoicePdfDocumentRepositoryAdapter(jpaRepository);
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
    }

    @Test
    @DisplayName("save() maps domain to entity and back")
    void testSave_roundTrip() {
        // Given
        TaxInvoicePdfDocumentEntity entity = TaxInvoicePdfDocumentEntity.builder()
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
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();
        when(jpaRepository.save(any())).thenReturn(entity);

        // When
        TaxInvoicePdfDocument result = repository.save(domain);

        // Then
        verify(jpaRepository).save(any(TaxInvoicePdfDocumentEntity.class));
        assertThat(result.getTaxInvoiceId()).isEqualTo("tax-inv-123");
        assertThat(result.getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
        assertThat(result.getFileSize()).isEqualTo(12345L);
        assertThat(result.isXmlEmbedded()).isTrue();
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("findById() returns mapped domain when found")
    void testFoundById_found() {
        // Given
        TaxInvoicePdfDocumentEntity entity = TaxInvoicePdfDocumentEntity.builder()
                .id(id)
                .taxInvoiceId("tax-inv-123")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .fileSize(12345L)
                .xmlEmbedded(true)
                .retryCount(0)
                .mimeType("application/pdf")
                .build();
        when(jpaRepository.findById(id)).thenReturn(Optional.of(entity));

        // When
        Optional<TaxInvoicePdfDocument> result = repository.findById(id);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getTaxInvoiceId()).isEqualTo("tax-inv-123");
        assertThat(result.get().getTaxInvoiceNumber()).isEqualTo("TXINV-2024-001");
    }

    @Test
    @DisplayName("findById() returns empty when not found")
    void testFoundById_notFound() {
        when(jpaRepository.findById(id)).thenReturn(Optional.empty());

        Optional<TaxInvoicePdfDocument> result = repository.findById(id);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByTaxInvoiceId() returns mapped domain when found")
    void testFindByTaxInvoiceId_found() {
        // Given
        TaxInvoicePdfDocumentEntity entity = TaxInvoicePdfDocumentEntity.builder()
                .id(id)
                .taxInvoiceId("tax-inv-123")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .fileSize(12345L)
                .xmlEmbedded(true)
                .retryCount(0)
                .mimeType("application/pdf")
                .build();
        when(jpaRepository.findByTaxInvoiceId("tax-inv-123")).thenReturn(Optional.of(entity));

        // When
        Optional<TaxInvoicePdfDocument> result = repository.findByTaxInvoiceId("tax-inv-123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getTaxInvoiceId()).isEqualTo("tax-inv-123");
    }

    @Test
    @DisplayName("findByTaxInvoiceId() returns empty when not found")
    void testFindByTaxInvoiceId_notFound() {
        when(jpaRepository.findByTaxInvoiceId("unknown")).thenReturn(Optional.empty());

        Optional<TaxInvoicePdfDocument> result = repository.findByTaxInvoiceId("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteById() delegates to JPA repository")
    void testDeleteById() {
        repository.deleteById(id);

        verify(jpaRepository).deleteById(id);
    }

    @Test
    @DisplayName("flush() delegates to JPA repository")
    void testFlush() {
        repository.flush();

        verify(jpaRepository).flush();
    }
}
