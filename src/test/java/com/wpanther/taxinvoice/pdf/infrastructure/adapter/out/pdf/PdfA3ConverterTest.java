package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for PdfA3Converter PDF/A-3 conversion functionality.
 *
 * <p>These tests verify basic converter behavior. Full PDF/A-3 compliance
 * testing requires specialized validation tools.</p>
 */
@DisplayName("PdfA3Converter Unit Tests")
class PdfA3ConverterTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("Constructor creates converter instance")
    void constructor_createsInstance() {
        PdfA3Converter converter = new PdfA3Converter("icc/sRGB.icc", meterRegistry);
        assertThat(converter).isNotNull();
    }

    @Test
    @DisplayName("convertToPdfA3() throws PdfConversionException for null input")
    void testConvertToPdfA3_NullInput_Throws() {
        PdfA3Converter converter = new PdfA3Converter("icc/sRGB.icc", meterRegistry);

        assertThatThrownBy(() ->
                converter.convertToPdfA3(null, "<xml/>", "test.xml", "TXINV-001"))
                .isInstanceOf(PdfA3Converter.PdfConversionException.class);
    }

    @Test
    @DisplayName("convertToPdfA3() throws PdfConversionException for empty PDF bytes")
    void testConvertToPdfA3_EmptyPdf_Throws() {
        PdfA3Converter converter = new PdfA3Converter("icc/sRGB.icc", meterRegistry);

        assertThatThrownBy(() ->
                converter.convertToPdfA3(new byte[0], "<xml/>", "test.xml", "TXINV-001"))
                .isInstanceOf(PdfA3Converter.PdfConversionException.class);
    }

    @Test
    @DisplayName("PdfConversionException can be created with message")
    void testPdfConversionException_Message() {
        PdfA3Converter.PdfConversionException exception =
                new PdfA3Converter.PdfConversionException("Test error");

        assertThat(exception).hasMessage("Test error");
    }

    @Test
    @DisplayName("PdfConversionException can be created with message and cause")
    void testPdfConversionException_MessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        PdfA3Converter.PdfConversionException exception =
                new PdfA3Converter.PdfConversionException("Test error", cause);

        assertThat(exception).hasMessage("Test error");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
