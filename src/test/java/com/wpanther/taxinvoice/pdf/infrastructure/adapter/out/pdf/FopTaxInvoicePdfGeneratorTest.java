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
}
