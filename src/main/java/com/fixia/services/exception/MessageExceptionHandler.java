package com.fixia.services.exception;

import com.fixia.services.controller.ServiceMessageController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores propios del chat (GC-527), con la misma forma {@code ProblemDetail} que el resto del servicio. Solo aplica a
 * {@link ServiceMessageController}; la validación del cuerpo y las 404 siguen en el manejador común.
 */
@RestControllerAdvice(assignableTypes = ServiceMessageController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MessageExceptionHandler {

    @ExceptionHandler(InvalidMessageException.class)
    public ProblemDetail handleInvalid(InvalidMessageException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Mensaje inválido", ex.getMessage());
    }

    @ExceptionHandler(ConversationClosedException.class)
    public ProblemDetail handleClosed(ConversationClosedException ex) {
        return problem(HttpStatus.CONFLICT, "Conversación cerrada", ex.getMessage());
    }

    @ExceptionHandler(MessageConflictException.class)
    public ProblemDetail handleConflict(MessageConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Mensaje repetido con otro texto", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
