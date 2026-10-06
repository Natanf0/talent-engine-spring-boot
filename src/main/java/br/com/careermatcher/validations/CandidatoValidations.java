package br.com.careermatcher.validations;

import br.com.careermatcher.crud.GraphSchema.Kind;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class CandidatoValidations {
    private final GraphValidations graph;
    public CandidatoValidations(GraphValidations graph) { this.graph = graph; }
    public ObjectNode validate(ObjectNode data) { return graph.validate(Kind.CANDIDATE, data); }
    public ObjectNode patch(ObjectNode current, ObjectNode patch) {
        ObjectNode writable = graph.withoutIds(current);
        // E-mail is projected from USER and is not writable through candidate CRUD.
        writable.remove("email");
        return validate(graph.merge(writable, patch));
    }
}
