package it.unibz.inf.ontop.iq.node.impl;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.node.ExtendedProjectionNode;
import it.unibz.inf.ontop.iq.node.FilterNode;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.substitution.impl.ImmutableSubstitutionTools;
import it.unibz.inf.ontop.substitution.impl.ImmutableUnificationTools;
import it.unibz.inf.ontop.utils.CoreUtilsFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class ExtendedProjectionNodeImpl extends CompositeQueryNodeImpl implements ExtendedProjectionNode {

    private final ImmutableUnificationTools unificationTools;
    protected final ConstructionNodeTools constructionNodeTools;
    private final ImmutableSubstitutionTools substitutionTools;
    protected final TermFactory termFactory;
    private final CoreUtilsFactory coreUtilsFactory;

    public ExtendedProjectionNodeImpl(SubstitutionFactory substitutionFactory, IntermediateQueryFactory iqFactory,
                                      ImmutableUnificationTools unificationTools,
                                      ConstructionNodeTools constructionNodeTools,
                                      ImmutableSubstitutionTools substitutionTools, TermFactory termFactory,
                                      CoreUtilsFactory coreUtilsFactory) {
        super(substitutionFactory, iqFactory);
        this.unificationTools = unificationTools;
        this.constructionNodeTools = constructionNodeTools;
        this.substitutionTools = substitutionTools;
        this.termFactory = termFactory;
        this.coreUtilsFactory = coreUtilsFactory;
    }

    @Override
    public IQTree applyDescendingSubstitution(
            ImmutableSubstitution<? extends VariableOrGroundTerm> descendingSubstitution,
            Optional<ImmutableExpression> constraint, IQTree child) {

        return applyDescendingSubstitution(descendingSubstitution, child,
                (c, r) -> propagateDescendingSubstitutionToChild(c, r, constraint));
    }

    /**
     *
     * TODO: better handle the constraint
     *
     * Returns the new child
     */
    private IQTree propagateDescendingSubstitutionToChild(IQTree child,
                                                          ConstructionNodeImpl.PropagationResults<VariableOrGroundTerm> tauFPropagationResults,
                                                          Optional<ImmutableExpression> constraint) throws EmptyTreeException {

        Optional<ImmutableExpression> descendingConstraint;
        if (constraint.isPresent()) {
            ImmutableExpression initialConstraint = constraint.get();

            VariableNullability extendedVariableNullability = child.getVariableNullability()
                    .extendToExternalVariables(initialConstraint.getVariableStream());

            descendingConstraint = computeChildConstraint(tauFPropagationResults.theta, initialConstraint,
                    extendedVariableNullability);
        }
        else
            descendingConstraint = Optional.empty();

        return Optional.of(tauFPropagationResults.delta)
                .filter(delta -> !delta.isEmpty())
                .map(delta -> child.applyDescendingSubstitution(delta, descendingConstraint))
                .orElse(child);
    }

    @Override
    public IQTree applyDescendingSubstitutionWithoutOptimizing(
            ImmutableSubstitution<? extends VariableOrGroundTerm> descendingSubstitution, IQTree child) {
        return applyDescendingSubstitution(descendingSubstitution, child,
                (c, r) -> Optional.of(r.delta)
                        .filter(delta -> !delta.isEmpty())
                        .map(c::applyDescendingSubstitutionWithoutOptimizing)
                        .orElse(c));
    }

    private IQTree applyDescendingSubstitution(ImmutableSubstitution<? extends VariableOrGroundTerm> tau, IQTree child,
                                               DescendingSubstitutionChildUpdateFunction updateChildFct) {

        ImmutableSet<Variable> newProjectedVariables = constructionNodeTools.computeNewProjectedVariables(tau, getVariables());

        ImmutableSubstitution<NonFunctionalTerm> tauC = tau.getFragment(NonFunctionalTerm.class);
        ImmutableSubstitution<GroundFunctionalTerm> tauF = tau.getFragment(GroundFunctionalTerm.class);

        try {
            ConstructionNodeImpl.PropagationResults<NonFunctionalTerm> tauCPropagationResults = propagateTauC(tauC, child);
            ConstructionNodeImpl.PropagationResults<VariableOrGroundTerm> tauFPropagationResults = propagateTauF(tauF, tauCPropagationResults);

            Optional<FilterNode> filterNode = tauFPropagationResults.filter
                    .map(iqFactory::createFilterNode);

            IQTree newChild = updateChildFct.apply(child, tauFPropagationResults);

            Optional<ExtendedProjectionNode> projectionNode = computeNewProjectionNode(newProjectedVariables,
                    tauFPropagationResults.theta, newChild);

            IQTree filterTree = filterNode
                    .map(n -> (IQTree) iqFactory.createUnaryIQTree(n, newChild))
                    .orElse(newChild);

            return projectionNode
                    .map(n -> (IQTree) iqFactory.createUnaryIQTree(n, filterTree))
                    .orElse(filterTree);

        } catch (EmptyTreeException e) {
            return iqFactory.createEmptyNode(newProjectedVariables);
        }
    }

    protected abstract Optional<ExtendedProjectionNode> computeNewProjectionNode(
            ImmutableSet<Variable> newProjectedVariables, ImmutableSubstitution<ImmutableTerm> theta, IQTree newChild);

    private ConstructionNodeImpl.PropagationResults<NonFunctionalTerm> propagateTauC(ImmutableSubstitution<NonFunctionalTerm> tauC, IQTree child)
            throws EmptyTreeException {

        ImmutableSet<Variable> projectedVariables = getVariables();
        ImmutableSubstitution<? extends ImmutableTerm> substitution = getSubstitution();

        /* ---------------
         * tauC to thetaC
         * ---------------
         */

        ImmutableSubstitution<NonFunctionalTerm> thetaC = substitution.getFragment(NonFunctionalTerm.class);

        // Projected variables after propagating tauC
        ImmutableSet<Variable> vC = constructionNodeTools.computeNewProjectedVariables(tauC, projectedVariables);

        ImmutableSubstitution<NonFunctionalTerm> newEta = unificationTools.computeMGUS2(thetaC, tauC)
                .map(eta -> substitutionTools.prioritizeRenaming(eta, vC))
                .orElseThrow(ConstructionNodeImpl.EmptyTreeException::new);

        ImmutableSubstitution<NonFunctionalTerm> thetaCBar = substitutionFactory.getSubstitution(
                newEta.getImmutableMap().entrySet().stream()
                        .filter(e -> vC.contains(e.getKey()))
                        .collect(ImmutableCollectors.toMap()));

        ImmutableSubstitution<NonFunctionalTerm> deltaC = extractDescendingSubstitution(newEta,
                v -> v, thetaC, thetaCBar, projectedVariables);

        /* ---------------
         * deltaC to thetaF
         * ---------------
         */
        ImmutableSubstitution<ImmutableFunctionalTerm> thetaF = substitution.getFragment(ImmutableFunctionalTerm.class);

        ImmutableMultimap<ImmutableTerm, ImmutableFunctionalTerm> m = thetaF.getImmutableMap().entrySet().stream()
                .collect(ImmutableCollectors.toMultimap(
                        e -> deltaC.apply(e.getKey()),
                        e -> deltaC.applyToFunctionalTerm(e.getValue())));

        ImmutableSubstitution<ImmutableFunctionalTerm> thetaFBar = substitutionFactory.getSubstitution(
                m.asMap().entrySet().stream()
                        .filter(e -> e.getKey() instanceof Variable)
                        .filter(e -> !child.getVariables().contains(e.getKey()))
                        .collect(ImmutableCollectors.toMap(
                                e -> (Variable) e.getKey(),
                                e -> e.getValue().iterator().next()
                        )));


        ImmutableSubstitution<ImmutableTerm> gamma = extractDescendingSubstitution(deltaC,
                thetaFBar::apply,
                thetaF, thetaFBar,
                projectedVariables);
        ImmutableSubstitution<NonFunctionalTerm> newDeltaC = gamma.getFragment(NonFunctionalTerm.class);

        Optional<ImmutableExpression> f = computeF(m, thetaFBar, gamma, newDeltaC);

        return new ConstructionNodeImpl.PropagationResults<>(thetaCBar, thetaFBar, newDeltaC, f);

    }

    private Optional<ImmutableExpression> computeF(ImmutableMultimap<ImmutableTerm, ImmutableFunctionalTerm> m,
                                                   ImmutableSubstitution<ImmutableFunctionalTerm> thetaFBar,
                                                   ImmutableSubstitution<ImmutableTerm> gamma,
                                                   ImmutableSubstitution<NonFunctionalTerm> newDeltaC) {

        ImmutableSet<Map.Entry<Variable, ImmutableFunctionalTerm>> thetaFBarEntries = thetaFBar.getImmutableMap().entrySet();

        Stream<ImmutableExpression> thetaFRelatedExpressions = m.entries().stream()
                .filter(e -> !thetaFBarEntries.contains(e))
                .map(e -> termFactory.getStrictEquality(thetaFBar.apply(e.getKey()), e.getValue()));

        Stream<ImmutableExpression> blockedExpressions = gamma.getImmutableMap().entrySet().stream()
                .filter(e -> !newDeltaC.isDefining(e.getKey()))
                .map(e -> termFactory.getStrictEquality(e.getKey(), e.getValue()));

        return termFactory.getConjunction(Stream.concat(thetaFRelatedExpressions, blockedExpressions));
    }

    private ConstructionNodeImpl.PropagationResults<VariableOrGroundTerm> propagateTauF(ImmutableSubstitution<GroundFunctionalTerm> tauF,
                                                                                        ConstructionNodeImpl.PropagationResults<NonFunctionalTerm> tauCPropagationResults) {

        ImmutableSubstitution<ImmutableTerm> thetaBar = tauCPropagationResults.theta;

        ImmutableSubstitution<VariableOrGroundTerm> delta = substitutionFactory.getSubstitution(
                tauF.getImmutableMap().entrySet().stream()
                        .filter(e -> !thetaBar.isDefining(e.getKey()))
                        .filter(e -> !tauCPropagationResults.delta.isDefining(e.getKey()))
                        .collect(ImmutableCollectors.toMap(
                                Map.Entry::getKey,
                                e -> (VariableOrGroundTerm)e.getValue()
                        )))
                .composeWith2(tauCPropagationResults.delta);

        ImmutableSubstitution<ImmutableTerm> newTheta = substitutionFactory.getSubstitution(
                thetaBar.getImmutableMap().entrySet().stream()
                        .filter(e -> !tauF.isDefining(e.getKey()))
                        .collect(ImmutableCollectors.toMap()));

        Stream<ImmutableExpression> newConditionStream =
                Stream.concat(
                        // tauF vs thetaBar
                        tauF.getImmutableMap().entrySet().stream()
                                .filter(e -> thetaBar.isDefining(e.getKey()))
                                .map(e -> termFactory.getStrictEquality(thetaBar.apply(e.getKey()), tauF.apply(e.getValue()))),
                        // tauF vs newDelta
                        tauF.getImmutableMap().entrySet().stream()
                                .filter(e -> tauCPropagationResults.delta.isDefining(e.getKey()))
                                .map(e -> termFactory.getStrictEquality(tauCPropagationResults.delta.apply(e.getKey()),
                                        tauF.apply(e.getValue()))));

        Optional<ImmutableExpression> newF = termFactory.getConjunction(Stream.concat(
                tauCPropagationResults.filter
                        .map(ImmutableExpression::flattenAND)
                        .orElseGet(Stream::empty),
                newConditionStream));

        return new ConstructionNodeImpl.PropagationResults<>(newTheta, delta, newF);
    }

    @Override
    public IQTree propagateDownConstraint(ImmutableExpression constraint, IQTree child) {
        try {
            Optional<ImmutableExpression> childConstraint = computeChildConstraint(getSubstitution(), constraint,
                    child.getVariableNullability().extendToExternalVariables(constraint.getVariableStream()));
            IQTree newChild = childConstraint
                    .map(child::propagateDownConstraint)
                    .orElse(child);
            return iqFactory.createUnaryIQTree(this, newChild);

        } catch (EmptyTreeException e) {
            return iqFactory.createEmptyNode(getVariables());
        }
    }

    private Optional<ImmutableExpression> computeChildConstraint(ImmutableSubstitution<? extends ImmutableTerm> theta,
                                                                 ImmutableExpression initialConstraint,
                                                                 VariableNullability variableNullabilityForConstraint)
            throws EmptyTreeException {

        ImmutableExpression.Evaluation descendingConstraintResults = theta.applyToBooleanExpression(initialConstraint)
                .evaluate(variableNullabilityForConstraint);

        if (descendingConstraintResults.isEffectiveFalse())
            throw new EmptyTreeException();

        return descendingConstraintResults.getExpression();
    }

    /**
     * TODO: find a better name
     *
     */
    private <T extends ImmutableTerm> ImmutableSubstitution<T> extractDescendingSubstitution(
            ImmutableSubstitution<? extends NonFunctionalTerm> substitution,
            java.util.function.Function<NonFunctionalTerm, T> valueTransformationFct,
            ImmutableSubstitution<? extends ImmutableTerm> partialTheta,
            ImmutableSubstitution<? extends ImmutableTerm> newPartialTheta,
            ImmutableSet<Variable> originalProjectedVariables) {

        return substitutionFactory.getSubstitution(
                substitution.getImmutableMap().entrySet().stream()
                        .filter(e -> {
                            Variable v = e.getKey();
                            return (!partialTheta.isDefining(v))
                                    && ((!newPartialTheta.isDefining(v)) || originalProjectedVariables.contains(v));
                        })
                        .collect(ImmutableCollectors.toMap(
                                Map.Entry::getKey,
                                e -> valueTransformationFct.apply(e.getValue())
                        )));
    }

    @Override
    public VariableNullability getVariableNullability(IQTree child) {
        return child.getVariableNullability().update(getSubstitution(), getVariables());
    }

    @Override
    public boolean isConstructed(Variable variable, IQTree child) {
        return getSubstitution().isDefining(variable)
                || (getChildVariables().contains(variable) && child.isConstructed(variable));
    }

    @FunctionalInterface
    protected interface DescendingSubstitutionChildUpdateFunction {

        IQTree apply(IQTree child, ConstructionNodeImpl.PropagationResults<VariableOrGroundTerm> tauFPropagationResults)
                throws EmptyTreeException;

    }

    protected static class EmptyTreeException extends Exception {
    }
}
