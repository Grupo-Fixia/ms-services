-- Finalización y cierre del servicio (GC-441): el técnico declara que terminó (TECHNICIAN_FINISHED) y el cliente confirma el
-- cierre (CLOSED) o lo objeta y el servicio vuelve a IN_PROGRESS. No hay columnas nuevas: quién hizo cada cambio, cuándo y,
-- en la objeción, el motivo, ya los guarda service_request_transitions (V9).
--
-- Igual que en V9, PostgreSQL no tiene ALTER CONSTRAINT para un CHECK: se elimina y se vuelve a crear, sin IF EXISTS ni
-- NOT VALID para que H2 (modo PostgreSQL, el de las pruebas) lo ejecute igual.
ALTER TABLE service_requests DROP CONSTRAINT ck_service_requests_status;
ALTER TABLE service_requests
    ADD CONSTRAINT ck_service_requests_status CHECK (status IN
        ('PUBLISHED', 'PROVIDER_SELECTED', 'CONTRACTED', 'IN_PROGRESS', 'TECHNICIAN_FINISHED', 'CLOSED',
         'CANCELLED', 'EXPIRED'));

ALTER TABLE service_requests DROP CONSTRAINT ck_service_requests_selected_provider;
ALTER TABLE service_requests
    ADD CONSTRAINT ck_service_requests_selected_provider CHECK
        (status NOT IN ('PROVIDER_SELECTED', 'CONTRACTED', 'IN_PROGRESS', 'TECHNICIAN_FINISHED', 'CLOSED')
         OR selected_provider_id IS NOT NULL);
