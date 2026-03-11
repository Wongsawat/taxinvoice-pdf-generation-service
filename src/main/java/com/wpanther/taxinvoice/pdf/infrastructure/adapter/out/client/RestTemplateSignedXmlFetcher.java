package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.client;

import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private final RestTemplate restTemplate;

    @Override
    public String fetch(String signedXmlUrl) {
        log.debug("Fetching signed XML from {}", signedXmlUrl);
        String xml = restTemplate.getForObject(signedXmlUrl, String.class);
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException(
                    "Received null or empty signed XML response from: " + signedXmlUrl);
        }
        return xml;
    }
}
