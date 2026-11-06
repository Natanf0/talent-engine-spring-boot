package br.com.careermatcher.crud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ResumeSkillPolicy(int maxSkills) {
    public ResumeSkillPolicy(@Value("${resume.skills.max:3}") int maxSkills) {
        if (maxSkills < 1) throw new IllegalArgumentException("resume.skills.max deve ser maior que zero.");
        this.maxSkills = maxSkills;
    }
}
