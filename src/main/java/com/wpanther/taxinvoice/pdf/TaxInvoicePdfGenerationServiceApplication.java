package com.wpanther.taxinvoice.pdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Boot application for Tax Invoice PDF Generation Service
 *
 * This service generates PDF/A-3 documents for Thai e-Tax tax invoices
 * with embedded signed XML content.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class TaxInvoicePdfGenerationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaxInvoicePdfGenerationServiceApplication.class, args);
    }
}
