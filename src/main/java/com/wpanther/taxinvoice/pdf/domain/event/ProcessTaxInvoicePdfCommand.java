package com.wpanther.taxinvoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Command received from Saga Orchestrator to generate tax invoice PDF.
 * Consumed from Kafka topic: saga.command.tax-invoice-pdf
 */
@Getter
public class ProcessTaxInvoicePdfCommand extends IntegrationEvent {

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

    @JsonProperty("taxInvoiceNumber")
    private final String taxInvoiceNumber;

    @JsonProperty("signedXmlUrl")
    private final String signedXmlUrl;

    @JsonProperty("taxInvoiceDataJson")
    private final String taxInvoiceDataJson;

    @JsonCreator
    public ProcessTaxInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") String sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId,
            @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl,
            @JsonProperty("taxInvoiceDataJson") String taxInvoiceDataJson) {
        super(eventId, occurredAt, eventType, version);
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
    }

    /**
     * Convenience constructor for testing.
     */
    public ProcessTaxInvoicePdfCommand(String sagaId, String sagaStep, String correlationId,
                                        String documentId, String taxInvoiceId, String taxInvoiceNumber,
                                        String signedXmlUrl, String taxInvoiceDataJson) {
        super();
        this.sagaId = sagaId;
        this.sagaStep = sagaStep;
        this.correlationId = correlationId;
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
    }
}
