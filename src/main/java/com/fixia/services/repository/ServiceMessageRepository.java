package com.fixia.services.repository;

import com.fixia.services.domain.ServiceMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mensajes del chat de cada contratación (GC-527), en el orden en que se enviaron. */
public interface ServiceMessageRepository extends JpaRepository<ServiceMessage, UUID> {

    /** Los primeros mensajes de la conversación, del más antiguo al más reciente. */
    @Query("""
            select m from ServiceMessage m
            where m.contractId = :contractId
            order by m.createdAt asc, m.id asc
            """)
    List<ServiceMessage> findFirstPage(@Param("contractId") UUID contractId, Pageable pageable);

    /**
     * Los mensajes estrictamente posteriores a la clave {@code (createdAt, id)} del último que la app ya tiene. Sirve
     * para la página siguiente y para preguntar si llegó algo nuevo.
     */
    @Query("""
            select m from ServiceMessage m
            where m.contractId = :contractId
              and (m.createdAt > :afterCreatedAt or (m.createdAt = :afterCreatedAt and m.id > :afterId))
            order by m.createdAt asc, m.id asc
            """)
    List<ServiceMessage> findPageAfter(@Param("contractId") UUID contractId,
                                       @Param("afterCreatedAt") Instant afterCreatedAt,
                                       @Param("afterId") UUID afterId, Pageable pageable);

    /** El mensaje que ya envió ese remitente con ese identificador de la app, si lo hay. */
    Optional<ServiceMessage> findByContractIdAndSenderIdAndClientMessageId(UUID contractId, UUID senderId,
                                                                           UUID clientMessageId);
}
