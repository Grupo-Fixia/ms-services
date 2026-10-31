package com.fixia.services.dto;

import java.util.List;

/**
 * Una página del historial de servicios (GC-448). {@code nextCursor} es {@code null} cuando no quedan más servicios;
 * si no es nulo, se pide la siguiente página con ese valor en el parámetro {@code cursor}.
 */
public record HistoryPageResponse(List<HistoryItemResponse> items, String nextCursor) {
}
