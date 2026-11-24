-- GC-490: el pago de la contratación también refleja cómo terminó el dinero retenido. COMPLETED = se cobró al cerrar
-- (pago.completado); REFUNDED = el administrador lo devolvió al cliente (pago.reembolsado). Antes ms-services ignoraba esos
-- hechos y el contrato quedaba HELD para siempre, aunque el dinero ya se hubiera cobrado o devuelto.
ALTER TABLE contract_payments DROP CONSTRAINT ck_contract_payments_status;
ALTER TABLE contract_payments
    ADD CONSTRAINT ck_contract_payments_status CHECK (status IN ('HELD', 'FAILED', 'COMPLETED', 'REFUNDED'));
CREATE INDEX ix_contract_payments_payment ON contract_payments (payment_id);
