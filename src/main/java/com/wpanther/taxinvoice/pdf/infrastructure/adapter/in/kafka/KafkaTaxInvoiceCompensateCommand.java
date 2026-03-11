package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

public class KafkaTaxInvoiceCompensateCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @Getter
    @JsonProperty("documentId")   private final String documentId;
    @Getter
    @JsonProperty("taxInvoiceId") private final String taxInvoiceId;

    @JsonCreator
    public KafkaTaxInvoiceCompensateCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceCompensateCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                            String documentId, String taxInvoiceId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }

    // Explicit getters for parent class fields (SagaCommand doesn't use @Getter)
    @Override
    public String getSagaId() {
        return super.getSagaId();
    }

    @Override
    public SagaStep getSagaStep() {
        return super.getSagaStep();
    }

    @Override
    public String getCorrelationId() {
        return super.getCorrelationId();
    }

    // Explicit getters (Lombok @Getter might not be processed)
    public String getDocumentId() {
        return documentId;
    }

    public String getTaxInvoiceId() {
        return taxInvoiceId;
    }
}
