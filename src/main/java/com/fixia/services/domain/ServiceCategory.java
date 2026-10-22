package com.fixia.services.domain;

/**
 * Categoría del servicio solicitado. Mismos valores que el catálogo de ms-users (equivalencias del DD:
 * PLOMERIA, ELECTRICIDAD, MANTENIMIENTO, CERRAJERIA, PINTURA, CARPINTERIA); se redefine aquí porque
 * ms-services no puede depender de las clases de otro microservicio.
 */
public enum ServiceCategory {
    PLUMBING,
    ELECTRICAL,
    MAINTENANCE,
    LOCKSMITHING,
    PAINTING,
    CARPENTRY
}
