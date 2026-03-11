package com.wpanther.taxinvoice.pdf.application.port.out;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;

public interface PdfEventPort {
    void publishPdfGenerated(TaxInvoicePdfGeneratedEvent event);
}
