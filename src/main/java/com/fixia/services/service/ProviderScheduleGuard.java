package com.fixia.services.service;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.exception.ScheduleConflictException;
import com.fixia.services.repository.ServiceContractRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Evita que un prestador quede contratado en dos servicios a la misma hora. Un servicio no guarda su duración, así que
 * dos fechas acordadas chocan si están a menos de {@code fixia.scheduling.min-gap} (2 horas por defecto) la una de la
 * otra: con un servicio a las 10:00, otro a las 11:00 choca y uno a las 12:00 ya no.
 *
 * <p>Solo cuentan los servicios que todavía ocupan al prestador: contratados, en curso o finalizados por él a la espera
 * de que el cliente confirme. Los cerrados, cancelados o vencidos liberan el horario.
 */
@Component
public class ProviderScheduleGuard {

    /** Estados en los que el servicio contratado sigue ocupando la agenda del prestador. */
    static final Set<ServiceRequestStatus> BUSY = EnumSet.of(ServiceRequestStatus.CONTRACTED,
            ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.TECHNICIAN_FINISHED);

    private static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

    private final ServiceContractRepository contractRepository;
    private final Duration minGap;

    public ProviderScheduleGuard(ServiceContractRepository contractRepository,
                                 @Value("${fixia.scheduling.min-gap:PT2H}") Duration minGap) {
        if (minGap == null || minGap.isNegative() || minGap.isZero()) {
            throw new IllegalArgumentException("fixia.scheduling.min-gap debe ser una duración positiva");
        }
        this.contractRepository = contractRepository;
        this.minGap = minGap;
    }

    /**
     * Falla con {@link ScheduleConflictException} si el prestador ya tiene un servicio activo cuya fecha acordada está
     * a menos del margen de {@code agreedAt}. La solicitud {@code serviceRequestId} no cuenta contra sí misma.
     */
    public void ensureAvailable(UUID providerId, Instant agreedAt, UUID serviceRequestId) {
        Optional<ServiceContract> clash = contractRepository.findFirstBusyOverlap(providerId, BUSY,
                agreedAt.minus(minGap), agreedAt.plus(minGap), serviceRequestId);
        clash.ifPresent(contract -> {
            throw new ScheduleConflictException("El técnico ya tiene un servicio contratado el "
                    + WHEN.format(contract.getAgreedAt().atZone(COLOMBIA))
                    + " (hora de Colombia). Debe haber al menos " + describe(minGap)
                    + " entre dos servicios: elige otra fecha.");
        });
    }

    private static String describe(Duration gap) {
        long minutes = gap.toMinutes();
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "1 hora" : hours + " horas";
        }
        return minutes + " minutos";
    }
}
