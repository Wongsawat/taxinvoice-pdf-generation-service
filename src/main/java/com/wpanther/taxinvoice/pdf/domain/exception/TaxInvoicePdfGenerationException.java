package com.wpanther.taxinvoice.pdf.domain.exception;

public class TaxInvoicePdfGenerationException extends RuntimeException {

    public TaxInvoicePdfGenerationException(String message) {
        super(message);
    }

    public TaxInvoicePdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
