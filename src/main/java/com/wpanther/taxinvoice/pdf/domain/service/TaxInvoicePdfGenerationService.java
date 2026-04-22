package com.wpanther.taxinvoice.pdf.domain.service;

public interface TaxInvoicePdfGenerationService {

    /**
     * Generate PDF/A-3 from the signed XML document.
     *
     * @param taxInvoiceNumber document number (used for logging and file naming)
     * @param signedXml        full Thai e-Tax signed XML (rsm:TaxInvoice_CrossIndustryInvoice)
     * @return PDF/A-3 bytes with the signed XML embedded as an attachment
     * @throws TaxInvoicePdfGenerationException if generation fails
     */
    byte[] generatePdf(String taxInvoiceNumber, String signedXml)
        throws TaxInvoicePdfGenerationException;

    class TaxInvoicePdfGenerationException extends Exception {
        public TaxInvoicePdfGenerationException(String message) { super(message); }
        public TaxInvoicePdfGenerationException(String message, Throwable cause) { super(message, cause); }
    }
}
