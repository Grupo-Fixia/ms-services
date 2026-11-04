package com.fixia.services.repository;

import com.fixia.services.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Eventos de otros servicios ya aplicados (GC-490); la clave es el {@code eventId} del sobre. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
