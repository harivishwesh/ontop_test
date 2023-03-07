package it.unibz.inf.ontop.generation.normalization.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import it.unibz.inf.ontop.generation.normalization.DialectExtraNormalizer;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInternalBugException;
import it.unibz.inf.ontop.injection.CoreSingletons;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.transform.impl.DefaultRecursiveIQTreeExtendedTransformer;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.NonGroundTerm;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.term.functionsymbol.db.NonDeterministicDBFunctionSymbol;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class ProjectOrderByTermsNormalizer extends DefaultRecursiveIQTreeExtendedTransformer<VariableGenerator> implements DialectExtraNormalizer {

    private final boolean onlyInPresenceOfDistinct;
    private final SubstitutionFactory substitutionFactory;

    protected ProjectOrderByTermsNormalizer(boolean onlyInPresenceOfDistinct, CoreSingletons coreSingletons) {
        super(coreSingletons);
        this.onlyInPresenceOfDistinct = onlyInPresenceOfDistinct;
        this.substitutionFactory = coreSingletons.getSubstitutionFactory();
    }

    @Override
    public IQTree transform(IQTree tree, VariableGenerator variableGenerator) {
        return tree.acceptTransformer(this, variableGenerator);
    }

    @Override
    public IQTree transformConstruction(IQTree tree, ConstructionNode rootNode, IQTree child, VariableGenerator variableGenerator) {
        return transformConstructionSliceDistinctOrOrderByTree(tree, variableGenerator);
    }

    @Override
    public IQTree transformDistinct(IQTree tree, DistinctNode rootNode, IQTree child, VariableGenerator variableGenerator) {
        return transformConstructionSliceDistinctOrOrderByTree(tree, variableGenerator);
    }

    @Override
    public IQTree transformSlice(IQTree tree, SliceNode sliceNode, IQTree child, VariableGenerator variableGenerator) {
        return transformConstructionSliceDistinctOrOrderByTree(tree, variableGenerator);
    }

    @Override
    public IQTree transformOrderBy(IQTree tree, OrderByNode rootNode, IQTree child, VariableGenerator variableGenerator) {
        return transformConstructionSliceDistinctOrOrderByTree(tree, variableGenerator);
    }

    protected IQTree transformConstructionSliceDistinctOrOrderByTree(IQTree tree, VariableGenerator variableGenerator) {
        IQTree newTree = applyTransformerToDescendantTree(tree, variableGenerator);

        Decomposition decomposition = Decomposition.decomposeTree(newTree);

        return decomposition.orderByNode
                .filter(o -> decomposition.distinctNode.isPresent() || (!onlyInPresenceOfDistinct))
                .map(o -> new Analysis(decomposition.distinctNode.isPresent(), decomposition.constructionNode, o.getComparators()))
                .map(a -> normalize(newTree, a, variableGenerator))
                .orElse(newTree);
    }

    /**
     * Applies the transformer to a descendant tree, and rebuilds the parent tree.
     *
     * The root node of the parent tree must be a ConstructionNode, a SliceNode, a DistinctNode or an OrderByNode
     */
    private IQTree applyTransformerToDescendantTree(IQTree tree, VariableGenerator variableGenerator) {
        Decomposition decomposition = Decomposition.decomposeTree(tree);

        //Recursive
        IQTree newDescendantTree = transform(decomposition.descendantTree, variableGenerator);

        return decomposition.descendantTree.equals(newDescendantTree)
            ? tree
            : decomposition.rebuildWithNewDescendantTree(newDescendantTree, iqFactory);
    }

    private IQTree normalize(IQTree tree, Analysis analysis, VariableGenerator variableGenerator) {

        ImmutableSet<Variable> projectedVariables = tree.getVariables();

        ImmutableSet<ImmutableTerm> alreadyDefinedTerms = analysis.constructionNode
                .map(c -> Stream.concat(
                        projectedVariables.stream(),
                        c.getSubstitution().getImmutableMap().values().stream())
                        .collect(ImmutableCollectors.toSet()))
                .orElseGet(() -> (ImmutableSet<ImmutableTerm>)(ImmutableSet<?>) tree.getVariables());

        ImmutableSet<Map.Entry<Variable, NonGroundTerm>> newBindings = analysis.sortConditions.stream()
                .map(OrderByNode.OrderComparator::getTerm)
                .filter(t -> !alreadyDefinedTerms.contains(t))
                .map(t -> (t instanceof Variable)
                        ? Maps.immutableEntry((Variable) t, t)
                        : Maps.immutableEntry(variableGenerator.generateNewVariable(), t))
                .collect(ImmutableCollectors.toSet());

        if (newBindings.isEmpty())
            return tree;


        if (!isSupported(projectedVariables, analysis, newBindings)) {
            throw new DistinctOrderByDialectLimitationException();
        }

        ImmutableSet<Variable> newProjectedVariables = Stream.concat(
                projectedVariables.stream(),
                newBindings.stream()
                        .map(Map.Entry::getKey))
                .collect(ImmutableCollectors.toSet());

        ImmutableSubstitution<ImmutableTerm> newSubstitution = substitutionFactory.getSubstitution(
                Stream.concat(
                        newBindings.stream()
                        .filter(e -> !e.getKey().equals(e.getValue()))
                        .map(e -> (Map.Entry<Variable,ImmutableTerm>)(Map.Entry<Variable,?>)e),
                analysis.constructionNode
                        .map(c -> c.getSubstitution().getImmutableMap().entrySet().stream())
                        .orElseGet(Stream::empty))
                .collect(ImmutableCollectors.toMap()));

        ConstructionNode newConstructionNode = iqFactory.createConstructionNode(newProjectedVariables, newSubstitution);

        return analysis.constructionNode
                .map(n -> updateTopConstructionNode(tree, newConstructionNode))
                .orElseGet(() -> insertConstructionNode(tree, newConstructionNode));
    }

    /**
     * Decides whether or not new bindings can be added
     *
     */
    protected boolean isSupported(ImmutableSet<Variable> projectedVariables, Analysis analysis,
                                ImmutableSet<Map.Entry<Variable, NonGroundTerm>> newBindings) {
        if (!analysis.hasDistinct)
            return true;

        ImmutableSet<ImmutableTerm> alreadyProjectedTerms = Stream.concat(
                projectedVariables.stream(),
                analysis.constructionNode
                        .map(c -> c.getSubstitution().getImmutableMap().values().stream())
                        .orElseGet(Stream::empty))
                .collect(ImmutableCollectors.toSet());

        return newBindings.stream()
                .map(Map.Entry::getValue)
                .noneMatch(t -> mayImpactDistinct(t, alreadyProjectedTerms));
    }

    /**
     * TODO: explain
     */
    protected boolean mayImpactDistinct(ImmutableTerm term, ImmutableSet<ImmutableTerm> alreadyProjectedTerms) {
        if (term instanceof ImmutableFunctionalTerm) {
            ImmutableFunctionalTerm functionalTerm = (ImmutableFunctionalTerm) term;
            if (functionalTerm.getFunctionSymbol() instanceof NonDeterministicDBFunctionSymbol)
                return true;
            else if (alreadyProjectedTerms.contains(term))
                return false;
            else
                return functionalTerm.getTerms().stream()
                        // Recursive
                        .anyMatch(t -> mayImpactDistinct(t, alreadyProjectedTerms));
        }
        else if (term instanceof Variable) {
            return !(alreadyProjectedTerms.contains(term));
        }
        // Constant
        else
            return false;
    }

    /**
     * Recursive
     */
    private IQTree updateTopConstructionNode(IQTree tree, ConstructionNode newConstructionNode) {
        QueryNode rootNode = tree.getRootNode();
        if (rootNode instanceof ConstructionNode)
            return iqFactory.createUnaryIQTree(newConstructionNode,
                    ((UnaryIQTree)tree).getChild());
        else if (rootNode instanceof UnaryOperatorNode)
            return iqFactory.createUnaryIQTree(
                    (UnaryOperatorNode) rootNode,
                    // Recursive
                    updateTopConstructionNode(((UnaryIQTree)tree).getChild(), newConstructionNode));
        else
            throw new MinorOntopInternalBugException("Was expected to reach a ConstructionNode before a non-unary node");
    }

    /**
     * Recursive
     */
    private IQTree insertConstructionNode(IQTree tree, ConstructionNode newConstructionNode) {
        QueryNode rootNode = tree.getRootNode();
        if ((rootNode instanceof DistinctNode)
                || (rootNode instanceof SliceNode))
            return iqFactory.createUnaryIQTree(
                    (UnaryOperatorNode) rootNode,
                    // Recursive
                    insertConstructionNode(((UnaryIQTree)tree).getChild(), newConstructionNode));
        else
            return iqFactory.createUnaryIQTree(newConstructionNode, tree);
    }


    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static class Analysis {
        final boolean hasDistinct;
        final Optional<ConstructionNode> constructionNode;
        final ImmutableList<OrderByNode.OrderComparator> sortConditions;

        private Analysis(boolean hasDistinct, Optional<ConstructionNode> constructionNode,
                         ImmutableList<OrderByNode.OrderComparator> sortConditions) {
            this.hasDistinct = hasDistinct;
            this.constructionNode = constructionNode;
            this.sortConditions = sortConditions;
        }
    }

    /**
     * Supposed to be an "internal bug" has the restriction should have been detected before, at the SPARQL level
     * (detection to be implemented)
     */
    private static class DistinctOrderByDialectLimitationException extends OntopInternalBugException {

        protected DistinctOrderByDialectLimitationException() {
            super("The dialect requires ORDER BY conditions to be projected but a DISTINCT prevents some of them");
        }
    }

    /**
     * As the ancestors, we may get the following nodes: OrderByNode, ConstructionNode, DistinctNode, SliceNode.
     * NB: this order is bottom-up.
     * Some nodes may be missing but the order must be respected. There are no multiple instances of the same kind of node.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected static class Decomposition {
        final Optional<SliceNode> sliceNode;
        final Optional<DistinctNode> distinctNode;
        final Optional<ConstructionNode> constructionNode;
        final Optional<OrderByNode> orderByNode;

        final IQTree descendantTree;

        private Decomposition(Optional<SliceNode> sliceNode,
                              Optional<DistinctNode> distinctNode,
                              Optional<ConstructionNode> constructionNode,
                              Optional<OrderByNode> orderByNode,
                              IQTree descendantTree) {
            this.sliceNode = sliceNode;
            this.distinctNode = distinctNode;
            this.constructionNode = constructionNode;
            this.orderByNode = orderByNode;
            this.descendantTree = descendantTree;
        }

        IQTree rebuildWithNewDescendantTree(IQTree newDescendantTree, IntermediateQueryFactory iqFactory) {
            return Stream.of(orderByNode, constructionNode, distinctNode, sliceNode)
                    .flatMap(Optional::stream)
                    .reduce(newDescendantTree,
                            (t, n) -> iqFactory.createUnaryIQTree(n, t),
                            (t1, t2) -> { throw new MinorOntopInternalBugException("Must not be run in parallel"); });
        }

        static Decomposition decomposeTree(IQTree tree) {

            QueryNode rootNode = tree.getRootNode();
            Optional<SliceNode> sliceNode = Optional.of(rootNode)
                    .filter(n -> n instanceof SliceNode)
                    .map(n -> (SliceNode) n);

            IQTree firstNonSliceTree = sliceNode
                    .map(n -> ((UnaryIQTree) tree).getChild())
                    .orElse(tree);

            Optional<DistinctNode> distinctNode = Optional.of(firstNonSliceTree)
                    .map(IQTree::getRootNode)
                    .filter(n -> n instanceof DistinctNode)
                    .map(n -> (DistinctNode) n);

            IQTree firstNonSliceDistinctTree = distinctNode
                    .map(n -> ((UnaryIQTree) firstNonSliceTree).getChild())
                    .orElse(firstNonSliceTree);

            Optional<ConstructionNode> constructionNode = Optional.of(firstNonSliceDistinctTree)
                    .map(IQTree::getRootNode)
                    .filter(n -> n instanceof ConstructionNode)
                    .map(n -> (ConstructionNode) n);

            IQTree firstNonSliceDistinctConstructionTree = constructionNode
                    .map(n -> ((UnaryIQTree) firstNonSliceDistinctTree).getChild())
                    .orElse(firstNonSliceDistinctTree);

            Optional<OrderByNode> orderByNode = Optional.of(firstNonSliceDistinctConstructionTree)
                    .map(IQTree::getRootNode)
                    .filter(n -> n instanceof OrderByNode)
                    .map(n -> (OrderByNode) n);

            IQTree descendantTree = orderByNode
                    .map(n -> ((UnaryIQTree) firstNonSliceDistinctConstructionTree).getChild())
                    .orElse(firstNonSliceDistinctConstructionTree);

            return new Decomposition(sliceNode, distinctNode, constructionNode, orderByNode, descendantTree);
        }
    }
}
