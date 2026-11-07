package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Un mensaje del chat de un servicio contratado (GC-527). Lo escriben y lo leen solo los dos participantes de la
 * contratación: el cliente y el técnico que ejecuta ({@link ServiceContract#getExecutorId()}). Es inmutable: no se
 * edita ni se borra.
 */
@Entity
@Table(name = "service_messages")
public class ServiceMessage {

    public static final int MAX_LENGTH = 2000;

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20, updatable = false)
    private ActorRole senderRole;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "body", nullable = false, length = MAX_LENGTH, updatable = false)
    private String body;

    /** Identificador que pone la app para reintentar sin duplicar; opcional. */
    @Column(name = "client_message_id", updatable = false)
    private UUID clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ServiceMessage() {
    }

    /**
     * Mensaje de uno de los dos participantes de la contratación al otro.
     *
     * @throws IllegalArgumentException si el remitente no es el cliente ni el ejecutor, o si el texto está vacío o
     *                                  supera {@value #MAX_LENGTH} caracteres
     */
    public static ServiceMessage send(ServiceContract contract, UUID senderId, String body, UUID clientMessageId,
                                      Instant now) {
        ActorRole role;
        UUID recipient;
        if (senderId.equals(contract.getClientId())) {
            role = ActorRole.CLIENT;
            recipient = contract.getExecutorId();
        } else if (senderId.equals(contract.getExecutorId())) {
            role = ActorRole.PROFESSIONAL;
            recipient = contract.getClientId();
        } else {
            throw new IllegalArgumentException("Solo el cliente y el técnico del servicio participan en el chat");
        }
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("El mensaje admite como máximo " + MAX_LENGTH + " caracteres");
        }
        ServiceMessage message = new ServiceMessage();
        message.id = UUID.randomUUID();
        message.contractId = contract.getId();
        message.serviceRequestId = contract.getServiceRequestId();
        message.senderId = senderId;
        message.senderRole = role;
        message.recipientId = recipient;
        message.body = text;
        message.clientMessageId = clientMessageId;
        // PostgreSQL guarda microsegundos: así el valor en memoria y el guardado coinciden y el cursor no se corre.
        message.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        return message;
    }

    /** El mismo envío repetido: mismo remitente, mismo identificador de la app y mismo texto. */
    public boolean isSameAs(UUID senderId, String body) {
        return this.senderId.equals(senderId) && this.body.equals(body == null ? null : body.strip());
    }

    public UUID getId() {
        return id;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public ActorRole getSenderRole() {
        return senderRole;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getBody() {
        return body;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sin el texto: puede traer datos personales y no debe acabar en los logs. */
    @Override
    public String toString() {
        return "ServiceMessage{id=" + id + ", contractId=" + contractId + ", senderRole=" + senderRole + "}";
    }
}
