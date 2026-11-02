package com.fixia.services.dto;

import com.fixia.services.client.MatchingClient;

import java.util.List;
import java.util.UUID;

/**
 * Una página de técnicos compatibles con una solicitud del cliente (GC-474), en orden estable y sin ranking. Solo lo
 * público de cada técnico: nombre corto, especialidades y ciudad. Nunca su zona (centro y radio), distancia ni datos de
 * contacto, ni nada de la solicitud más allá de lo que el cliente ya conoce.
 *
 * <p>{@code nextCursor} es nulo solo cuando no quedan técnicos por revisar: una página puede venir corta, o vacía, con
 * cursor no nulo, y hay que seguir pidiendo hasta que sea nulo.
 */
public record CompatibleTechniciansResponse(List<Item> items, String nextCursor) {

    public record Item(UUID userId, String displayName, List<String> categories, String city) {
    }

    public static CompatibleTechniciansResponse from(MatchingClient.CompatibleTechnicians page) {
        return new CompatibleTechniciansResponse(
                page.items().stream()
                        .map(t -> new Item(t.userId(), t.displayName(), t.categories(), t.city()))
                        .toList(),
                page.nextCursor());
    }
}
