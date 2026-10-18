package com.fixia.services.exception;

import com.fixia.services.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/** Respuestas de error propias de las postulaciones que la API todavía no alcanza por sí sola. */
class ApplicationErrorHandlingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unCambioDeEstadoNoPermitidoResponde409() {
        ProblemDetail problem = handler.handleInvalidApplicationTransition(
                new InvalidApplicationTransitionException(ApplicationStatus.REJECTED, ApplicationStatus.SELECTED));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Cambio de estado no permitido");
        assertThat(problem.getDetail()).contains("REJECTED").contains("SELECTED");
    }

    @Test
    void losMotivosDeNoElegibilidadNuncaSonNulos() {
        assertThat(new TechnicianNotEligibleException(null).getReasons()).isEmpty();
    }
}
