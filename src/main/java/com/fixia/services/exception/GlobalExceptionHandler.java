package com.fixia.services.exception;

import com.fixia.services.client.EligibilityCheckException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Formato de error común del servicio: RFC 7807 (ProblemDetail).
 * Los mensajes nunca incluyen datos del cliente ni detalles internos.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField).thenComparing(FieldError::getDefaultMessage))
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Hay datos que debe corregir");
        problem.setTitle("Datos inválidos");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es válido");
        problem.setTitle("Solicitud inválida");
        return problem;
    }

    @ExceptionHandler(InvalidServiceRequestException.class)
    public ProblemDetail handleInvalidServiceRequest(InvalidServiceRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Datos inválidos");
        return problem;
    }

    /** Un identificador que no es UUID se trata igual que uno inexistente: no se distingue en la respuesta. */
    @ExceptionHandler({ServiceRequestNotFoundException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleNotFound(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No existe la solicitud indicada");
        problem.setTitle("Solicitud no encontrada");
        return problem;
    }

    @ExceptionHandler(ServiceRequestNotAvailableException.class)
    public ProblemDetail handleRequestNotAvailable(ServiceRequestNotAvailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Solicitud no disponible");
        return problem;
    }

    /** 422 con los motivos, para que el técnico sepa qué le falta (categoría, zona u horario). */
    @ExceptionHandler(TechnicianNotEligibleException.class)
    public ProblemDetail handleNotEligible(TechnicianNotEligibleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("No elegible");
        problem.setProperty("reasons", ex.getReasons());
        return problem;
    }

    /** Falla cerrado: sin respuesta de ms-users no se registra la postulación. */
    @ExceptionHandler(EligibilityCheckException.class)
    public ProblemDetail handleEligibilityUnavailable(EligibilityCheckException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "No fue posible verificar tu elegibilidad. Intenta de nuevo en unos minutos");
        problem.setTitle("Elegibilidad no verificable");
        return problem;
    }

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ProblemDetail handleApplicationNotFound(ApplicationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Postulación no encontrada");
        return problem;
    }

    @ExceptionHandler(InvalidApplicationTransitionException.class)
    public ProblemDetail handleInvalidApplicationTransition(InvalidApplicationTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Cambio de estado no permitido");
        return problem;
    }

    /** 409 con el id de la postulación existente cuando se conoce (GC-343). */
    @ExceptionHandler(DuplicateApplicationException.class)
    public ProblemDetail handleDuplicateApplication(DuplicateApplicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Postulación duplicada");
        if (ex.getExistingApplicationId() != null) {
            problem.setProperty("applicationId", ex.getExistingApplicationId());
        }
        return problem;
    }
}
