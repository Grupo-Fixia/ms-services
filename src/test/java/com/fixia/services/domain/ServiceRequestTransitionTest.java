package com.fixia.services.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de una fila del historial de la solicitud (GC-437): un hecho completo y que no admite datos a medias. */
class ServiceRequestTransitionTest {

    private static final Instant AT = Instant.parse("2026-10-27T15:00:00Z");
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Test
    void guardaDeDondeAdondeQuienYCuando() {
        ServiceRequestTransition transition = ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.CONTRACTED,
                ServiceRequestStatus.IN_PROGRESS, PROVIDER, ActorRole.PROFESSIONAL, null, AT);

        assertThat(transition.getServiceRequestId()).isEqualTo(REQUEST);
        assertThat(transition.getFromStatus()).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(transition.getToStatus()).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(transition.getActorId()).isEqualTo(PROVIDER);
        assertThat(transition.getActorRole()).isEqualTo(ActorRole.PROFESSIONAL);
        assertThat(transition.getReason()).isNull();
        assertThat(transition.getOccurredAt()).isEqualTo(AT);
    }

    @Test
    void laPublicacionNoTieneEstadoDeOrigen() {
        ServiceRequestTransition transition = ServiceRequestTransition.of(REQUEST, null,
                ServiceRequestStatus.PUBLISHED, UUID.randomUUID(), ActorRole.CLIENT, null, AT);

        assertThat(transition.getFromStatus()).isNull();
    }

    @Test
    void elSistemaPuedeActuarSinUsuarioPeroNadieMas() {
        ServiceRequestTransition system = ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.PUBLISHED,
                ServiceRequestStatus.EXPIRED, null, ActorRole.SYSTEM, null, AT);

        assertThat(system.getActorId()).isNull();
        assertThatThrownBy(() -> ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.CONTRACTED,
                ServiceRequestStatus.IN_PROGRESS, null, ActorRole.PROFESSIONAL, null, AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El actor es obligatorio");
    }

    @Test
    void elMotivoSeLimpiaYTieneUnMaximo() {
        assertThat(ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.CONTRACTED,
                ServiceRequestStatus.IN_PROGRESS, PROVIDER, ActorRole.PROFESSIONAL, "   ", AT).getReason()).isNull();
        assertThat(ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.CONTRACTED,
                ServiceRequestStatus.IN_PROGRESS, PROVIDER, ActorRole.PROFESSIONAL, "  Cliente ausente ", AT)
                .getReason()).isEqualTo("Cliente ausente");
        assertThatThrownBy(() -> ServiceRequestTransition.of(REQUEST, ServiceRequestStatus.CONTRACTED,
                ServiceRequestStatus.IN_PROGRESS, PROVIDER, ActorRole.PROFESSIONAL, "x".repeat(501), AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void exigeSolicitudEstadoNuevoRolYMomento() {
        assertThatThrownBy(() -> ServiceRequestTransition.of(null, null, ServiceRequestStatus.PUBLISHED, PROVIDER,
                ActorRole.CLIENT, null, AT)).hasMessage("La solicitud es obligatoria");
        assertThatThrownBy(() -> ServiceRequestTransition.of(REQUEST, null, null, PROVIDER, ActorRole.CLIENT, null,
                AT)).hasMessage("El estado nuevo es obligatorio");
        assertThatThrownBy(() -> ServiceRequestTransition.of(REQUEST, null, ServiceRequestStatus.PUBLISHED, PROVIDER,
                null, null, AT)).hasMessage("El rol del actor es obligatorio");
        assertThatThrownBy(() -> ServiceRequestTransition.of(REQUEST, null, ServiceRequestStatus.PUBLISHED, PROVIDER,
                ActorRole.CLIENT, null, null)).hasMessage("El momento es obligatorio");
    }
}
