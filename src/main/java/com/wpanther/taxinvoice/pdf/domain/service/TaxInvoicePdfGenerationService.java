package com.wpanther.taxinvoice.pdf.domain.service;

/**
 * Domain service for Tax Invoice PDF generation
 */
public interface TaxInvoicePdfGenerationService {

    /**
     * Generate PDF from tax invoice data
     *
     * @param taxInvoiceNumber Tax invoice number
     * @param xmlContent XML content to embed
     * @param taxInvoiceDataJson JSON data for template
     * @return PDF bytes
     * @throws TaxInvoicePdfGenerationException if generation fails
     */
    byte[] generatePdf(String taxInvoiceNumber, String xmlContent, String taxInvoiceDataJson)
        throws TaxInvoicePdfGenerationException;

    /**
     * Exception thrown when PDF generation fails
     */
    class TaxInvoicePdfGenerationException extends Exception {
        public TaxInvoicePdfGenerationException(String message) {
            super(message);
        }

        public TaxInvoicePdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
