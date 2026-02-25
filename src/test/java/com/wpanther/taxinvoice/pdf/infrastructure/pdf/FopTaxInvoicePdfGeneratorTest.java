package com.wpanther.taxinvoice.pdf.infrastructure.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("Constructor succeeds: FOP factory and XSL templates are loaded from classpath")
    void constructor_loadsTemplatesAndFopFactory() throws Exception {
        // Verifies resolveBaseUri(), createFopFactory(), and compileTemplates() all succeed.
        // If the XSL template or fop.xconf is missing from the classpath this will throw.
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator();
        assertThat(generator).isNotNull();
    }

    @Test
    @DisplayName("resolveBaseUri() returns a non-null URI ending with '/'")
    void resolveBaseUri_returnsValidUri() throws Exception {
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator();

        Method method = FopTaxInvoicePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);

        assertThat(uri).isNotNull();
        assertThat(uri.toString()).endsWith("/");
    }

    @Test
    @DisplayName("resolveBaseUri() returns an absolute URI (not relative)")
    void resolveBaseUri_returnsAbsoluteUri() throws Exception {
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator();

        Method method = FopTaxInvoicePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);

        assertThat(uri.isAbsolute()).isTrue();
    }
}
