package com.wpanther.taxinvoice.pdf.application.usecase;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;

public interface ProcessTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceProcessCommand command);
}
