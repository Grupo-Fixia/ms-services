-- Un mismo archivo (SHA-256) se registra una sola vez por contrato (GC-439): el reintento de una subida ya confirmada
-- devuelve la evidencia existente en lugar de duplicarla. La aplicación lo resuelve bajo el bloqueo de la solicitud;
-- el índice es la red de seguridad.
CREATE UNIQUE INDEX uk_service_evidences_contract_sha ON service_evidences(contract_id, sha256);
