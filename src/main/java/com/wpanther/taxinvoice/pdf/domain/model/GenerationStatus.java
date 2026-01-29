package com.wpanther.taxinvoice.pdf.domain.model;

/**
 * Enum representing the status of PDF generation
 */
public enum GenerationStatus {
    /**
     * PDF generation is pending
     */
    PENDING,

    /**
     * PDF is being generated
     */
    GENERATING,

    /**
     * PDF generation completed successfully
     */
    COMPLETED,

    /**
     * PDF generation failed
     */
    FAILED
}
