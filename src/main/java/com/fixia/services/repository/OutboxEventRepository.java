package com.fixia.services.repository;

import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Selecciona eventos pendientes listos para publicar (GC-434): status PENDING, next_attempt_at vencido,
     * y garantizando orden estricto por agregado (no selecciona secuencia n+1 mientras la secuencia anterior
     * de la misma solicitud no esté PUBLISHED). Utiliza FOR UPDATE SKIP LOCKED para concurrencia multi-instancia segura.
     */
    @Query(value = """
            SELECT * FROM outbox_events e
            WHERE e.status = 'PENDING'
              AND e.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events prev
                  WHERE prev.aggregate_id = e.aggregate_id
                    AND prev.aggregate_sequence < e.aggregate_sequence
                    AND prev.status != 'PUBLISHED'
              )
            ORDER BY e.occurred_at ASC, e.aggregate_sequence ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForRelay(@Param("now") Instant now, @Param("limit") int limit);

    /** Conteo de eventos por estado para métricas de observabilidad. */
    long countByStatus(OutboxStatus status);

    /** Obtiene el evento más antiguo en un estado dado para calcular la edad del pendiente más antiguo. */
    Optional<OutboxEvent> findFirstByStatusOrderByOccurredAtAsc(OutboxStatus status);
}
