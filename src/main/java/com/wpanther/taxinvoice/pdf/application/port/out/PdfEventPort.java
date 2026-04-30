package com.wpanther.taxinvoice.pdf.application.port.out;

import com.wpanther.taxinvoice.pdf.application.dto.event.TaxInvoicePdfGeneratedEvent;

public interface PdfEventPort {
    void publishPdfGenerated(TaxInvoicePdfGeneratedEvent event);
}
