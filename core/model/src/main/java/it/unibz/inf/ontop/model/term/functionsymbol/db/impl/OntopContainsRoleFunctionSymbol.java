package it.unibz.inf.ontop.model.term.functionsymbol.db.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.evaluator.QueryContext;
import it.unibz.inf.ontop.model.term.ImmutableExpression;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.type.DBTermType;

public class OntopContainsRoleFunctionSymbol extends AbstractBooleanAuthorizationFunctionSymbol {

    public static final String ONTOP_CONTAINS_ROLE = "ONTOP_CONTAINS_ROLE";
    protected OntopContainsRoleFunctionSymbol(DBTermType dbStringType, DBTermType dbBooleanTermType) {
        super(ONTOP_CONTAINS_ROLE, ImmutableList.of(dbStringType), dbBooleanTermType);
    }

    @Override
    public ImmutableExpression simplifyWithContext(ImmutableList<ImmutableTerm> terms, QueryContext queryContext,
                                                   TermFactory termFactory) {
        var subTerm = terms.get(0);

        return termFactory.getDisjunction(
                queryContext.getRoles().stream()
                        .map(r -> termFactory.getStrictEquality(termFactory.getDBStringConstant(r), subTerm)))
                .orElseGet(() -> termFactory.getFalseOrNullFunctionalTerm(ImmutableList.of(termFactory.getDBIsNull(subTerm))));
    }
}