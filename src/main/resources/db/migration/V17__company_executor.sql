-- GC-551: en un servicio de empresa, el técnico designado cotiza y ejecuta, y la empresa sigue siendo la prestadora
-- (ms-users/docs/contracts/empresas.md §8). Se separa quién recibe el pago (provider_id, la empresa) de quién hace el
-- trabajo (author_id en la cotización, executor_id en el contrato). En un técnico independiente las tres columnas
-- coinciden, así que las filas existentes se completan con provider_id y nada cambia para ellas.

ALTER TABLE quotations ADD COLUMN author_id UUID;
UPDATE quotations SET author_id = provider_id WHERE author_id IS NULL;
ALTER TABLE quotations ALTER COLUMN author_id SET NOT NULL;
CREATE INDEX idx_quotations_author ON quotations (author_id, created_at DESC);

ALTER TABLE service_contracts ADD COLUMN executor_id UUID;
UPDATE service_contracts SET executor_id = provider_id WHERE executor_id IS NULL;
ALTER TABLE service_contracts ALTER COLUMN executor_id SET NOT NULL;
CREATE INDEX idx_service_contracts_executor ON service_contracts (executor_id, contracted_at DESC);
