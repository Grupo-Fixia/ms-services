-- Postulación empresarial (GC-468). Una empresa se postula con un único técnico asociado como ejecutor propuesto.
-- provider_id sigue siendo quien se postula: el users.id del técnico independiente o el de la cuenta de la empresa
-- (su administrador). La unicidad (service_request_id, provider_id) de V4 ya garantiza una postulación por empresa y
-- solicitud. Las postulaciones anteriores son de técnicos independientes y no tienen técnico propuesto.
ALTER TABLE applications ADD COLUMN provider_type VARCHAR(20) NOT NULL DEFAULT 'INDEPENDENT';
ALTER TABLE applications ADD COLUMN technician_id UUID;

ALTER TABLE applications
    ADD CONSTRAINT ck_applications_provider_type CHECK (
        (provider_type = 'INDEPENDENT' AND technician_id IS NULL)
        OR (provider_type = 'COMPANY' AND technician_id IS NOT NULL AND technician_id <> provider_id));
