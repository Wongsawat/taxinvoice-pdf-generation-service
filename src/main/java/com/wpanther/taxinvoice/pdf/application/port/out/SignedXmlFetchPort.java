package com.wpanther.taxinvoice.pdf.application.port.out;

public interface SignedXmlFetchPort {
    /** Fetch signed XML content from the given URL. */
    String fetch(String signedXmlUrl);
}
