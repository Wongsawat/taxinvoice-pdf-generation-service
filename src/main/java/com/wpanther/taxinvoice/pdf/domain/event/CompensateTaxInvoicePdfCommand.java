package com.wpanther.taxinvoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensation command from Saga Orchestrator to undo PDF generation.
 * Consumed from Kafka topic: saga.compensation.tax-invoice-pdf
 */
@Getter
public class CompensateTaxInvoicePdfCommand extends IntegrationEvent {

    private static final long serialVersionUID = 1L;

    @JsonProperty("sagaId")
    private final String sagaId;

    @JsonProperty("sagaStep")
    private final String sagaStep;

    @JsonProperty("correlationId")
    private final String correlationId;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("taxInvoiceId")
    private final String taxInvoiceId;

    @JsonCreator
    public CompensateTaxInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") String sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId) {
        super(eventId, occurredAt, eventType, version);
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }

    /**
     * Convenience constructor for testing.
     */
    public CompensateTaxInvoicePdfCommand(String sagaId, String sagaStep, String correlationId,
                                           String documentId, String taxInvoiceId) {
        super();
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }
}
