package it.unibz.inf.ontop.query.translation;

import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.exception.OntopInvalidKGQueryException;
import it.unibz.inf.ontop.exception.OntopUnsupportedKGQueryException;
import it.unibz.inf.ontop.iq.IQ;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;

public interface RDF4JQueryTranslator extends KGQueryTranslator {

    IQ translateQuery(ParsedQuery parsedQuery, BindingSet bindings)
            throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException;

    IQ translateAskQuery(ParsedQuery parsedQuery, BindingSet bindings)
            throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException;

    ImmutableSet<IQ> translateInsertOperation(ParsedUpdate parsedUpdate)
            throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException;
}
