package com.wpanther.taxinvoice.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for tax invoice PDF compensation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface CompensateTaxInvoicePdfUseCase {

    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
