package br.com.careermatcher.crud;

import br.com.careermatcher.security.AuthUser;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.enums.Role;

public class TestActor extends CurrentActor {
    private volatile AuthUser current;
    public void staff(long companyId) { current = new AuthUser("test-staff", "Staff", "staff@example.com", Role.ADMINISTRADOR, companyId, null, true, null); }
    public void candidate(long candidateId) { current = new AuthUser("test-candidate", "Candidate", "candidate@example.com", Role.CANDIDATO, null, candidateId, true, null); }
    @Override public AuthUser user() { return current; }
}
