package com.fixia.services.client;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidMatchingQueryException;
import com.fixia.services.replication.UserReplica;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Técnicos compatibles con una solicitud (GC-900): categoría, ciudad y círculo de la zona, y franja del momento
 * esperado, como hacía ms-matching-geo con los datos de ms-users. Desde GC-547 se calcula con la réplica local del
 * estado de los técnicos, sin llamar a ms-matching-geo ni a ms-users. Del resultado nunca sale el círculo de la zona.
 *
 * <p>Orden estable por id del técnico; el cursor es el último id devuelto, codificado.
 */
@Component
public class MatchingClient {

    public record CompatibleTechnician(UUID userId, String displayName, List<String> categories, String city) {
    }

    public record CompatibleTechnicians(List<CompatibleTechnician> items, String nextCursor) {
    }

    private final UserReplica replica;

    public MatchingClient(UserReplica replica) {
        this.replica = replica;
    }

    /**
     * @param limit  técnicos por página, de 1 a 50
     * @param cursor el {@code nextCursor} de la página anterior, o {@code null} para empezar
     * @throws InvalidMatchingQueryException si el cursor no es uno de los que entrega este método
     */
    public CompatibleTechnicians findCompatible(ServiceRequest request, int limit, String cursor) {
        UUID after = decode(cursor);
        ApproximateLocation location = request.getApproximateLocation();
        String category = request.getCategory().name();
        List<CompatibleTechnician> items = new ArrayList<>();
        UUID last = null;
        boolean more = false;
        for (UserReplica.Technician technician : replica.allActiveTechnicians()) {
            if (after != null && technician.userId().compareTo(after) <= 0) {
                continue;
            }
            if (!technician.verificationRequirementMet() || !technician.offers(category)
                    || !technician.covers(location.city(), location.latitude(), location.longitude())
                    || !technician.isAvailableAt(request.getExpectedAt())) {
                continue;
            }
            if (items.size() == limit) {
                more = true;
                break;
            }
            items.add(new CompatibleTechnician(technician.userId(), technician.displayName(),
                    technician.categories(), technician.city()));
            last = technician.userId();
        }
        return new CompatibleTechnicians(items, more ? encode(last) : null);
    }

    private static String encode(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static UUID decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw InvalidMatchingQueryException.invalidCursor();
        }
    }
}
