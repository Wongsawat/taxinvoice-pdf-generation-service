package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.taxinvoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.application.usecase.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.usecase.ProcessTaxInvoicePdfUseCase;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final ProcessTaxInvoicePdfUseCase processUseCase;
    private final CompensateTaxInvoicePdfUseCase compensateUseCase;
    private final SagaCommandHandler sagaCommandHandler;

    public SagaRouteConfig(ProcessTaxInvoicePdfUseCase processUseCase,
                           CompensateTaxInvoicePdfUseCase compensateUseCase,
                           SagaCommandHandler sagaCommandHandler) {
        this.processUseCase = processUseCase;
        this.compensateUseCase = compensateUseCase;
        this.sagaCommandHandler = sagaCommandHandler;
    }

    @Override
    public void configure() throws Exception {

        errorHandler(deadLetterChannel(
                        "kafka:{{app.kafka.topics.dlq}}?brokers={{app.kafka.bootstrap-servers}}")
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true)
                        .onPrepareFailure(exchange -> {
                            Object body = exchange.getIn().getBody();
                            Throwable cause = exchange.getProperty(
                                    org.apache.camel.Exchange.EXCEPTION_CAUGHT, Throwable.class);
                            if (body instanceof KafkaTaxInvoiceProcessCommand cmd) {
                                sagaCommandHandler.publishOrchestrationFailure(cmd, cause);
                            } else if (body instanceof KafkaTaxInvoiceCompensateCommand cmd) {
                                sagaCommandHandler.publishCompensationOrchestrationFailure(cmd, cause);
                            } else {
                                log.error("DLQ: body not deserialized ({}) — orchestrator must timeout",
                                        body == null ? "null" : body.getClass().getSimpleName());
                            }
                        }));

        from("kafka:{{app.kafka.topics.saga-command-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-command-consumer")
                .unmarshal().json(JsonLibrary.Jackson, KafkaTaxInvoiceProcessCommand.class)
                .process(exchange -> {
                    KafkaTaxInvoiceProcessCommand cmd =
                            exchange.getIn().getBody(KafkaTaxInvoiceProcessCommand.class);
                    log.info("Processing saga command for saga: {}, taxInvoice: {}",
                            cmd.getSagaId(), cmd.getTaxInvoiceNumber());
                    processUseCase.handle(cmd);
                })
                .log("Successfully processed saga command");

        from("kafka:{{app.kafka.topics.saga-compensation-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-compensation-consumer")
                .unmarshal().json(JsonLibrary.Jackson, KafkaTaxInvoiceCompensateCommand.class)
                .process(exchange -> {
                    KafkaTaxInvoiceCompensateCommand cmd =
                            exchange.getIn().getBody(KafkaTaxInvoiceCompensateCommand.class);
                    log.info("Processing compensation for saga: {}, taxInvoice: {}",
                            cmd.getSagaId(), cmd.getTaxInvoiceId());
                    compensateUseCase.handle(cmd);
                })
                .log("Successfully processed compensation command");
    }
}
