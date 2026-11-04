package com.fixia.services.domain;

/** Quién provocó una transición de estado de la solicitud; {@code SYSTEM} es un proceso del servicio, sin usuario. */
public enum ActorRole {
    CLIENT,
    PROFESSIONAL,
    SYSTEM
}
