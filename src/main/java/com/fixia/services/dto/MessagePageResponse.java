package com.fixia.services.dto;

import com.fixia.services.service.MessageService;

import java.util.List;
import java.util.UUID;

/**
 * Página de la conversación (GC-527), en orden de envío. {@code nextCursor} se manda como {@code after} para pedir lo
 * que sigue o preguntar si llegó algo nuevo; {@code hasMore} dice si ya hay más mensajes esperando; {@code open} dice si
 * el servicio todavía admite mensajes.
 */
public record MessagePageResponse(List<MessageResponse> items, String nextCursor, boolean hasMore, boolean open) {

    public static MessagePageResponse from(MessageService.Page page, UUID viewerId) {
        return new MessagePageResponse(page.messages().stream().map(m -> MessageResponse.from(m, viewerId)).toList(),
                page.next(), page.more(), page.open());
    }
}
