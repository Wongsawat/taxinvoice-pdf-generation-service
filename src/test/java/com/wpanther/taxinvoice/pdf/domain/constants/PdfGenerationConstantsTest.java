package com.wpanther.taxinvoice.pdf.domain.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PdfGenerationConstants Unit Tests")
class PdfGenerationConstantsTest {

    @Test
    @DisplayName("DEFAULT_MAX_RETRIES should be 3")
    void testDefaultMaxRetries() {
        assertThat(PdfGenerationConstants.DEFAULT_MAX_RETRIES).isEqualTo(3);
    }

    @Test
    @DisplayName("PDF_MIME_TYPE should be 'application/pdf'")
    void testPdfMimeType() {
        assertThat(PdfGenerationConstants.PDF_MIME_TYPE).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("Constructor should be private - utility class pattern")
    void testConstructorIsPrivate() throws Exception {
        // Utility classes should have private constructors
        var constructor = PdfGenerationConstants.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }
}
