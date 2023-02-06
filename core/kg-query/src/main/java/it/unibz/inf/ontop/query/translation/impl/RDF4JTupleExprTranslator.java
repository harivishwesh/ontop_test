package it.unibz.inf.ontop.query.translation.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInvalidKGQueryException;
import it.unibz.inf.ontop.exception.OntopUnsupportedKGQueryException;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.QueryNodeRenamer;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.transform.impl.HomogeneousIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.atom.AtomFactory;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbolFactory;
import it.unibz.inf.ontop.model.type.RDFDatatype;
import it.unibz.inf.ontop.model.type.TermTypeInference;
import it.unibz.inf.ontop.model.type.TypeFactory;
import it.unibz.inf.ontop.model.vocabulary.SPARQL;
import it.unibz.inf.ontop.model.vocabulary.XSD;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.InjectiveVar2VarSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.CoreUtilsFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class RDF4JTupleExprTranslator {

    private final ImmutableMap<Variable, GroundTerm> externalBindings;
    private final @Nullable Dataset dataset;
    private final boolean treatBNodeAsVariable;

    private final CoreUtilsFactory coreUtilsFactory;
    private final SubstitutionFactory substitutionFactory;
    private final IntermediateQueryFactory iqFactory;
    private final AtomFactory atomFactory;
    private final TermFactory termFactory;
    private final FunctionSymbolFactory functionSymbolFactory;
    private final RDF rdfFactory;
    private final TypeFactory typeFactory;

    public RDF4JTupleExprTranslator(ImmutableMap<Variable, GroundTerm> externalBindings, @Nullable Dataset dataset, boolean treatBNodeAsVariable, CoreUtilsFactory coreUtilsFactory, SubstitutionFactory substitutionFactory, IntermediateQueryFactory iqFactory, AtomFactory atomFactory, TermFactory termFactory, FunctionSymbolFactory functionSymbolFactory, RDF rdfFactory, TypeFactory typeFactory) {
        this.externalBindings = externalBindings;
        this.dataset = dataset;
        this.treatBNodeAsVariable = treatBNodeAsVariable;
        this.coreUtilsFactory = coreUtilsFactory;
        this.substitutionFactory = substitutionFactory;
        this.iqFactory = iqFactory;
        this.atomFactory = atomFactory;
        this.termFactory = termFactory;
        this.functionSymbolFactory = functionSymbolFactory;
        this.rdfFactory = rdfFactory;
        this.typeFactory = typeFactory;
    }

    public IQTree getTree(TupleExpr node) throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException {
        return translate(node).iqTree;
    }

    private TranslationResult translate(TupleExpr node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        if (node instanceof QueryRoot) {
            return translate(((QueryRoot) node).getArg());
        }

        if (node instanceof StatementPattern) {
            return translate((StatementPattern) node);
        }

        if (node instanceof Join)
            return translateJoinLikeNode((Join) node);

        if (node instanceof LeftJoin)
            return translateJoinLikeNode((LeftJoin) node);

        if (node instanceof Difference)
            return translate((Difference) node);

        if (node instanceof Union)
            return translate((Union) node);

        if (node instanceof Filter)
            return translate((Filter) node);

        if (node instanceof Projection)
            return translate((Projection) node);

        if (node instanceof Slice)
            return translate((Slice) node);

        if (node instanceof Distinct)
            return translate((Distinct) node);

        if (node instanceof Reduced)
            return translate((Reduced) node);

        if (node instanceof SingletonSet)
            return createTranslationResult(iqFactory.createTrueNode(), ImmutableSet.of());

        if (node instanceof Group)
            return translate((Group) node);

        if (node instanceof Extension)
            return translate((Extension) node);

        if (node instanceof BindingSetAssignment)
            return translate((BindingSetAssignment) node);

        if (node instanceof Order)
            return translate((Order) node);

        throw new OntopUnsupportedKGQueryException("Unsupported SPARQL operator: " + node.toString());
    }

    private static Sets.SetView<Variable> getSharedVariables(TranslationResult left, TranslationResult right) {
        return Sets.intersection(left.iqTree.getVariables(), right.iqTree.getVariables());
    }

    private VariableGenerator getVariableGenerator(TranslationResult left, TranslationResult right) {
        return coreUtilsFactory.createVariableGenerator(Sets.union(left.iqTree.getKnownVariables(), right.iqTree.getKnownVariables()));
    }

    private TranslationResult translate(Difference diff) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult leftTranslation = translate(diff.getLeftArg());
        TranslationResult rightTranslation = translate(diff.getRightArg());

        Sets.SetView<Variable> sharedVars = getSharedVariables(rightTranslation, leftTranslation);

        if (sharedVars.isEmpty()) {
            return leftTranslation;
        }

        VariableGenerator vGen = getVariableGenerator(leftTranslation, rightTranslation);

        InjectiveVar2VarSubstitution sub = sharedVars.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(vGen::generateNewVariableFromVar));

        InjectiveVar2VarSubstitution sharedVarsSub = sub.restrictDomainTo(sharedVars);

        ImmutableExpression ljCond = termFactory.getConjunction(Stream.concat(
                        sharedVarsSub.builder()
                                .toStream((v, t) -> termFactory.getDisjunction(
                                        getEqOrNullable(v, t, leftTranslation.nullableVariables, rightTranslation.nullableVariables))),
                        Stream.of(termFactory.getDisjunction(sharedVarsSub.builder()
                                .toStrictEqualities().collect(ImmutableCollectors.toList()))))
                .collect(ImmutableCollectors.toList()));

        ImmutableExpression filter = termFactory.getConjunction(sub.getRangeSet().stream()
                .map(termFactory::getDBIsNull)
                .collect(ImmutableCollectors.toList()));

        InjectiveVar2VarSubstitution leftNonProjVarsRenaming = getNonProjVarsRenaming(leftTranslation, rightTranslation, vGen);
        InjectiveVar2VarSubstitution rightNonProjVarsRenaming = getNonProjVarsRenaming(rightTranslation, leftTranslation, vGen);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createConstructionNode(leftTranslation.iqTree.getVariables()),
                        iqFactory.createUnaryIQTree(
                                iqFactory.createFilterNode(filter),
                                iqFactory.createBinaryNonCommutativeIQTree(
                                        iqFactory.createLeftJoinNode(ljCond),
                                        applyInDepthRenaming(leftTranslation.iqTree, leftNonProjVarsRenaming),
                                        applyInDepthRenaming(
                                                rightTranslation.iqTree.applyDescendingSubstitutionWithoutOptimizing(sub, vGen),
                                                rightNonProjVarsRenaming)))),
                leftTranslation.nullableVariables);
    }

    private IQTree applyInDepthRenaming(IQTree tree, InjectiveVar2VarSubstitution renaming) {
        if (renaming.isEmpty())
            return tree;

        QueryNodeRenamer nodeTransformer = new QueryNodeRenamer(iqFactory, renaming, atomFactory, substitutionFactory);
        HomogeneousIQTreeVisitingTransformer iqTransformer = new HomogeneousIQTreeVisitingTransformer(nodeTransformer, iqFactory);
        return iqTransformer.transform(tree);
    }

    private ImmutableList<ImmutableExpression> getEqOrNullable(Variable leftVar, Variable renamedVar, ImmutableSet<Variable> leftNullableVars,
                                                               ImmutableSet<Variable> rightNullableVars) {

        ImmutableExpression equality = termFactory.getStrictEquality(leftVar, renamedVar);

        if (leftNullableVars.contains(leftVar)) {
            return rightNullableVars.contains(leftVar)
                    ? ImmutableList.of(equality, termFactory.getDBIsNull(leftVar), termFactory.getDBIsNull(renamedVar))
                    : ImmutableList.of(equality, termFactory.getDBIsNull(leftVar));
        }
        else {
            return rightNullableVars.contains(leftVar)
                    ? ImmutableList.of(equality, termFactory.getDBIsNull(renamedVar))
                    : ImmutableList.of(equality);
        }
    }


    private TranslationResult translate(Group group) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(group.getArg());

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs =
                getGroupVarDefs(group.getGroupElements(), child.iqTree.getVariables());

        if (mergedVarDefs.size() > 1) {
            throw new Sparql2IqConversionException("Unexpected parsed SPARQL query: nested complex projections appear " +
                    "within an RDF4J Group node: " + group);
        }
        AggregationNode an = iqFactory.createAggregationNode(
                group.getGroupBindingNames().stream()
                        .map(termFactory::getVariable)
                        .collect(ImmutableCollectors.toSet()),
                mergedVarDefs.get(0).castTo(ImmutableFunctionalTerm.class)); // only one substitution guaranteed by the if

        UnaryIQTree aggregationTree = iqFactory.createUnaryIQTree(an, child.iqTree);

        ImmutableSet<Variable> nullableVariables = Sets.union(
                        Sets.intersection(an.getGroupingVariables(), child.nullableVariables),
                        an.getSubstitution().restrictRange(t -> t.getFunctionSymbol().isNullable(ImmutableSet.of(0)))
                                .getDomain())
                .immutableCopy();

        IQTree iqTree = applyExternalBindingFilter(aggregationTree, an.getSubstitution().getDomain());
        return createTranslationResult(iqTree, nullableVariables);
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getGroupVarDefs(List<GroupElem> list,
                                                                                ImmutableSet<Variable> childVariables) {
        List<VarDef> result = new ArrayList<>();
        Set<Variable> allowedVars = new HashSet<>(childVariables); // mutable: accumulator

        for (GroupElem elem : list) {
            RDF4JValueExprTranslator translator = getValueTranslator(allowedVars);
            ImmutableTerm term = translator.getTerm(elem.getOperator());
            Variable definedVar = termFactory.getVariable(elem.getName());
            allowedVars.add(definedVar);

            result.add(new VarDef(definedVar, term));
        }
        return mergeVarDefs(ImmutableList.copyOf(result));
    }

    private TranslationResult translate(Order order) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(order.getArg());
        RDF4JValueExprTranslator translator = getValueTranslator(child.iqTree.getVariables());

        ImmutableList<OrderByNode.OrderComparator> comparators = order.getElements().stream()
                .map(o -> Optional.of(translator.getTerm(o.getExpr()))
                        .filter(t -> t instanceof NonGroundTerm)
                        .map(t -> (NonGroundTerm)t)
                        .map(t -> iqFactory.createOrderComparator(t, o.isAscending())))
                .flatMap(Optional::stream)
                .collect(ImmutableCollectors.toList());

        return comparators.isEmpty()
                ? child
                : createTranslationResult(
                    iqFactory.createUnaryIQTree(iqFactory.createOrderByNode(comparators), child.iqTree),
                    child.nullableVariables);
    }


    private TranslationResult translate(BindingSetAssignment node) {

        ImmutableSet<Variable> allVars = node.getBindingNames().stream()
                .map(termFactory::getVariable)
                .collect(ImmutableCollectors.toSet());

        RDF4JValueTranslator translator = getValueTranslator();

        ImmutableList<ImmutableSubstitution<ImmutableTerm>> substitutions =
                StreamSupport.stream(node.getBindingSets().spliterator(), false)
                        .map(bs -> bs.getBindingNames().stream()
                                .collect(substitutionFactory.toSubstitution(
                                        termFactory::getVariable,
                                        x -> Optional.ofNullable(bs.getBinding(x))
                                                .map(Binding::getValue)
                                                .<ImmutableTerm>map(translator::getTermForLiteralOrIri)
                                                .orElseGet(termFactory::getNullConstant))))
                        .collect(ImmutableCollectors.toList());

        ImmutableSet<Variable> nullableVars = substitutions.get(0).getDomain().stream()
                .filter(v -> substitutions.stream().anyMatch(s -> s.get(v).isNull()))
                .collect(ImmutableCollectors.toSet());

        ImmutableList<IQTree> subtrees = substitutions.stream()
                .map(sub -> iqFactory.createConstructionNode(sub.getDomain(), sub))
                .map(cn -> iqFactory.createUnaryIQTree(cn, iqFactory.createTrueNode()))
                .collect(ImmutableCollectors.toList());

        IQTree tree = subtrees.size() == 1
                ? subtrees.get(0)
                : iqFactory.createNaryIQTree(iqFactory.createUnionNode(allVars), subtrees);

        IQTree iqTree = applyExternalBindingFilter(tree, allVars);
        return createTranslationResult(iqTree, nullableVars);
    }

    private TranslationResult translate(Reduced reduced) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(reduced.getArg());
        return createTranslationResult(
                iqFactory.createUnaryIQTree(iqFactory.createDistinctNode(), child.iqTree),
                child.nullableVariables);
    }

    private TranslationResult translate(Distinct distinct) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(distinct.getArg());
        return createTranslationResult(
                iqFactory.createUnaryIQTree(iqFactory.createDistinctNode(), child.iqTree),
                child.nullableVariables);
    }

    private TranslationResult translate(Slice slice) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(slice.getArg());

        // Assumption: at least the limit or the offset is not -1 (otherwise the rdf4j parser would not generate a slice node)
        long offset = slice.getOffset() == -1
                ? 0
                : slice.getOffset();

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        slice.getLimit() == -1
                            ? iqFactory.createSliceNode(offset)
                            : iqFactory.createSliceNode(offset, slice.getLimit()),
                        child.iqTree),
                child.nullableVariables);
    }

    private TranslationResult translate(Filter filter) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult child = translate(filter.getArg());
        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createFilterNode(getFilterExpression(filter.getCondition(), child.iqTree.getVariables())),
                        child.iqTree),
                child.nullableVariables);
    }

    private TranslationResult translateJoinLikeNode(BinaryTupleOperator join) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        if (!(join instanceof Join) && !(join instanceof LeftJoin)) {
            throw new Sparql2IqConversionException("A left or inner join is expected");
        }

        TranslationResult leftTranslation = translate(join.getLeftArg());
        TranslationResult rightTranslation = translate(join.getRightArg());

        Sets.SetView<Variable> nullableVarsUnion = Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables);

        Sets.SetView<Variable> sharedVars = getSharedVariables(leftTranslation, rightTranslation);

        ImmutableSet<Variable> toCoalesce = Sets.intersection(sharedVars, nullableVarsUnion).immutableCopy();

        VariableGenerator variableGenerator = getVariableGenerator(leftTranslation, rightTranslation);

        // May update the variable generator!!
        InjectiveVar2VarSubstitution leftRenamingSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        InjectiveVar2VarSubstitution rightRenamingSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        ImmutableSubstitution<ImmutableTerm> topSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toSubstitution(
                        v -> termFactory.getImmutableFunctionalTerm(
                                functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.COALESCE, 2),
                                leftRenamingSubstitution.get(v),
                                rightRenamingSubstitution.get(v))));

        Optional<ImmutableExpression> filterExpression;
        if (join instanceof LeftJoin) {
            ImmutableSet<Variable> variables = Sets.union(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()).immutableCopy();
            filterExpression = Optional.ofNullable(((LeftJoin) join).getCondition())
                    .map(c -> topSubstitution.apply(getFilterExpression(c, variables)));
        }
        else {
            filterExpression = Optional.empty();
        }

        Optional<ImmutableExpression> joinCondition = termFactory.getConjunction(filterExpression, toCoalesce.stream()
                .map(v -> generateCompatibleExpression(v, leftRenamingSubstitution, rightRenamingSubstitution)));

        JoinLikeNode joinLikeNode = join instanceof LeftJoin
                ? iqFactory.createLeftJoinNode(joinCondition)
                : iqFactory.createInnerJoinNode(joinCondition);

        Sets.SetView<Variable> newSetOfNullableVars = join instanceof LeftJoin
                ? Sets.union(nullableVarsUnion, Sets.difference(rightTranslation.iqTree.getVariables(), sharedVars))
                : Sets.difference(nullableVarsUnion, sharedVars);

        ImmutableSet<Variable> projectedVariables = Sets.union(
                Sets.difference(
                        Sets.union(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()),
                        toCoalesce),
                topSubstitution.getDomain()).immutableCopy();

        InjectiveVar2VarSubstitution leftNonProjVarsRenaming = getNonProjVarsRenaming(leftTranslation, rightTranslation, variableGenerator);
        InjectiveVar2VarSubstitution rightNonProjVarsRenaming = getNonProjVarsRenaming(rightTranslation, leftTranslation, variableGenerator);

        IQTree joinTree = getJoinTree(
                joinLikeNode,
                applyInDepthRenaming(
                        leftTranslation.iqTree.applyDescendingSubstitutionWithoutOptimizing(leftRenamingSubstitution, variableGenerator),
                        leftNonProjVarsRenaming),
                applyInDepthRenaming(
                        rightTranslation.iqTree.applyDescendingSubstitutionWithoutOptimizing(rightRenamingSubstitution, variableGenerator),
                        rightNonProjVarsRenaming));

        IQTree joinQuery = topSubstitution.isEmpty()
                ? joinTree
                : iqFactory.createUnaryIQTree(
                    iqFactory.createConstructionNode(projectedVariables, topSubstitution),
                    joinTree);

        return createTranslationResult(joinQuery, newSetOfNullableVars.immutableCopy());
    }

    private ImmutableExpression generateCompatibleExpression(Variable outputVariable,
                                                             InjectiveVar2VarSubstitution leftChildSubstitution,
                                                             InjectiveVar2VarSubstitution rightChildSubstitution) {

        Variable leftVariable = substitutionFactory.onVariables().apply(leftChildSubstitution, outputVariable);
        Variable rightVariable = substitutionFactory.onVariables().apply(rightChildSubstitution, outputVariable);

        ImmutableExpression equalityCondition = termFactory.getStrictEquality(leftVariable, rightVariable);
        ImmutableExpression isNullExpression = termFactory.getDisjunction(
                termFactory.getDBIsNull(leftVariable), termFactory.getDBIsNull(rightVariable));

        return termFactory.getDisjunction(equalityCondition, isNullExpression);
    }

    private IQTree getJoinTree(JoinLikeNode joinNode, IQTree leftTree, IQTree rightTree) {
        if (joinNode instanceof LeftJoinNode) {
            return iqFactory.createBinaryNonCommutativeIQTree((LeftJoinNode) joinNode, leftTree, rightTree);
        }
        if (joinNode instanceof InnerJoinNode) {
            return iqFactory.createNaryIQTree((InnerJoinNode) joinNode, ImmutableList.of(leftTree, rightTree));
        }
        throw new Sparql2IqConversionException("Left or inner join expected");
    }

    private TranslationResult translate(Projection projection) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(projection.getArg());

        ImmutableMap<Variable, Variable> map = projection.getProjectionElemList().getElements().stream()
                .collect(ImmutableCollectors.toMap(
                        pe -> termFactory.getVariable(pe.getName()),
                        pe -> termFactory.getVariable(pe.getProjectionAlias().orElse(pe.getName()))));

        ImmutableSubstitution<Variable> substitution = map.entrySet().stream()
                .collect(substitutionFactory.toSubstitutionSkippingIdentityEntries());

        ImmutableSet<Variable> projectedVars = ImmutableSet.copyOf(map.values());

        if (substitution.isEmpty() && projectedVars.equals(child.iqTree.getVariables())) {
            return child;
        }

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(child.iqTree.getKnownVariables(), projectedVars));

        IQTree subQuery = child.iqTree.applyDescendingSubstitutionWithoutOptimizing(substitution, variableGenerator);

        // Substitution for possibly unbound variables
        ImmutableSubstitution<ImmutableTerm> newSubstitution = Sets.difference(projectedVars, subQuery.getVariables()).stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant()));

        ConstructionNode projectNode = iqFactory.createConstructionNode(projectedVars, newSubstitution);
        UnaryIQTree constructTree = iqFactory.createUnaryIQTree(projectNode, subQuery);

        ImmutableSet<Variable> nullableVariables = substitutionFactory.onVariables().apply(substitution, child.nullableVariables);

        IQTree iqTree = applyExternalBindingFilter(constructTree, projectNode.getSubstitution().getDomain());
        return createTranslationResult(iqTree, nullableVariables);
    }

    private TranslationResult translate(Union union) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult leftTranslation = translate(union.getLeftArg());
        TranslationResult rightTranslation = translate(union.getRightArg());

        VariableGenerator variableGenerator = getVariableGenerator(leftTranslation, rightTranslation);

        ImmutableSet<Variable> leftVariables = leftTranslation.iqTree.getVariables();
        ImmutableSet<Variable> rightVariables = rightTranslation.iqTree.getVariables();

        Sets.SetView<Variable> nullOnLeft = Sets.difference(rightVariables, leftVariables);
        Sets.SetView<Variable> nullOnRight = Sets.difference(leftVariables, rightVariables);

        ImmutableSet<Variable> allNullable = Sets.union(
                Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables),
                Sets.union(nullOnLeft, nullOnRight)).immutableCopy();

        ImmutableSet<Variable> rootVariables = Sets.union(leftVariables, rightVariables).immutableCopy();

        ConstructionNode leftCn = iqFactory.createConstructionNode(rootVariables, nullOnLeft.stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant())));
        ConstructionNode rightCn = iqFactory.createConstructionNode(rootVariables, nullOnRight.stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant())));

        InjectiveVar2VarSubstitution leftNonProjVarsRenaming = getNonProjVarsRenaming(leftTranslation, rightTranslation, variableGenerator);
        InjectiveVar2VarSubstitution rightNonProjVarsRenaming = getNonProjVarsRenaming(rightTranslation, leftTranslation, variableGenerator);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(iqFactory.createConstructionNode(rootVariables),
                        iqFactory.createNaryIQTree(iqFactory.createUnionNode(rootVariables),
                                ImmutableList.of(
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(leftCn, leftTranslation.iqTree), leftNonProjVarsRenaming),
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(rightCn, rightTranslation.iqTree), rightNonProjVarsRenaming)))),
                allNullable);
    }

    private TranslationResult translate(StatementPattern pattern) {

        RDF4JValueExprTranslator translator = getValueTranslator(ImmutableSet.of());
        VariableOrGroundTerm subject = translator.translateRDF4JVar(pattern.getSubjectVar(), true);
        VariableOrGroundTerm predicate = translator.translateRDF4JVar(pattern.getPredicateVar(), true);
        VariableOrGroundTerm object = translator.translateRDF4JVar(pattern.getObjectVar(), true);

        IQTree subTree;
        if (pattern.getScope().equals(StatementPattern.Scope.NAMED_CONTEXTS))  {
            VariableOrGroundTerm graph = translator.translateRDF4JVar(pattern.getContextVar(), true);
            subTree = translateQuadPattern(subject, predicate, object, graph);
        }
        else  {
            subTree = translateTriplePattern(subject, predicate, object);
        }

        IQTree iqTree = applyExternalBindingFilter(subTree, subTree.getVariables());
        return createTranslationResult(iqTree, ImmutableSet.of());
    }

    private IQTree translateTriplePattern(VariableOrGroundTerm subject, VariableOrGroundTerm predicate, VariableOrGroundTerm object) {

        if (dataset == null || dataset.getDefaultGraphs().isEmpty() && dataset.getNamedGraphs().isEmpty()) {
            return iqFactory.createIntensionalDataNode(
                    atomFactory.getIntensionalTripleAtom(subject, predicate, object));
        }
        else {
            Set<IRI> defaultGraphs = dataset.getDefaultGraphs();
            int defaultGraphCount = defaultGraphs.size();

            // From SPARQL 1.1 "If there is no FROM clause, but there is one or more FROM NAMED clauses,
            // then the dataset includes an empty graph for the default graph."
            if (defaultGraphCount == 0) {
                return iqFactory.createEmptyNode(
                        Stream.of(subject, predicate, object)
                                .filter(t -> t instanceof Variable)
                                .map(t -> (Variable) t)
                                .collect(ImmutableCollectors.toSet()));
            }
            // NB: INSERT blocks cannot have more than 1 default graph. Important for the rest
            else if (defaultGraphCount == 1) {
                IRIConstant graph = termFactory.getConstantIRI(defaultGraphs.iterator().next().stringValue());
                return iqFactory.createIntensionalDataNode(
                        atomFactory.getIntensionalQuadAtom(subject, predicate, object, graph));
            }
            else {
                Variable graph = termFactory.getVariable("g" + UUID.randomUUID());

                IntensionalDataNode quadNode = iqFactory.createIntensionalDataNode(
                        atomFactory.getIntensionalQuadAtom(subject, predicate, object, graph));

                FilterNode filterNode = getGraphFilter(graph, defaultGraphs);

                ImmutableSet<Variable> projectedVariables = Sets.difference(quadNode.getVariables(), ImmutableSet.of(graph)).immutableCopy();

                // Merges the default trees -> removes duplicates
                return iqFactory.createUnaryIQTree(iqFactory.createDistinctNode(),
                        iqFactory.createUnaryIQTree(
                                iqFactory.createConstructionNode(projectedVariables),
                                iqFactory.createUnaryIQTree(filterNode, quadNode)));
            }
        }
    }

    private IQTree translateQuadPattern(VariableOrGroundTerm subject, VariableOrGroundTerm predicate, VariableOrGroundTerm object, VariableOrGroundTerm graph) {

        IntensionalDataNode dataNode = iqFactory.createIntensionalDataNode(
                atomFactory.getIntensionalQuadAtom(subject, predicate, object, graph));

        return (dataset == null || dataset.getNamedGraphs().isEmpty())
            ? dataNode
            : iqFactory.createUnaryIQTree(getGraphFilter(graph, dataset.getNamedGraphs()), dataNode);
    }

    private FilterNode getGraphFilter(VariableOrGroundTerm graph, Set<IRI> graphIris) {
        ImmutableExpression graphFilter = termFactory.getDisjunction(graphIris.stream()
                        .map(g -> termFactory.getConstantIRI(g.stringValue()))
                        .map(iriConstant -> termFactory.getStrictEquality(graph, iriConstant)))
                .orElseThrow(() -> new MinorOntopInternalBugException("The empty case already handled"));

        return iqFactory.createFilterNode(graphFilter);
    }

    private TranslationResult translate(Extension node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult childTranslation = translate(node.getArg());

        // Warning: an ExtensionElement might reference a variable appearing in a previous ExtensionElement
        // So we may need to nest them

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableSet<Variable> childVars = childTranslation.iqTree.getVariables();
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs = getVarDefs(node.getElements(), childVars);

        if (mergedVarDefs.isEmpty()) {
            return childTranslation;
        }

        TranslationResult result = createTranslationResult(childTranslation.iqTree, childTranslation.nullableVariables);

        for (ImmutableSubstitution<ImmutableTerm> substitution : mergedVarDefs) {

            ImmutableSet<Variable> nullableVariables = result.nullableVariables;
            ImmutableSet<Variable> newNullableVariables = substitution
                    .restrictRange(t -> t.getVariableStream().anyMatch(nullableVariables::contains))
                    .getDomain();

            ConstructionNode constructionNode = iqFactory.createConstructionNode(
                    Sets.union(result.iqTree.getVariables(), substitution.getDomain()).immutableCopy(),
                    substitution);

            UnaryIQTree tree = iqFactory.createUnaryIQTree(constructionNode, result.iqTree);

            IQTree iqTree = applyExternalBindingFilter(tree, constructionNode.getSubstitution().getDomain());
            result = createTranslationResult(iqTree, Sets.union(nullableVariables, newNullableVariables).immutableCopy());
        }

        return result;
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getVarDefs(List<ExtensionElem> list,
                                                                           ImmutableSet<Variable> childVars) {
        List<VarDef> result = new ArrayList<>();
        Set<Variable> allowedVars = new HashSet<>(childVars); // mutable: accumulator

        for (ExtensionElem elem : list) {
            if (!(elem.getExpr() instanceof Var && elem.getName().equals(((Var) elem.getExpr()).getName()))) {
                ImmutableTerm term = getValueTranslator(allowedVars).getTerm(elem.getExpr());
                Variable definedVar = termFactory.getVariable(elem.getName());
                allowedVars.add(definedVar);

                result.add(new VarDef(definedVar, term));
            }
        }

        ImmutableList<VarDef> varDefs = result.stream()
                .filter(vd -> !childVars.contains(vd.var))
                .collect(ImmutableCollectors.toList());

        return mergeVarDefs(varDefs);
    }

    /** Returns the injective substitution that renames the non-projected variables from the left
     * that are also present in the right operand
     */
    private InjectiveVar2VarSubstitution getNonProjVarsRenaming(TranslationResult left, TranslationResult right,
                                                                VariableGenerator variableGenerator) {
        return Sets.intersection(
                        Sets.difference(left.iqTree.getKnownVariables(), left.iqTree.getVariables()),
                        right.iqTree.getKnownVariables()).stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergeVarDefs(ImmutableList<VarDef> varDefs)  {
        Deque<Map<Variable, ImmutableTerm>> substitutionMapList = new LinkedList<>();
        substitutionMapList.add(new HashMap<>());

        for (VarDef varDef : varDefs) {
            Map<Variable, ImmutableTerm> last = substitutionMapList.getLast();
            if (varDef.term.getVariableStream().anyMatch(last::containsKey)) { // start off a new substitution
                substitutionMapList.addLast(new HashMap<>());
            }
            substitutionMapList.getLast().put(varDef.var, varDef.term);
        }

        return substitutionMapList.stream()
                .map(m -> m.entrySet().stream().collect(substitutionFactory.toSubstitution()))
                .collect(ImmutableCollectors.toList());
    }

    /**
     * @param expr                 expression
     * @param childVariables       the set of variables that can occur in the expression
     */

    private ImmutableExpression getFilterExpression(ValueExpr expr, ImmutableSet<Variable> childVariables) {

        ImmutableTerm term = getValueTranslator(childVariables).getTerm(expr);

        ImmutableTerm xsdBooleanTerm = term.inferType()
                .flatMap(TermTypeInference::getTermType)
                .filter(t -> t instanceof RDFDatatype)
                .map(t -> (RDFDatatype)t)
                .filter(t -> t.isA(XSD.BOOLEAN))
                .isPresent()
                    ? term
                    : termFactory.getSPARQLEffectiveBooleanValue(term);

        return termFactory.getRDF2DBBooleanFunctionalTerm(xsdBooleanTerm);
    }


    private TranslationResult createTranslationResult(IQTree iqTree, ImmutableSet<Variable> nullableVariables)  {
        return new TranslationResult(iqTree, nullableVariables);
    }

    private IQTree applyExternalBindingFilter(IQTree tree, ImmutableSet<Variable> variables) {
        // Most of the time
        if (externalBindings.isEmpty())
            return tree;

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(variables, externalBindings.keySet());

        Optional<ImmutableExpression> conjunction = termFactory.getConjunction(
                externallyBoundedVariables.stream()
                        .map(v -> termFactory.getStrictEquality(v, externalBindings.get(v))));

        // Filter variables according to bindings
        return conjunction
                .map(iqFactory::createFilterNode)
                .<IQTree>map(f -> iqFactory.createUnaryIQTree(f, tree))
                .orElse(tree);
    }


    private static class TranslationResult {
        private final IQTree iqTree;
        private final ImmutableSet<Variable> nullableVariables;

        /**
         * Do not call it directly, use createTranslationResult instead
         */
        private TranslationResult(IQTree iqTree, ImmutableSet<Variable> nullableVariables) {
            this.nullableVariables = nullableVariables;
            this.iqTree = iqTree;
        }
    }

    private static class Sparql2IqConversionException extends OntopInternalBugException {

        Sparql2IqConversionException(String s) {
            super(s);
        }
    }

    private static class VarDef {
        private final Variable var;
        private final ImmutableTerm term;

        private VarDef(Variable var, ImmutableTerm term) {
            this.var = var;
            this.term = term;
        }
    }

    private RDF4JValueTranslator getValueTranslator() {
        return new RDF4JValueTranslator(termFactory, rdfFactory, typeFactory);
    }

    private RDF4JValueExprTranslator getValueTranslator(Set<Variable> knownVariables) {
        return new RDF4JValueExprTranslator(knownVariables, externalBindings, treatBNodeAsVariable, termFactory, rdfFactory, typeFactory, functionSymbolFactory);
    }
}
