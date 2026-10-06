package br.com.careermatcher.validations;

import br.com.careermatcher.crud.GraphSchema.Kind;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class VagaValidations {
    private final GraphValidations graph;
    public VagaValidations(GraphValidations graph) { this.graph = graph; }
    public ObjectNode validate(ObjectNode data) { return graph.validate(Kind.VACANCY, data); }
    public ObjectNode patch(ObjectNode current, ObjectNode patch) { return validate(graph.merge(graph.withoutIds(current), patch)); }
}
