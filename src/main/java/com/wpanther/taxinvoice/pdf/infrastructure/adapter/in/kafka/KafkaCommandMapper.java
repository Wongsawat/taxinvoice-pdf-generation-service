package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import org.springframework.stereotype.Component;

@Component
public class KafkaCommandMapper {

    public KafkaTaxInvoiceProcessCommand toProcess(KafkaTaxInvoiceProcessCommand src) {
        // Wire DTO IS the command — identity mapping (same type used at use-case boundary)
        return src;
    }

    public KafkaTaxInvoiceCompensateCommand toCompensate(KafkaTaxInvoiceCompensateCommand src) {
        return src;
    }
}
