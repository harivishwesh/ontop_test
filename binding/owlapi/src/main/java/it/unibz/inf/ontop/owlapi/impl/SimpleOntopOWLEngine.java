package it.unibz.inf.ontop.owlapi.impl;

import it.unibz.inf.ontop.answering.OntopQueryEngine;
import it.unibz.inf.ontop.answering.connection.OntopConnection;
import it.unibz.inf.ontop.answering.reformulation.input.InputQueryFactory;
import it.unibz.inf.ontop.exception.OBDASpecificationException;
import it.unibz.inf.ontop.exception.OntopConnectionException;
import it.unibz.inf.ontop.injection.OntopSystemConfiguration;
import it.unibz.inf.ontop.owlapi.OntopOWLEngine;
import it.unibz.inf.ontop.owlapi.connection.OntopOWLConnection;
import it.unibz.inf.ontop.owlapi.connection.impl.DefaultOntopOWLConnection;
import org.semanticweb.owlapi.reasoner.IllegalConfigurationException;
import org.semanticweb.owlapi.reasoner.ReasonerInternalException;


public class SimpleOntopOWLEngine implements OntopOWLEngine {

    private final OntopQueryEngine queryEngine;
    private final InputQueryFactory inputQueryFactory;

    public SimpleOntopOWLEngine(OntopSystemConfiguration configuration) throws IllegalConfigurationException {
        try {
            this.queryEngine = configuration.loadQueryEngine();
            inputQueryFactory = configuration.getInputQueryFactory();
        } catch (OBDASpecificationException e) {
            throw new IllegalConfigurationException(e, new QuestOWLConfiguration(configuration));
        }
    }

    @Override
    public OntopOWLConnection getConnection() throws ReasonerInternalException {
        try {
            OntopConnection conn = queryEngine.getConnection();
            return new DefaultOntopOWLConnection(conn, inputQueryFactory);
        }
        catch (OntopConnectionException e) {
            // TODO: find a better exception?
            throw new ReasonerInternalException(e);
        }
    }

    @Override
    public void close() throws Exception {
        queryEngine.close();
    }
}
