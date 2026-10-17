package com.fixia.services.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStatusTest {

    @Test
    void unaPostulacionPuedeSeleccionarseORechazarse() {
        assertThat(ApplicationStatus.APPLIED.allowedTransitions())
                .containsExactlyInAnyOrder(ApplicationStatus.SELECTED, ApplicationStatus.REJECTED);
        assertThat(ApplicationStatus.APPLIED.isTerminal()).isFalse();
        assertThat(ApplicationStatus.APPLIED.canTransitionTo(ApplicationStatus.APPLIED)).isFalse();
    }

    @Test
    void seleccionadaYRechazadaSonTerminales() {
        for (ApplicationStatus status : new ApplicationStatus[] {ApplicationStatus.SELECTED, ApplicationStatus.REJECTED}) {
            assertThat(status.isTerminal()).as(status.name()).isTrue();
            assertThat(status.canTransitionTo(ApplicationStatus.APPLIED)).as(status.name()).isFalse();
        }
    }
}
