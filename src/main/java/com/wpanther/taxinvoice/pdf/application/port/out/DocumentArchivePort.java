package com.wpanther.taxinvoice.pdf.application.port.out;

import com.wpanther.taxinvoice.pdf.application.dto.event.DocumentArchiveEvent;

public interface DocumentArchivePort {
    void publish(DocumentArchiveEvent event);
}