package com.wpanther.taxinvoice.pdf.domain.constants;

/**
 * Constants for PDF generation configuration.
 */
public final class PdfGenerationConstants {

    private PdfGenerationConstants() {
        // Utility class - prevent instantiation
    }

    /**
     * Default maximum number of retry attempts for PDF generation.
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * MIME type for PDF documents.
     */
    public static final String PDF_MIME_TYPE = "application/pdf";
}
