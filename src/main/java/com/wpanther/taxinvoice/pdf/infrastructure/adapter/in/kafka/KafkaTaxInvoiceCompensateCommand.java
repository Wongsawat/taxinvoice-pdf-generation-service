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

    @JsonCreator
    public KafkaTaxInvoiceCompensateCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceCompensateCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                            String documentId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
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

    // Explicit getter for documentId (Lombok @Getter might not be processed)
    public String getDocumentId() {
        return documentId;
    }
}
