package com.fixia.services.replication;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reacciona a un cambio de un estado replicado, en la misma transacción en que se guarda (GC-547). Recibe la versión
 * anterior ({@code null} si no había) y la nueva.
 */
public interface ReplicatedStateObserver {

    void onChange(StateEvent event, JsonNode previousData, boolean previouslyRemoved);
}
