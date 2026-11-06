package br.com.careermatcher.controllers;

import br.com.careermatcher.crud.ResumeSkillPolicy;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResumeFormConfigurationController {
    private final ResumeSkillPolicy policy;
    public ResumeFormConfigurationController(ResumeSkillPolicy policy) { this.policy = policy; }

    @GetMapping({"/api/me/candidate/form-configuration", "/api/careermatcher/candidatos/form-configuration"})
    public ResumeSkillPolicy configuration() { return policy; }
}
