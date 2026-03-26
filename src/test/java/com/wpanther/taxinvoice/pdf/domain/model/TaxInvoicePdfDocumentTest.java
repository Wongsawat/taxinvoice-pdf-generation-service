package com.wpanther.taxinvoice.pdf.domain.model;

import com.wpanther.taxinvoice.pdf.domain.exception.TaxInvoicePdfGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TaxInvoicePdfDocument Aggregate Tests")
class TaxInvoicePdfDocumentTest {

    private TaxInvoicePdfDocument pendingDocument() {
        return TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .build();
    }

    // -------------------------------------------------------------------------
    // Builder / invariants
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should create document in PENDING status with defaults")
    void testCreate_Defaults() {
        TaxInvoicePdfDocument doc = pendingDocument();

        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(doc.getMimeType()).isEqualTo("application/pdf");
        assertThat(doc.getRetryCount()).isZero();
        assertThat(doc.isXmlEmbedded()).isFalse();
        assertThat(doc.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should reject blank taxInvoiceId")
    void testCreate_BlankTaxInvoiceId() {
        assertThatThrownBy(() ->
                TaxInvoicePdfDocument.builder()
                        .taxInvoiceId("   ")
                        .taxInvoiceNumber("TXINV-001")
                        .build()
        ).isInstanceOf(TaxInvoicePdfGenerationException.class)
         .hasMessageContaining("Tax Invoice ID cannot be blank");
    }

    @Test
    @DisplayName("Should reject null taxInvoiceNumber")
    void testCreate_NullTaxInvoiceNumber() {
        assertThatThrownBy(() ->
                TaxInvoicePdfDocument.builder()
                        .taxInvoiceId("tax-inv-001")
                        .taxInvoiceNumber(null)
                        .build()
        ).isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // State machine — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PENDING → startGeneration() → GENERATING")
    void testStartGeneration() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.GENERATING);
    }

    @Test
    @DisplayName("GENERATING → markCompleted() → COMPLETED")
    void testMarkCompleted() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();
        doc.markCompleted("2024/01/15/test.pdf", "http://minio/test.pdf", 12345L);

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(doc.getDocumentPath()).isEqualTo("2024/01/15/test.pdf");
        assertThat(doc.getDocumentUrl()).isEqualTo("http://minio/test.pdf");
        assertThat(doc.getFileSize()).isEqualTo(12345L);
        assertThat(doc.getCompletedAt()).isNotNull();
        assertThat(doc.isCompleted()).isTrue();
        assertThat(doc.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("Any state → markFailed() → FAILED")
    void testMarkFailed_FromPending() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.markFailed("Something went wrong");

        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("Something went wrong");
        assertThat(doc.isFailed()).isTrue();
        assertThat(doc.isCompleted()).isFalse();
        assertThat(doc.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING → markXmlEmbedded() sets flag")
    void testMarkXmlEmbedded() {
        TaxInvoicePdfDocument doc = pendingDocument();
        assertThat(doc.isXmlEmbedded()).isFalse();
        doc.markXmlEmbedded();
        assertThat(doc.isXmlEmbedded()).isTrue();
    }

    // -------------------------------------------------------------------------
    // State machine — invalid transitions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startGeneration() from GENERATING throws IllegalStateException")
    void testStartGeneration_AlreadyGenerating() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(doc::startGeneration)
                .isInstanceOf(TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("markCompleted() from PENDING throws IllegalStateException")
    void testMarkCompleted_FromPending() {
        TaxInvoicePdfDocument doc = pendingDocument();

        assertThatThrownBy(() -> doc.markCompleted("path", "url", 100L))
                .isInstanceOf(TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("GENERATING");
    }

    @Test
    @DisplayName("markCompleted() with zero fileSize throws IllegalArgumentException")
    void testMarkCompleted_ZeroFileSize() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(() -> doc.markCompleted("path", "url", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File size must be positive");
    }

    @Test
    @DisplayName("markCompleted() with null documentPath throws NullPointerException")
    void testMarkCompleted_NullPath() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.startGeneration();

        assertThatThrownBy(() -> doc.markCompleted(null, "url", 100L))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Retry tracking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("incrementRetryCount() increases count by one")
    void testIncrementRetryCount() {
        TaxInvoicePdfDocument doc = pendingDocument();
        assertThat(doc.getRetryCount()).isZero();
        doc.incrementRetryCount();
        assertThat(doc.getRetryCount()).isOne();
        doc.incrementRetryCount();
        assertThat(doc.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementRetryCountTo() advances count to target")
    void testIncrementRetryCountTo_AdvancesToTarget() {
        TaxInvoicePdfDocument doc = pendingDocument();
        doc.incrementRetryCountTo(2);
        assertThat(doc.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementRetryCountTo() is a no-op when count already at target")
    void testIncrementRetryCountTo_NoOpWhenAlreadyAtTarget() {
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .retryCount(2)
                .build();
        doc.incrementRetryCountTo(1);
        assertThat(doc.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("isMaxRetriesExceeded() returns true when retryCount >= maxRetries")
    void testIsMaxRetriesExceeded_AtLimit() {
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .retryCount(3)
                .build();
        assertThat(doc.isMaxRetriesExceeded(3)).isTrue();
    }

    @Test
    @DisplayName("isMaxRetriesExceeded() returns false when retryCount < maxRetries")
    void testIsMaxRetriesExceeded_BelowLimit() {
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .retryCount(2)
                .build();
        assertThat(doc.isMaxRetriesExceeded(3)).isFalse();
    }
}
