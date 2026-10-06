package br.com.careermatcher.enums;

public enum Role {
    ADMINISTRADOR, GERENTE, RECRUTADOR, CANDIDATO;

    public boolean isStaff() { return this != CANDIDATO; }
}
