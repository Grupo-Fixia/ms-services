package com.fixia.services.exception;

import com.fixia.services.domain.QuotationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unaReglaDeDominioIncumplidaEsUn400ConSuMensaje() {
        ProblemDetail problem = handler.handleInvalidServiceRequest(new InvalidServiceRequestException(
                "El momento esperado debe ser posterior a la publicación", new IllegalArgumentException()));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Datos inválidos");
        assertThat(problem.getDetail()).contains("momento esperado");
    }

    @Test
    void unaSolicitudInexistenteEsUn404SinDetallesInternos() {
        ProblemDetail problem = handler.handleNotFound(new ServiceRequestNotFoundException());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Solicitud no encontrada");
        assertThat(problem.getDetail()).isEqualTo("No existe la solicitud indicada");
    }

    @Test
    void unCambioDeEstadoInvalidoDeUnaCotizacionEsUn409ConSuMensaje() {
        ProblemDetail problem = handler.handleInvalidQuotationTransition(new InvalidQuotationTransitionException(
                QuotationStatus.REJECTED, QuotationStatus.ACCEPTED));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Cambio de estado no permitido");
        assertThat(problem.getDetail()).contains("REJECTED").contains("ACCEPTED");
    }
}
