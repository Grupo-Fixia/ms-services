package com.fixia.services.dto;

import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Quién participó en una solicitud y si ya está cerrada (GC-444), para que ms-users decida si alguien puede evaluar
 * sin leer esta base. Solo identificadores, estado y fecha de cierre: ni dirección, ni precio, ni descripción.
 *
 * @param providerId   el prestador seleccionado; {@code null} si todavía no hay
 * @param providerType {@code INDEPENDENT} o {@code COMPANY}; {@code null} sin prestador
 * @param closedAt     el paso a {@code CLOSED} según el historial de transiciones; {@code null} si no está cerrada
 */
public record ParticipationResponse(
        UUID serviceRequestId,
        ServiceRequestStatus status,
        UUID clientId,
        UUID providerId,
        ProviderType providerType,
        Instant closedAt
) {
}
