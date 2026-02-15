package com.wpanther.taxinvoice.pdf.infrastructure.config;

import com.wpanther.taxinvoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.domain.event.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.ProcessTaxInvoicePdfCommand;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for saga command and compensation consumers.
 *
 * <p>This route:</p>
 * <ul>
 *   <li>Consumes from: saga.command.tax-invoice-pdf (process commands from orchestrator)</li>
 *   <li>Consumes from: saga.compensation.tax-invoice-pdf (compensation commands from orchestrator)</li>
 *   <li>DLQ: pdf.generation.tax-invoice.dlq</li>
 * </ul>
 *
 * <p>Events are published via outbox pattern (not direct Kafka produce).</p>
 */
@Component
@Slf4j
public class CamelRouteConfig extends RouteBuilder {

    private final SagaCommandHandler sagaCommandHandler;

    @Value("${app.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Value("${app.kafka.topics.saga-command-tax-invoice-pdf}")
    private String sagaCommandTopic;

    @Value("${app.kafka.topics.saga-compensation-tax-invoice-pdf}")
    private String sagaCompensationTopic;

    @Value("${app.kafka.topics.dlq:pdf.generation.tax-invoice.dlq}")
    private String dlqTopic;

    public CamelRouteConfig(SagaCommandHandler sagaCommandHandler) {
        this.sagaCommandHandler = sagaCommandHandler;
    }

    @Override
    public void configure() throws Exception {

        // Global error handler - Dead Letter Channel with retries
        errorHandler(deadLetterChannel("kafka:" + dlqTopic + "?brokers=" + kafkaBrokers)
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true));

        // ============================================================
        // CONSUMER ROUTE: saga.command.tax-invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCommandTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=taxinvoice-pdf-generation-service"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-command-consumer")
                        .log("Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, ProcessTaxInvoicePdfCommand.class)
                        .process(exchange -> {
                                ProcessTaxInvoicePdfCommand cmd = exchange.getIn().getBody(ProcessTaxInvoicePdfCommand.class);
                                log.info("Processing saga command for saga: {}, taxInvoice: {}",
                                                cmd.getSagaId(), cmd.getTaxInvoiceNumber());
                                sagaCommandHandler.handleProcessCommand(cmd);
                        })
                        .log("Successfully processed saga command");

        // ============================================================
        // CONSUMER ROUTE: saga.compensation.tax-invoice-pdf (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCompensationTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=taxinvoice-pdf-generation-service"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-compensation-consumer")
                        .log("Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, CompensateTaxInvoicePdfCommand.class)
                        .process(exchange -> {
                                CompensateTaxInvoicePdfCommand cmd = exchange.getIn().getBody(CompensateTaxInvoicePdfCommand.class);
                                log.info("Processing compensation for saga: {}, taxInvoice: {}",
                                                cmd.getSagaId(), cmd.getTaxInvoiceId());
                                sagaCommandHandler.handleCompensation(cmd);
                        })
                        .log("Successfully processed compensation command");
    }
}
