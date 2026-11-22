package com.fixia.services.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Pregunta de ms-users antes de desvincular a un técnico de su empresa (GC-524, contrato de empresas §6.1).
 *
 * @param companyAdminUserId la empresa tal como la conoce ms-services: el usuario administrador, que es el prestador
 * @param technicianUserId   el técnico que quiere desvincularse
 */
public record CompanyExecutorWorkloadRequest(@NotNull UUID companyAdminUserId, @NotNull UUID technicianUserId) {
}
