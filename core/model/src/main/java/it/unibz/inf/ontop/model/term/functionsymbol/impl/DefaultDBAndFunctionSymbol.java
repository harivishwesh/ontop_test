package it.unibz.inf.ontop.model.term.functionsymbol.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.DBAndFunctionSymbol;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultDBAndFunctionSymbol extends AbstractDBBooleanConnectorFunctionSymbol implements DBAndFunctionSymbol {

    private final String argumentSeparator;

    protected DefaultDBAndFunctionSymbol(String nameInDialect, int arity, DBTermType dbBooleanTermType) {
        super(nameInDialect, arity, dbBooleanTermType);
        if (arity < 2)
            throw new IllegalArgumentException("Arity must be >= 2");
        this.argumentSeparator = String.format(" %s ", nameInDialect);
    }

    @Override
    public boolean isInjective(ImmutableList<? extends ImmutableTerm> arguments, ImmutableSet<Variable> nonNullVariables) {
        return false;
    }

    @Override
    public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
        return true;
    }

    /**
     * NB: terms are assumed to be either TRUE, FALSE, NULL or ImmutableExpressions.
     * TODO: check it
     */
    @Override
    protected ImmutableTerm buildTermAfterEvaluation(ImmutableList<ImmutableTerm> newTerms,
                                                     boolean isInConstructionNodeInOptimizationPhase,
                                                     TermFactory termFactory) {
        return computeNewConjunction(newTerms, termFactory);
    }

    /**
     * Temporarily public
     * TODO: hide it again
     */
    public static ImmutableTerm computeNewConjunction(ImmutableList<ImmutableTerm> evaluatedTerms, TermFactory termFactory) {
        DBConstant falseValue = termFactory.getDBBooleanConstant(false);
        if (evaluatedTerms.stream()
                .anyMatch(falseValue::equals))
            return falseValue;

        Optional<ImmutableTerm> optionalNull = evaluatedTerms.stream()
                .filter(t -> (t instanceof Constant) && ((Constant) t).isNull())
                .findFirst();

        ImmutableList<ImmutableExpression> others = evaluatedTerms.stream()
                // We don't care about TRUE
                .filter(t -> (t instanceof ImmutableExpression))
                .map(t -> (ImmutableExpression) t)
                // Flattens nested ANDs
                .flatMap(t -> (t.getFunctionSymbol() instanceof DBAndFunctionSymbol)
                        ? t.getTerms().stream()
                            .map(s -> (ImmutableExpression)s)
                        : Stream.of(t))
                .distinct()
                .collect(ImmutableCollectors.toList());

        return others.isEmpty()
                ? optionalNull.orElseGet(() -> termFactory.getDBBooleanConstant(true))
                :  optionalNull
                .map(n -> (ImmutableTerm) termFactory.getFalseOrNullFunctionalTerm(others))
                .orElseGet(() -> others.size() == 1 ? others.get(0) : termFactory.getConjunction(others));
    }

    @Override
    public String getNativeDBString(ImmutableList<? extends ImmutableTerm> terms, Function<ImmutableTerm, String> termConverter, TermFactory termFactory) {
        return inBrackets(terms.stream()
                        .map(termConverter::apply)
                        .collect(Collectors.joining(argumentSeparator)));
    }

    @Override
    public boolean blocksNegation() {
        return false;
    }

    @Override
    public ImmutableExpression negate(ImmutableList<? extends ImmutableTerm> subTerms, TermFactory termFactory) {
        return termFactory.getDisjunction(
                subTerms.stream()
                    .map(t -> (ImmutableExpression) t)
                    .map(t -> t.negate(termFactory))
                    .collect(ImmutableCollectors.toList()));
    }
}
