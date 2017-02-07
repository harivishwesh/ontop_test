package it.unibz.inf.ontop.executor;


import it.unibz.inf.ontop.pivotalrepr.QueryNode;
import it.unibz.inf.ontop.pivotalrepr.proposal.NodeCentricOptimizationResults;
import it.unibz.inf.ontop.pivotalrepr.proposal.SimpleNodeCentricOptimizationProposal;

public interface SimpleNodeCentricExecutor<N extends QueryNode, P extends SimpleNodeCentricOptimizationProposal<N>>
        extends NodeCentricExecutor<N, NodeCentricOptimizationResults<N>, P> {
}