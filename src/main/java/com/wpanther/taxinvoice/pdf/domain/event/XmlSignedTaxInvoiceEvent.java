package com.wpanther.taxinvoice.pdf.domain.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.IntegrationEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * External event consumed when a tax invoice XML has been signed
 * This event is published by the XML Signing Service
 */
@Getter
public class XmlSignedTaxInvoiceEvent extends IntegrationEvent {

    private static final String EVENT_TYPE = "xml.signed.tax-invoice";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("taxInvoiceId")
    private final String taxInvoiceId;

    @JsonProperty("taxInvoiceNumber")
    private final String taxInvoiceNumber;

    @JsonProperty("signedXmlContent")
    private final String signedXmlContent;

    @JsonProperty("taxInvoiceDataJson")
    private final String taxInvoiceDataJson;

    @JsonProperty("correlationId")
    private final String correlationId;

    // Default constructor - calls super() for auto-generated metadata
    public XmlSignedTaxInvoiceEvent(
            String documentId,
            String taxInvoiceId,
            String taxInvoiceNumber,
            String signedXmlContent,
            String taxInvoiceDataJson,
            String correlationId
    ) {
        super();
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
        this.correlationId = correlationId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    // JsonCreator constructor for Kafka deserialization
    @JsonCreator
    public XmlSignedTaxInvoiceEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId,
            @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
            @JsonProperty("signedXmlContent") String signedXmlContent,
            @JsonProperty("taxInvoiceDataJson") String taxInvoiceDataJson,
            @JsonProperty("correlationId") String correlationId
    ) {
        super(eventId, occurredAt, eventType, version);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
        this.correlationId = correlationId;
    }
}
