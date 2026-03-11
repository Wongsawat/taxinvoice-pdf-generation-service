package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

public class KafkaTaxInvoiceProcessCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @Getter
    @JsonProperty("documentId")   private final String documentId;
    @Getter
    @JsonProperty("taxInvoiceId") private final String taxInvoiceId;
    @Getter
    @JsonProperty("taxInvoiceNumber") private final String taxInvoiceNumber;
    @Getter
    @JsonProperty("signedXmlUrl") private final String signedXmlUrl;
    @Getter
    @JsonProperty("taxInvoiceDataJson") private final String taxInvoiceDataJson;

    @JsonCreator
    public KafkaTaxInvoiceProcessCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId,
            @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl,
            @JsonProperty("taxInvoiceDataJson") String taxInvoiceDataJson) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceProcessCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                         String documentId, String taxInvoiceId, String taxInvoiceNumber,
                                         String signedXmlUrl, String taxInvoiceDataJson) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
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

    public String getTaxInvoiceId() {
        return taxInvoiceId;
    }

    public String getTaxInvoiceNumber() {
        return taxInvoiceNumber;
    }

    public String getSignedXmlUrl() {
        return signedXmlUrl;
    }

    public String getTaxInvoiceDataJson() {
        return taxInvoiceDataJson;
    }
}
