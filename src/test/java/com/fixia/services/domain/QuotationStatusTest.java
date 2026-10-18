package com.fixia.services.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationStatusTest {

    @Test
    void unaCotizacionEnviadaPuedeAceptarseRechazarseVencerOReemplazarse() {
        assertThat(QuotationStatus.SENT.allowedTransitions()).containsExactlyInAnyOrder(
                QuotationStatus.ACCEPTED, QuotationStatus.REJECTED, QuotationStatus.EXPIRED,
                QuotationStatus.SUPERSEDED);
        assertThat(QuotationStatus.SENT.isTerminal()).isFalse();
        assertThat(QuotationStatus.SENT.canTransitionTo(QuotationStatus.SENT)).isFalse();
    }

    @Test
    void losDemasEstadosSonTerminales() {
        for (QuotationStatus status : new QuotationStatus[] {QuotationStatus.ACCEPTED, QuotationStatus.REJECTED,
                QuotationStatus.EXPIRED, QuotationStatus.SUPERSEDED}) {
            assertThat(status.isTerminal()).as(status.name()).isTrue();
            assertThat(status.canTransitionTo(QuotationStatus.ACCEPTED)).as(status.name()).isFalse();
        }
    }
}
