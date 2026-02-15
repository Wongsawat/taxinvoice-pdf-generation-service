package com.wpanther.taxinvoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class TaxInvoicePdfGeneratedEvent extends IntegrationEvent {

    private static final String EVENT_TYPE = "pdf.generated.tax-invoice";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("taxInvoiceId")
    private final String taxInvoiceId;

    @JsonProperty("taxInvoiceNumber")
    private final String taxInvoiceNumber;

    @JsonProperty("documentUrl")
    private final String documentUrl;

    @JsonProperty("fileSize")
    private final long fileSize;

    @JsonProperty("xmlEmbedded")
    private final boolean xmlEmbedded;

    @JsonProperty("correlationId")
    private final String correlationId;

    // Default constructor - calls super() for auto-generated metadata
    public TaxInvoicePdfGeneratedEvent(
            String documentId,
            String taxInvoiceId,
            String taxInvoiceNumber,
            String documentUrl,
            long fileSize,
            boolean xmlEmbedded,
            String correlationId
    ) {
        super();
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
        this.correlationId = correlationId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    // JsonCreator constructor for Kafka deserialization
    @JsonCreator
    public TaxInvoicePdfGeneratedEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId,
            @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
            @JsonProperty("documentUrl") String documentUrl,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("xmlEmbedded") boolean xmlEmbedded,
            @JsonProperty("correlationId") String correlationId
    ) {
        super(eventId, occurredAt, eventType, version);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
        this.correlationId = correlationId;
    }
}
