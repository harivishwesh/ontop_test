package it.unibz.inf.ontop.model;

import it.unibz.inf.ontop.exception.DuplicateMappingException;
import it.unibz.inf.ontop.exception.InvalidMappingException;
import it.unibz.inf.ontop.exception.MappingIOException;
import it.unibz.inf.ontop.mapping.pp.SQLPPMapping;
import org.apache.commons.rdf.api.Graph;

import java.io.File;
import java.io.Reader;

public interface SQLMappingParser {

    SQLPPMapping parse(File file) throws InvalidMappingException, DuplicateMappingException, MappingIOException;

    SQLPPMapping parse(Reader reader) throws InvalidMappingException, DuplicateMappingException, MappingIOException;

    SQLPPMapping parse(Graph mappingGraph) throws InvalidMappingException, DuplicateMappingException;
}
