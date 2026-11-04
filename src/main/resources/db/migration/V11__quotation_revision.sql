-- Número de versión visible de la cotización (GC-427): 1, 2, 3... por solicitud, para que el cliente y el prestador vean
-- «versión 2» sin calcularla. Es distinto de la columna version, que es el candado optimista de JPA y no se expone.
--
-- Las cotizaciones existentes se numeran por antigüedad dentro de su solicitud (created_at y, a igual instante, id), así
-- que no se inventa ningún dato. Se usa una subconsulta correlacionada y no ROW_NUMBER() con UPDATE ... FROM porque H2
-- (modo PostgreSQL, el de las pruebas) no admite UPDATE ... FROM.
ALTER TABLE quotations ADD COLUMN revision INTEGER;

UPDATE quotations q SET revision = (
    SELECT CAST(COUNT(*) AS INTEGER) FROM quotations p
    WHERE p.service_request_id = q.service_request_id
      AND (p.created_at < q.created_at OR (p.created_at = q.created_at AND p.id <= q.id)));

ALTER TABLE quotations ALTER COLUMN revision SET NOT NULL;
ALTER TABLE quotations ADD CONSTRAINT uk_quotations_request_revision UNIQUE (service_request_id, revision);
ALTER TABLE quotations ADD CONSTRAINT ck_quotations_revision CHECK (revision >= 1);
