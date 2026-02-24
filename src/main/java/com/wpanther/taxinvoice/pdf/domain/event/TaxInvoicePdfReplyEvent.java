package com.wpanther.taxinvoice.pdf.domain.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;

/**
 * Saga reply event for tax invoice PDF generation service.
 * Published to Kafka topic: saga.reply.tax-invoice-pdf
 *
 * SUCCESS replies include pdfUrl and pdfSize so the orchestrator
 * can forward the MinIO URL to the PDF_STORAGE step.
 */
public class TaxInvoicePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    // Additional fields included in SUCCESS replies
    private String pdfUrl;
    private Long pdfSize;

    public static TaxInvoicePdfReplyEvent success(
            String sagaId, SagaStep sagaStep, String correlationId,
            String pdfUrl, Long pdfSize) {
        TaxInvoicePdfReplyEvent reply = new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
        reply.pdfUrl = pdfUrl;
        reply.pdfSize = pdfSize;
        return reply;
    }

    public static TaxInvoicePdfReplyEvent failure(String sagaId, SagaStep sagaStep, String correlationId,
                                                  String errorMessage) {
        return new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static TaxInvoicePdfReplyEvent compensated(String sagaId, SagaStep sagaStep, String correlationId) {
        return new TaxInvoicePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private TaxInvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private TaxInvoicePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public Long getPdfSize() {
        return pdfSize;
    }
}
