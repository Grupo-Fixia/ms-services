package com.fixia.services.exception;

import java.util.List;

/**
 * Un técnico que pertenece a una empresa intentó postularse por su cuenta (contrato de empresas, D9 y P2). Se postula
 * a través de su empresa. Se traduce a 409 con el motivo {@value #COMPANY_MEMBER}.
 */
public class CompanyMemberApplicationException extends RuntimeException {

    public static final String COMPANY_MEMBER = "COMPANY_MEMBER";

    public CompanyMemberApplicationException() {
        super("Perteneces a una empresa: te postulas a través de ella");
    }

    public List<String> getReasons() {
        return List.of(COMPANY_MEMBER);
    }
}
