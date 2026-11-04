package com.fixia.services.exception;

/** No hay una contratación con ese identificador (o no es del solicitante) o la solicitud todavía no se contrató (GC-359). */
public class ContractNotFoundException extends RuntimeException {

    public ContractNotFoundException() {
        super("No existe la contratación indicada");
    }
}
