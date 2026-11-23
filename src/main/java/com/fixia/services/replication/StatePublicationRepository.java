package com.fixia.services.replication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Estados ya publicados por este servicio (GC-547). */
public interface StatePublicationRepository extends JpaRepository<StatePublication, StateKey> {

    List<StatePublication> findByIdStateType(String stateType);
}
