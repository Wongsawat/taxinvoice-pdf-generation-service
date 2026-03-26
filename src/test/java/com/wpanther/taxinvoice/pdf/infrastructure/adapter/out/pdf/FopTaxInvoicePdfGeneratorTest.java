package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for FopTaxInvoicePdfGenerator construction and URI resolution.
 *
 * <p>Full PDF generation is not tested here (requires real Thai fonts). These
 * tests verify that the component initialises correctly — template caching and
 * base-URI resolution — so deployment failures are caught early.</p>
 */
@DisplayName("FopTaxInvoicePdfGenerator Unit Tests")
class FopTaxInvoicePdfGeneratorTest {

    @Test
    @DisplayName("Constructor succeeds and compiles XSL template")
    void constructor_compilesTemplateSuccessfully() {
        // No exception = template found and compiled
        assertThatCode(() -> new FopTaxInvoicePdfGenerator(2, 52428800L, new SimpleMeterRegistry()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor rejects maxConcurrentRenders < 1 with IllegalStateException")
    void constructor_invalidMaxConcurrentRenders_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopTaxInvoicePdfGenerator(0, 52428800L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-renders")
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("Constructor rejects maxPdfSizeBytes < 1 with IllegalStateException")
    void constructor_invalidMaxPdfSizeBytes_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopTaxInvoicePdfGenerator(1, 0L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-pdf-size-bytes")
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("Semaphore is initialised with the configured permit count")
    void constructor_semaphorePermitsMatchConfiguration() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(5, 52428800L, new SimpleMeterRegistry());
        Field f = FopTaxInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore s = (Semaphore) f.get(gen);
        assertThat(s.availablePermits()).isEqualTo(5);
        assertThat(s.isFair()).isTrue();
    }

    @Test
    @DisplayName("checkFontAvailability() does not throw regardless of font presence")
    void checkFontAvailability_doesNotThrow() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        // Method logs info (fonts present) or warn (fonts absent) — never throws.
        assertThatCode(() -> gen.checkFontAvailability()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PdfGenerationException(String) 1-arg constructor carries the message")
    void pdfGenerationException_messageOnlyConstructor_hasMessage() {
        var ex = new FopTaxInvoicePdfGenerator.PdfGenerationException("FOP failed");
        assertThat(ex.getMessage()).isEqualTo("FOP failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("generatePdf() on an interrupted thread throws PdfGenerationException")
    void generatePdf_threadAlreadyInterrupted_throwsPdfGenerationException() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        Thread.currentThread().interrupt();  // mark thread as interrupted before acquire()
        try {
            assertThatThrownBy(() -> gen.generatePdf("<taxInvoice/>"))
                    .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();  // restore clean interrupted status for subsequent tests
        }
    }

    @Test
    @DisplayName("Semaphore blocks callers when all permits are held")
    void generatePdf_semaphoreBlocksWhenAtCapacity() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());

        // Drain the single permit so the next caller must wait
        Field f = FopTaxInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore sem = (Semaphore) f.get(gen);
        sem.acquire();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = exec.submit(() -> {
                try {
                    gen.generatePdf("<taxInvoice/>");
                } catch (FopTaxInvoicePdfGenerator.PdfGenerationException ignored) {
                    // expected once permit is released — not what we are testing here
                }
            });

            // While the permit is held, the task must not complete
            assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // Release permit → task unblocks and finishes (may fail on bad XML, that is fine)
            sem.release();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("resolveBaseUri() returns a non-null URI ending with '/'")
    void resolveBaseUri_returnsValidUri() throws Exception {
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator(2, 52428800L, new SimpleMeterRegistry());

        Method method = FopTaxInvoicePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);

        assertThat(uri).isNotNull();
        assertThat(uri.toString()).endsWith("/");
    }

    @Test
    @DisplayName("resolveBaseUri() returns an absolute URI (not relative)")
    void resolveBaseUri_returnsAbsoluteUri() throws Exception {
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator(2, 52428800L, new SimpleMeterRegistry());

        Method method = FopTaxInvoicePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);

        assertThat(uri.isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("Valid tax invoice XML → returns non-empty PDF bytes starting with %PDF")
    void generatePdf_validXml_returnsPdfBytes() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        String xml = "<taxInvoice>"
                + "<taxInvoiceNumber>TINV-TEST-001</taxInvoiceNumber>"
                + "<seller><name>Test Seller</name><address>1 Test Rd</address>"
                + "<taxId>1234567890123</taxId></seller>"
                + "<buyer><name>Test Buyer</name><address>2 Test Rd</address>"
                + "<taxId>9876543210987</taxId></buyer>"
                + "<lineItems><item><description>Widget</description>"
                + "<quantity>1</quantity><unit>EA</unit>"
                + "<unitPrice>1000</unitPrice><amount>1000</amount></item></lineItems>"
                + "<subtotal>1000</subtotal><amountBeforeVat>1000</amountBeforeVat>"
                + "<vatRate>7</vatRate><vatAmount>70</vatAmount><grandTotal>1070</grandTotal>"
                + "</taxInvoice>";

        byte[] result = gen.generatePdf(xml);

        assertThat(result).isNotEmpty();
        // All PDF files start with the %PDF header
        assertThat(new String(result, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("Malformed XML → PdfGenerationException")
    void generatePdf_malformedXml_throwsPdfGenerationException() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf("this is not xml <<<"))
                .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class);
    }

    @Test
    @DisplayName("generatePdf() throws PdfGenerationException when PDF exceeds max size")
    void generatePdf_pdfExceedsMaxSize_throwsPdfGenerationException() throws Exception {
        // Set a 1-byte limit so any real PDF will exceed it
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 1L, new SimpleMeterRegistry());
        String xml = "<taxInvoice>"
                + "<taxInvoiceNumber>TINV-TOOBIG</taxInvoiceNumber>"
                + "<seller><name>S</name><address>A</address><taxId>123</taxId></seller>"
                + "<buyer><name>B</name><address>A</address><taxId>987</taxId></buyer>"
                + "<lineItems/>"
                + "<subtotal>0</subtotal><amountBeforeVat>0</amountBeforeVat>"
                + "<vatRate>7</vatRate><vatAmount>0</vatAmount><grandTotal>0</grandTotal>"
                + "</taxInvoice>";

        assertThatThrownBy(() -> gen.generatePdf(xml))
                .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class)
                .hasMessageContaining("exceeds max allowed size");
    }
}
