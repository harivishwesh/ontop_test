package it.unibz.inf.ontop.model.term.functionsymbol.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.RDFTermTypeConstant;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBIfThenFunctionSymbol;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.model.type.MetaRDFTermType;
import it.unibz.inf.ontop.model.type.RDFTermType;
import it.unibz.inf.ontop.model.type.TermTypeInference;

import java.util.Optional;
import java.util.function.Function;


/**
 * This function mirrors UnaryLatelyTypedFunctionSymbolImpl, but applies to 2 terms rather than one.
 * @see UnaryLatelyTypedFunctionSymbolImpl
 *
 * This function symbol takes "two" lexical terms and a meta RDF term type term as input.
 *
 * Once the concrete RDF term type is determined, looks for its closest DB type
 * and then selects the corresponding DB function symbol to use (let's call it f).
 *
 * Then the lexical term (l) is transformed into a natural one (n).
 *
 * By default, returns f(n) but further transformations can be applied over it.
 *
 */
public class BinaryLatelyTypedFunctionSymbolImpl extends FunctionSymbolImpl {

    private final DBTermType targetType;
    private final Function<DBTermType, DBFunctionSymbol> dbFunctionSymbolFct;

    protected BinaryLatelyTypedFunctionSymbolImpl(DBTermType dbStringType0, DBTermType dbStringType1,
                                                  MetaRDFTermType metaRDFTermType, DBTermType targetType,
                                                  Function<DBTermType, DBFunctionSymbol> dbFunctionSymbolFct) {
        super("BINARY_LATELY_TYPE_" + dbFunctionSymbolFct, ImmutableList.of(dbStringType0, dbStringType1, metaRDFTermType));
        this.targetType = targetType;
        this.dbFunctionSymbolFct = dbFunctionSymbolFct;
    }

    @Override
    protected boolean tolerateNulls() {
        return false;
    }

    @Override
    protected boolean mayReturnNullWithoutNullArguments() {
        return false;
    }

    @Override
    public boolean isAlwaysInjectiveInTheAbsenceOfNonInjectiveFunctionalTerms() {
        return false;
    }

    /**
     * Could be inferred after simplification
     */
    @Override
    public Optional<TermTypeInference> inferType(ImmutableList<? extends ImmutableTerm> terms) {
        return Optional.of(TermTypeInference.declareTermType(targetType));
    }

    @Override
    public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
        return false;
    }

    @Override
    protected ImmutableTerm buildTermAfterEvaluation(ImmutableList<ImmutableTerm> newTerms, TermFactory termFactory,
                                                     VariableNullability variableNullability) {
        ImmutableTerm rdfTypeTerm = newTerms.get(2);
        if (rdfTypeTerm instanceof RDFTermTypeConstant) {
            RDFTermType rdfType = ((RDFTermTypeConstant) rdfTypeTerm).getRDFTermType();
            DBTermType dbType = rdfType.getClosestDBType(termFactory.getTypeFactory().getDBTypeFactory());

            ImmutableFunctionalTerm dbTerm = termFactory.getImmutableFunctionalTerm(
                    dbFunctionSymbolFct.apply(dbType),
                    termFactory.getConversionFromRDFLexical2DB(dbType, newTerms.get(0), rdfType),
                    termFactory.getConversionFromRDFLexical2DB(dbType, newTerms.get(1), rdfType));

            return transformNaturalDBTerm(dbTerm, dbType, rdfType, termFactory)
                    .simplify(variableNullability);
        }
        else
            // Tries to lift the DB case of the rdf type term if there is any
            return Optional.of(rdfTypeTerm)
                    .filter(t -> t instanceof ImmutableFunctionalTerm)
                    .map(t -> (ImmutableFunctionalTerm)t)
                    .filter(t -> t.getFunctionSymbol() instanceof DBIfThenFunctionSymbol)
                    .map(t -> ((DBIfThenFunctionSymbol) t.getFunctionSymbol())
                            .pushDownRegularFunctionalTerm(
                                    termFactory.getImmutableFunctionalTerm(this, newTerms),
                                    newTerms.indexOf(t),
                                    termFactory))
                    .map(t -> t.simplify(variableNullability))
                    // Tries to lift magic numbers
                    .orElseGet(() -> tryToLiftMagicNumbers(newTerms, termFactory, variableNullability, false)
                            .orElseGet(() -> super.buildTermAfterEvaluation(newTerms, termFactory, variableNullability)));
    }

    /**
     * By default, returns the natural DB term
     */
    protected ImmutableTerm transformNaturalDBTerm(ImmutableFunctionalTerm dbTerm, DBTermType inputDBType, RDFTermType rdfType,
                                                   TermFactory termFactory) {
        return dbTerm;
    }
}

