package com.fixia.services.controller;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Simula en las pruebas que ms-payments ya confirmó el saldo retenido de una contratación (GC-490): deja la misma fila
 * que deja el consumidor al recibir {@code pago.procesado}. Las pruebas de otras funciones, que necesitan un servicio en
 * curso, la usan antes de iniciarlo; las que prueban el bloqueo del inicio no la siembran.
 */
final class ContractPayments {

    private ContractPayments() {
    }

    /** El total quedó retenido: la contratación puede iniciar. */
    static void hold(JdbcTemplate jdbc, UUID contractId) {
        insert(jdbc, contractId, "HELD");
    }

    /** No se pudo retener (p. ej. saldo insuficiente): la contratación no puede iniciar. */
    static void fail(JdbcTemplate jdbc, UUID contractId) {
        insert(jdbc, contractId, "FAILED");
    }

    private static void insert(JdbcTemplate jdbc, UUID contractId, String status) {
        int rows = jdbc.update("""
                insert into contract_payments
                    (contract_id, service_request_id, payment_id, status, last_event_id, last_sequence, updated_at)
                select id, service_request_id, ?, ?, ?, 1, current_timestamp
                from service_contracts where id = ?
                """, UUID.randomUUID(), status, UUID.randomUUID(), contractId);
        if (rows != 1) {
            throw new IllegalStateException("No existe la contratación " + contractId);
        }
    }
}
