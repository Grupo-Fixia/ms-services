package com.fixia.services.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static com.fixia.services.domain.ServiceRequestStatus.CANCELLED;
import static com.fixia.services.domain.ServiceRequestStatus.CLOSED;
import static com.fixia.services.domain.ServiceRequestStatus.CONTRACTED;
import static com.fixia.services.domain.ServiceRequestStatus.EXPIRED;
import static com.fixia.services.domain.ServiceRequestStatus.IN_PROGRESS;
import static com.fixia.services.domain.ServiceRequestStatus.PROVIDER_SELECTED;
import static com.fixia.services.domain.ServiceRequestStatus.PUBLISHED;
import static com.fixia.services.domain.ServiceRequestStatus.TECHNICIAN_FINISHED;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceRequestStatusTest {

    @Test
    void unaSolicitudPublicadaPuedeSeleccionarPrestadorCancelarseOExpirar() {
        assertThat(PUBLISHED.allowedTransitions()).containsExactlyInAnyOrder(PROVIDER_SELECTED, CANCELLED, EXPIRED);
    }

    @Test
    void unaSolicitudConPrestadorSeleccionadoSoloSePuedeContratarOCancelar() {
        assertThat(PROVIDER_SELECTED.allowedTransitions()).containsExactlyInAnyOrder(CONTRACTED, CANCELLED);
    }

    @Test
    void unaSolicitudContratadaSoloPuedeIniciarse() {
        assertThat(CONTRACTED.allowedTransitions()).containsExactly(IN_PROGRESS);
    }

    @Test
    void unServicioEnCursoSoloPuedeSerDeclaradoFinalizado() {
        assertThat(IN_PROGRESS.allowedTransitions()).containsExactly(TECHNICIAN_FINISHED);
    }

    @Test
    void unServicioFinalizadoSePuedeCerrarOObjetarYVolverAEnCurso() {
        assertThat(TECHNICIAN_FINISHED.allowedTransitions()).containsExactlyInAnyOrder(CLOSED, IN_PROGRESS);
    }

    @ParameterizedTest
    @EnumSource(value = ServiceRequestStatus.class, names = {"CLOSED", "CANCELLED", "EXPIRED"})
    void losEstadosTerminalesNoTienenTransiciones(ServiceRequestStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ServiceRequestStatus.class,
            names = {"PUBLISHED", "PROVIDER_SELECTED", "CONTRACTED", "IN_PROGRESS", "TECHNICIAN_FINISHED"})
    void losEstadosActivosNoSonTerminales(ServiceRequestStatus active) {
        assertThat(active.isTerminal()).isFalse();
    }

    @Test
    void unServicioCerradoNoSeReabre() {
        for (ServiceRequestStatus target : EnumSet.allOf(ServiceRequestStatus.class)) {
            assertThat(CLOSED.canTransitionTo(target)).as("CLOSED -> %s", target).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ServiceRequestStatus.class)
    void laDireccionExactaSeEntregaDesdeLaContratacionTambienUnaVezCerrado(ServiceRequestStatus status) {
        assertThat(status.exposesExactLocation()).isEqualTo(
                Set.of(CONTRACTED, IN_PROGRESS, TECHNICIAN_FINISHED, CLOSED).contains(status));
    }

    @ParameterizedTest
    @EnumSource(ServiceRequestStatus.class)
    void canTransitionToCoincideConLasTransicionesPermitidas(ServiceRequestStatus from) {
        Set<ServiceRequestStatus> allowed = from.allowedTransitions();
        for (ServiceRequestStatus to : EnumSet.allOf(ServiceRequestStatus.class)) {
            assertThat(from.canTransitionTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(allowed.contains(to));
        }
    }

    @ParameterizedTest
    @EnumSource(ServiceRequestStatus.class)
    void ningunEstadoPuedePasarASiMismo(ServiceRequestStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }
}
