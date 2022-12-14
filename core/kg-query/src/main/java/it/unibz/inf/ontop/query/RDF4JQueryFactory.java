package it.unibz.inf.ontop.query;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;

/**
 * Depends on RDF4J
 */
public interface RDF4JQueryFactory {

    RDF4JSelectQuery createSelectQuery(String queryString, ParsedQuery parsedQuery, BindingSet bindings);

    RDF4JAskQuery createAskQuery(String queryString, ParsedQuery parsedQuery, BindingSet bindings);

    RDF4JConstructQuery createConstructQuery(String queryString, ParsedQuery parsedQuery, BindingSet bindings);

    RDF4JDescribeQuery createDescribeQuery(String queryString, ParsedQuery parsedQuery, BindingSet bindings);

    RDF4JInsertOperation createInsertOperation(String queryString, ParsedUpdate parsedUpdate);
}
