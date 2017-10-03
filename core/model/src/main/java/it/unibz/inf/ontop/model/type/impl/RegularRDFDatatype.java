package it.unibz.inf.ontop.model.type.impl;

import it.unibz.inf.ontop.model.term.functionsymbol.Predicate;
import it.unibz.inf.ontop.model.type.LanguageTag;

import java.util.Optional;

public class RegularRDFDatatype extends AbstractRDFDatatype {

    protected RegularRDFDatatype(Predicate.COL_TYPE colType) {
        super(colType);
        if (colType == Predicate.COL_TYPE.LITERAL_LANG)
            throw new IllegalArgumentException("A lang string is not a regular RDF data type");
    }

    @Override
    public Optional<LanguageTag> getLanguageTag() {
        return Optional.empty();
    }
}