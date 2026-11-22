package com.fixia.services.dto;

import java.util.List;
import java.util.UUID;

/**
 * Trabajo del técnico con la empresa (GC-524, contrato de empresas §6.1). Solo identificadores de solicitudes.
 *
 * @param pendingServiceRequestIds solicitudes con trabajo vivo: postulación abierta o servicio seleccionado sin cerrar.
 *                                 Si no está vacía, el técnico no se puede desvincular
 * @param serviceRequestIds        todas las solicitudes en que el técnico fue el designado seleccionado, incluidas las
 *                                 cerradas; ms-users las usa para preguntar a ms-payments por dinero retenido
 */
public record CompanyExecutorWorkloadResponse(List<UUID> pendingServiceRequestIds, List<UUID> serviceRequestIds) {
}
