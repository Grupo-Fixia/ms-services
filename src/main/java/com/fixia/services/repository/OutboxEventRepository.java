package com.fixia.services.repository;

import com.fixia.services.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Última secuencia usada en la historia de una solicitud, o 0 si aún no tiene eventos. Quien la usa debe tener la
     * solicitud bloqueada (o estar creándola), para que dos transacciones no tomen el mismo número.
     */
    @Query("select coalesce(max(e.aggregateSequence), 0) from OutboxEvent e where e.aggregateId = :aggregateId")
    long lastSequence(@Param("aggregateId") UUID aggregateId);

    /** Historia de una solicitud en orden: consulta operativa y de pruebas. */
    List<OutboxEvent> findByAggregateIdOrderByAggregateSequenceAsc(UUID aggregateId);
}
