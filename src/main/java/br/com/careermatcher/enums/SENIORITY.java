package br.com.careermatcher.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
@AllArgsConstructor
public enum SENIORITY {
    ESTAGIARIO(0),
    TRAINEE(1),
    JUNIOR(1),
    PLENO(2),
    SENIOR(3),
    GESTOR(4),
    GERENTE(5),
    ARQUITETO(6),
    ESPECIALISTA(7);
    // verificar mais opções e ajustar o banco de dadps

    @Getter
    private int valor;
}
