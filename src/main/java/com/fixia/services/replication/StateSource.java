package com.fixia.services.replication;

import java.util.Map;
import java.util.UUID;

/**
 * Una entidad de este servicio que otros necesitan (GC-547). El publicador le pide su estado actual completo cada pocos
 * segundos y publica solo lo que cambió. El estado no lleva correo, teléfono, documento ni secretos.
 */
public interface StateSource {

    /** Tipo del estado, que también es el {@code eventType} del mensaje: por ejemplo {@code tecnico.estado}. */
    String stateType();

    /** Tópico de Kafka donde se publica; la clave del mensaje es la entidad. */
    String topic();

    /** Tipo del agregado en el sobre. */
    String aggregateType();

    /** Estado actual de cada entidad, por su id. Una entidad que desaparece de aquí se publica como eliminada. */
    Map<UUID, Object> currentStates();
}
