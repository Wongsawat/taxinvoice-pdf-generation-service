package com.wpanther.taxinvoice.pdf.application.usecase;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;

public interface CompensateTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceCompensateCommand command);
}
