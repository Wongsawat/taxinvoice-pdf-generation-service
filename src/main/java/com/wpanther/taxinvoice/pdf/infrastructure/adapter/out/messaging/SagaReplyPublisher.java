package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfReplyEvent;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes saga reply events via outbox pattern.
 * Replies are sent to orchestrator via saga.reply.tax-invoice-pdf topic.
 */
@Component
@Slf4j
public class SagaReplyPublisher implements SagaReplyPort {

    private static final String AGGREGATE_TYPE = OutboxConstants.AGGREGATE_TYPE;

    private final String replyTopic;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public SagaReplyPublisher(
            @Value("${app.kafka.topics.saga-reply-tax-invoice-pdf:saga.reply.tax-invoice-pdf}") String replyTopic,
            OutboxService outboxService,
            ObjectMapper objectMapper) {
        this.replyTopic = replyTopic;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                               String pdfUrl, long pdfSize) {
        TaxInvoicePdfReplyEvent reply = TaxInvoicePdfReplyEvent.success(sagaId, sagaStep, correlationId, pdfUrl, pdfSize);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "SUCCESS"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                toJson(headers)
        );

        log.info("Published SUCCESS saga reply for saga {} step {}", sagaId, sagaStep);
        log.debug("SUCCESS reply pdfUrl={} pdfSize={}", pdfUrl, pdfSize);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        TaxInvoicePdfReplyEvent reply = TaxInvoicePdfReplyEvent.failure(sagaId, sagaStep, correlationId, errorMessage);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "FAILURE"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                toJson(headers)
        );

        log.info("Published FAILURE saga reply for saga {} step {}: {}", sagaId, sagaStep, errorMessage);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        TaxInvoicePdfReplyEvent reply = TaxInvoicePdfReplyEvent.compensated(sagaId, sagaStep, correlationId);

        Map<String, String> headers = Map.of(
                "sagaId", sagaId,
                "correlationId", correlationId,
                "status", "COMPENSATED"
        );

        outboxService.saveWithRouting(
                reply,
                AGGREGATE_TYPE,
                sagaId,
                replyTopic,
                sagaId,
                toJson(headers)
        );

        log.info("Published COMPENSATED saga reply for saga {} step {}", sagaId, sagaStep);
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event headers — aborting to prevent publishing without correlation headers", e);
        }
    }
}
