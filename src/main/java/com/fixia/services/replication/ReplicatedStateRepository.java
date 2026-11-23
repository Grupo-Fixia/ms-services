package com.fixia.services.replication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Estados replicados de otros servicios (GC-547). */
public interface ReplicatedStateRepository extends JpaRepository<ReplicatedState, StateKey> {

    List<ReplicatedState> findByIdStateTypeAndRemovedFalse(String stateType);

    List<ReplicatedState> findByIdStateTypeAndIdStateKeyInAndRemovedFalse(String stateType, Collection<UUID> keys);
}
