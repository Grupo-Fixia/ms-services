-- Historial propio del cliente (GC-448): sus contrataciones, la más reciente primero. El del prestador ya tiene su
-- índice (ix_service_contracts_provider, V5).
CREATE INDEX ix_service_contracts_client ON service_contracts (client_id, contracted_at);
