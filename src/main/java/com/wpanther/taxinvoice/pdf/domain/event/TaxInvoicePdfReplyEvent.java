package com.wpanther.taxinvoice.pdf.domain.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.model.SagaReply;

/**
 * Saga reply event for tax invoice PDF generation service.
 * Published to Kafka topic: saga.reply.tax-invoice-pdf
 */
public class TaxInvoicePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    public static TaxInvoicePdfReplyEvent success(String sagaId, String sagaStep, String correlationId) {
        return new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
    }

    public static TaxInvoicePdfReplyEvent failure(String sagaId, String sagaStep, String correlationId,
                                                  String errorMessage) {
        return new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static TaxInvoicePdfReplyEvent compensated(String sagaId, String sagaStep, String correlationId) {
        return new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private TaxInvoicePdfReplyEvent(String sagaId, String sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private TaxInvoicePdfReplyEvent(String sagaId, String sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }
}
