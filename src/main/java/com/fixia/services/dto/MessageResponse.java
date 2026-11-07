package com.fixia.services.dto;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceMessage;

import java.time.Instant;
import java.util.UUID;

/** Un mensaje del chat (GC-527). {@code mine} dice si lo envió quien consulta, para mostrarlo de su lado. */
public record MessageResponse(UUID id, UUID contractId, UUID serviceRequestId, UUID senderId, ActorRole senderRole,
                              UUID recipientId, String body, UUID clientMessageId, Instant createdAt, boolean mine) {

    public static MessageResponse from(ServiceMessage message, UUID viewerId) {
        return new MessageResponse(message.getId(), message.getContractId(), message.getServiceRequestId(),
                message.getSenderId(), message.getSenderRole(), message.getRecipientId(), message.getBody(),
                message.getClientMessageId(), message.getCreatedAt(), message.getSenderId().equals(viewerId));
    }
}
