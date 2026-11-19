package com.fixia.services.dto;

import com.fixia.services.domain.ServiceMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Mensaje del chat (GC-527). {@code clientMessageId} es opcional: si la app lo manda y reintenta el mismo envío, no se
 * duplica.
 */
public record SendMessageRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = ServiceMessage.MAX_LENGTH, message = "El mensaje admite como máximo 2000 caracteres")
        String body,
        UUID clientMessageId
) {
}
