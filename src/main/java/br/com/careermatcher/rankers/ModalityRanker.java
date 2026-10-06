package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.Modalidade;
import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Vacancy;
import org.springframework.stereotype.Component;

@Component
public class ModalityRanker extends ExplainedRanker {
    @Override public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        boolean remote = vacancy != null && vacancy.getModalidade() == Modalidade.REMOTO;
        double points = candidatePerspective && remote ? PesosRanker.VAGA_MODALIDADE_REMOTO.getPeso() : 0.0;
        return RankerDetail.single("ModalityRanker", candidatePerspective ? "REMOTE_BONUS" : "MODALITY_NOT_SCORED",
                points, candidatePerspective
                        ? "Bônus para vaga remota; não representa uma preferência individual declarada."
                        : "Modalidade não pontua na perspectiva da vaga.",
                "modality", vacancy == null ? null : vacancy.getModalidade(), "remote", remote,
                "bonus", PesosRanker.VAGA_MODALIDADE_REMOTO.getPeso());
    }
}
