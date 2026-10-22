package com.fixia.services.dto;

import java.util.List;

/**
 * Una página de oportunidades (GC-338). {@code nextCursor} es {@code null} cuando no quedan más candidatas. Una página
 * puede traer menos de {@code limit} elementos, incluso ninguno, con {@code nextCursor} no nulo: significa que se
 * agotó el presupuesto de revisión de esa consulta y hay que pedir la siguiente con ese cursor.
 */
public record OpportunityPageResponse(List<OpportunityResponse> items, String nextCursor) {
}
