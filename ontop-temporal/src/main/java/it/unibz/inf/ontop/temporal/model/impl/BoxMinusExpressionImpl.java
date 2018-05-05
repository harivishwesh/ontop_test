package it.unibz.inf.ontop.temporal.model.impl;

import it.unibz.inf.ontop.temporal.model.*;

public class BoxMinusExpressionImpl extends AbstractUnaryTemporalExpressionWithRange implements BoxMinusExpression {
    BoxMinusExpressionImpl(TemporalRange range, DatalogMTLExpression operand) {
        super(range, operand);
    }

    @Override
    public String render() {
        return String.format("ALWAYS IN PAST %s {%s}", getRange(), getOperand().render());
//        if (getOperand() instanceof BinaryTemporalExpression || getOperand() instanceof TemporalJoinExpression)
//            return String.format("ALWAYS IN PAST %s {%s}", getRange(), getOperand().render());
//
//        return String.format("ALWAYS IN PAST %s %s", getRange(), getOperand().render());
    }

    @Override
    public String toString() {
        return String.format("BoxMinusExpressionImpl{range = %s, operand = %s}", getRange(), getOperand());
    }
}