package br.com.careermatcher.matching.execution;

import br.com.careermatcher.security.CurrentActor;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredCompany implements CurrentCompany {
    private final CurrentActor actor;
    public ConfiguredCompany(CurrentActor actor) { this.actor = actor; }
    @Override public long id() { return actor.requireCompany(); }
}
