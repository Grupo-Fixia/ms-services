package com.fixia.services.repository;

import com.fixia.services.domain.ContractPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Pago virtual de cada contratación (GC-490); la clave es el id de la contratación. */
public interface ContractPaymentRepository extends JpaRepository<ContractPayment, UUID> {
}
