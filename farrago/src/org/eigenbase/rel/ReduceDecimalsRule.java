/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005-2005 The Eigenbase Project
// Copyright (C) 2002-2005 Disruptive Tech
// Copyright (C) 2005-2005 LucidEra, Inc.
// Portions Copyright (C) 2003-2005 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.eigenbase.rel;

import java.math.*;
import java.util.ArrayList;
import java.util.List;

import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.fun.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.util.*;

/**
 * ReduceDecimalsRule reduces decimal operations (such as casts or 
 * arithmetic) into operations involving primitive types (such as 
 * longs and floats).
 * 
 * <p>The rule works by finding the {@link RexNode} expressions of a 
 * {@link RelNode} relation. If the expressions contains decimal 
 * operations, they are replaced with reduced expressions and a 
 * new relation is returned. 
 *
 * <p>TODO: might be more optimal to simplify null values, spurious casts
 *
 * @author jpham
 * @version $Id$
 */
public class ReduceDecimalsRule extends RelOptRule
{
    //~ Instance fields -------------------------------------------------------

    private final Class relClass;
    
    //~ Constructors ----------------------------------------------------------

    /**
     * Constructs the rule.
     *
     * @param relClass the RelNode class which has expressions to be reduced
     */
    public ReduceDecimalsRule(
        Class relClass)
    {
        super(new RelOptRuleOperand(relClass, null));
        this.relClass = relClass;
        description = "ReduceDecimalsRule:" + relClass.getName();
    }
    
    //~ Methods ---------------------------------------------------------------

    // implement RelOptRule
    public CallingConvention getOutConvention()
    {
        return CallingConvention.NONE;
    }

    // implement RelOptRule
    public void onMatch(RelOptRuleCall call)
    {
        RelNode rel = call.rels[0];
        if (rel.getClass() != relClass) {
            // require exact match on type
            return;
        }

        if (rel instanceof CalcRel) {
            CalcRel calcRel = (CalcRel) rel;
            final RexProgram program = calcRel.getProgram();
            if (!RexUtil.requiresDecimalExpansion(program, true)) {
                return;
            }
            // Expand decimals in every expression in this program. If no
            // expression changes, don't apply the rule.

            // TODO: Move this logic into RexProgramBuilder, as a method
            // which applies a visitor to every expression in a program. That
            // method will eliminate common sub-expressions, and be able to
            // handle more complex expressions.
            RexBuilder rexBuilder = rel.getCluster().getRexBuilder();
            List<RexNode> newExprList = new ArrayList<RexNode>();
            boolean reduced = false;
            for (RexNode expr : program.getExprList()) {
                RexNode newExpr = reduceDecimals(expr, rexBuilder);
                if (expr != newExpr) {
                    reduced = true;
                }
                newExprList.add(newExpr);
            }
            if (! reduced) {
                assert false : "requiresDecimalExpansion lied";
                return;
            }
            final RexProgram newProgram =
                new RexProgram(
                    program.getInputRowType(),
                    newExprList,
                    new ArrayList<RexLocalRef>(program.getProjectList()),
                    program.getCondition(),
                    program.getOutputRowType());

            CalcRel newCalcRel = new CalcRel(
                calcRel.getCluster(),
                calcRel.getTraits(),
                calcRel.getChild(),
                newProgram.getOutputRowType(),
                newProgram);
            call.transformTo(newCalcRel);
        }
    }
    
    RexNode[] getProjections(RexNode[] childExps, boolean hasCondition)
    {
        Util.pre(childExps.length>0, "childExps.length>0");
        if (hasCondition) {
            RexNode[] projections = new RexNode[childExps.length-1];
            for (int i=0; i < projections.length; i++) {
                projections[i] = childExps[i];
            }
            return projections;
        }
        return childExps;
    }

    RexNode getCondition(RexNode[] childExps, boolean hasCondition)
    {
       if (hasCondition) {
           Util.pre(childExps.length>1, "childExps.length>1");
           return childExps[childExps.length-1];
       }
       return null;
    }

    /**
     * Reduces an expression. The expression may be nested. This method 
     * performs multiple passes so the result of one pass can be further 
     * expanded.
     * 
     * @param expr expression to be reduced
     * @param rexBuilder builder for building reduced expression tree
     * @return the reduced expression, or the orignal if it was not reduced
     */
    public RexNode reduceDecimals(RexNode expr, RexBuilder rexBuilder) 
    {
        RexNode oldExpr;
        RexNode newExpr = expr;
        do {
            oldExpr = newExpr;
            newExpr = reduceTree(oldExpr, rexBuilder); 
        } 
        while (oldExpr != newExpr);
        return newExpr;
    }
    
    /**
     * Reduces a tree of expressions
     * 
     * @param expr the expression tree
     * @param rexBuilder builder for building reduced expression tree 
     * @return the reduced expression, or the orignal if it was not reduced 
     */
    private RexNode reduceTree(RexNode expr, RexBuilder rexBuilder) 
    {
        if (expr instanceof RexCall) {
            RexCall call = (RexCall) expr;
            RexNode[] newOperands = new RexNode[call.operands.length];
            boolean operandsReduced = false;
            for (int i = 0; i < call.operands.length; i++) {
                newOperands[i] = reduceTree(call.operands[i], rexBuilder);
                if (newOperands[i] != call.operands[i]) {
                    operandsReduced = true;
                }
            }
            if (operandsReduced) {
                return reduceNode(call, newOperands, rexBuilder);
            } else {
                return reduceNode(call, call.operands, rexBuilder);
            }
        }
        return expr;
    }
    
    /**
     * Reduces a single expression node. As usual, this method returns 
     * the original expression if it was not reduced. However, it always 
     * returns a new expression if its operands were reduced.
     * 
     * @param call expression node to be reduced
     * @param reducedOperands operands, in reduced form
     * @param rexBuilder
     * @return the reduced expressed
     */
    private RexNode reduceNode(
        RexCall call, RexNode[] reducedOperands, RexBuilder rexBuilder)
    {
        RexExpander expander = getExpander(call, rexBuilder);
        if (expander.canExpand(call)) {
            return expander.expand(call, reducedOperands);
        }
        if (reducedOperands != call.operands) {
            return RexRebuilder.rebuildCall(
                call, reducedOperands, rexBuilder);
        }
        return call;
    }

    /**
     * FIXME: table oriented dispatch would be more flexible 
     */ 
    private RexExpander getExpander(RexCall call, RexBuilder rexBuilder)
    {
        // NOTE: any function which returns a decimal requires special 
        // handling
        if (call.isA(RexKind.Cast)) {
            return new CastExpander(rexBuilder);
        } else if (call.isA(RexKind.MinusPrefix)) {
            return new PassThroughExpander(rexBuilder);
        } else if (call.isA(RexKind.Arithmetic)) {
            // NOTE: MinusPrefix is also Arithmetic so it is tested first
            return new BinaryArithmeticExpander(rexBuilder);
        } else if (call.isA(RexKind.Comparison)) {
            return new BinaryArithmeticExpander(rexBuilder);
        } else if (call.isA(RexKind.Reinterpret)) {
            return new ReinterpretExpander(rexBuilder);
        } else if (call.isA(RexKind.Other)
            && (call.getOperator() == SqlStdOperatorTable.absFunc))
        {
            return new PassThroughExpander(rexBuilder);
        } else {
            return new CastAsDoubleExpander(rexBuilder);
        }
    }

    /**
     * Expands a decimal expression
     */
    abstract class RexExpander
    {
        //~ Instance fields -------------------------------------------------

        /** Factory for constructing RexNode */
        RexBuilder builder;
        /** 
         * Type for the internal representation of decimals. When using 
         * this type, be careful to set its nullability.
         */
        RelDataType int8;
        /** 
         * Type for doubles. When using this type, be careful to set 
         * its nullability 
         */
        RelDataType real8;

        //~ Public methods --------------------------------------------------
        
        /** Constructs a RexExpander */
        public RexExpander(RexBuilder builder)
        {
            this.builder = builder;
            int8=builder.getTypeFactory().createSqlType(SqlTypeName.Bigint);
            real8=builder.getTypeFactory().createSqlType(SqlTypeName.Double);
        }

        /** Whether or not the expression can be expanded */
        public abstract boolean canExpand(RexCall call);
        
        /** Returns expanded expression */
        public abstract RexNode expand(RexCall call, RexNode[] operands);

        //~ Protected methods -----------------------------------------------
        
        /** 
         * Makes a RexLiteral representing 10^scale. If <code>scale</code>
         * is non-negative, then the literal returned will be of Bigint type.
         * If <code>scale</code> is negative. The literal returned will be of 
         * an approximate type.
         */
        protected RexNode makeScaleFactor(int scale)
        {
            if (scale >= 0) {
                BigDecimal bd = new BigDecimal(Util.powerOfTen(scale));
                return builder.makeExactLiteral(bd, int8);
            } else {
                BigDecimal bd = new BigDecimal(BigInteger.valueOf(1), scale);
                return builder.makeApproxLiteral(bd);
            }
        }

        /**
         * Makes an exact numeric RexLiteral representing 10^scale/2
         * 
         * @param scale scale of round factor; must be positive
         */
        protected RexNode makeRoundFactor(int scale)
        {
            Util.pre(scale > 0, "scale > 0");
            BigDecimal bd = new BigDecimal(Util.powerOfTen(scale)/2);
            return builder.makeExactLiteral(bd, int8);
        }
        
        /** 
         * Scales up a decimal value. The <code>scale</code> parameter 
         * must be non-negative. If <code>scale</code> is positive, this 
         * method multiplies <code>value</code> by a scale factor. If 
         * scale is zero, the original value is returned.
         */
        protected RexNode scaleUp(RexNode value, int scale)
        {
            Util.pre(scale>=0, "ReduceDecimalsRule:scale>=0");
            if (scale == 0) {
                return value;
            }
            return builder.makeCall(
                SqlStdOperatorTable.multiplyOperator,
                value,
                makeScaleFactor(scale));
        }
        
        /** 
         * Scales down a decimal value. The <code>scale</code> parameter 
         * must be non-negative. If <code>scale</code> is positive, this 
         * method divides <code>value</code> by a scale factor. If 
         * scale is zero, the original value will be returned. A round 
         * factor is added to round away from zero. The division 
         * will be implemented as an integer division and the type of 
         * value returned will be an integer type.
         */
        protected RexNode scaleDown(RexNode value, int scale)
        {
            Util.pre(scale>=0, "ReduceDecimalsRule:scale>=0");
            if (scale == 0) {
                return value;
            }
            
            RexNode[] caseOperands = new RexNode[3];
            caseOperands[0] = builder.makeCall(
                SqlStdOperatorTable.greaterThanOperator,
                value,
                builder.makeExactLiteral(BigDecimal.ZERO));
            RexNode roundFactor = makeRoundFactor(scale);
            caseOperands[1] = builder.makeCall(
                SqlStdOperatorTable.plusOperator,
                value,
                roundFactor);
            caseOperands[2] = builder.makeCall(
                SqlStdOperatorTable.minusOperator,
                value,
                roundFactor);
            RexNode roundValue = builder.makeCall(
                SqlStdOperatorTable.caseOperator,
                caseOperands);
            
            return builder.makeCall(
                SqlStdOperatorTable.divideOperator,
                roundValue,
                makeScaleFactor(scale));
        }
        
        /** 
         * Scales down a decimal value. The <code>scale</code> parameter 
         * must be non-negative. If <code>scale</code> is positive, this 
         * method divides <code>value</code> by a scale factor. If 
         * scale is zero, the original value will be returned. The division 
         * will be implemented as a floating point division and the type of 
         * value returned will be a floating point value.
         */
        protected RexNode scaleDownDouble(RexNode value, int scale)
        {
            Util.pre(scale>=0, "ReduceDecimalsRule:scale>=0");
            RexNode cast = ensureType(real8, value);
            if (scale == 0) {
                return cast;
            }
            // TODO: appropriate rounding behavior
            return builder.makeCall(
                SqlStdOperatorTable.divideOperator,
                cast,
                makeScaleFactor(scale));
        }

        /**
         * Ensures decimal represented by <code>node</code> is of sufficient 
         * scale. If the decimal is not, then it is scaled up. Otherwise the 
         * original node is returned.
         * 
         * @param value integer representation of decimal
         * @param scale current scale
         * @param required scale required; required >= scale
         * @return node or scaled up version of the node
         */
        protected RexNode ensureScale(RexNode value, int scale, int required)
        {
            Util.pre(required>=scale, "ReduceDecimalsRule:required>=scale");
            if (scale == required) {
                return value;
            } else {
                return scaleUp(value, required-scale);
            }
        }
        
        /** Retrieves a decimal node's integer representation */
        protected RexNode decodeValue(RexNode decimalNode) 
        {
            assert(SqlTypeUtil.isDecimal(decimalNode.getType()));
            return builder.makeReinterpretCast(
                matchNullability(int8, decimalNode),
                decimalNode,
                builder.makeLiteral(false));
        }
        
        /** 
         * Casts a decimal's integer representation to a decimal node. 
         * If the expression is not the expected integer type, then 
         * it is casted first.
         * 
         * <p>By default, this method corrects the nullability of the 
         * specified type to match the nullability of the expression.
         * It does not perform an overflow check.
         * 
         * @param value integer representation of decimal
         * @param decimalType type integer will be reinterpreted as
         * 
         * @return the integer representation reinterpreted as a decimal type
         */
        protected RexNode encodeValue(RexNode value, RelDataType decimalType)
        {
            return encodeValue(value, decimalType, true, false);
        }

        /** 
         * Casts a decimal's integer representation to a decimal node. 
         * If the expression is not the expected integer type, then 
         * it is casted first.
         * 
         * <p>The more versatile version of <code>encodeValue</code>
         * is useful for explicit casts which need strict handling 
         * of cast types and overflow checks.
         * 
         * The <code>checkOverflow</code> flag 
         * 
         * @param value integer representation of decimal
         * @param decimalType type integer will be reinterpreted as
         * @param matchNullability indicates whether the decimal type's 
         *            nullability should be corrected to match the 
         *            expression's nullability. Usually true, except for 
         *            explicit casts.
         * @param checkOverflow indicates whether an overflow check is 
         *            required when reinterpreting this particular value  
         *            as the decimal type. This is usually not required 
         *            for arithmetic, but often required for explicit 
         *            casts.
         * 
         * @return the integer representation reinterpreted as a decimal type
         */
        protected RexNode encodeValue(
            RexNode value, 
            RelDataType decimalType, 
            boolean matchNullability,
            boolean checkOverflow)
        {
            RexNode cast = ensureType(int8, value);
            RelDataType targetType = decimalType;
            if (matchNullability) {
                targetType = matchNullability(decimalType, cast);
            }
            return builder.makeReinterpretCast(
                targetType, cast, builder.makeLiteral(checkOverflow));
        }
        
        /**
         * Retrieves the primitive value of a numeric node. If the node 
         * is a decimal, then it must first be decoded. Otherwise the 
         * original node may be returned.
         * 
         * @param node a numeric node, possibly a decimal
         * @return the primitive value of the numeric node
         */
        protected RexNode accessValue(RexNode node)
        {
            if (SqlTypeUtil.isIntType(node.getType())
                || SqlTypeUtil.isApproximateNumeric(node.getType())) 
            {
                return node;
            } else {
                assert(SqlTypeUtil.isDecimal(node.getType()));
                return decodeValue(node);
            }
        }
        
        /**
         * Ensures expression is interpreted as a specified type. 
         * The returned expression may be wrapped with a cast. 
         * 
         * <p>By default, this method corrects the nullability of the 
         * specified type to match the nullability of the expression.
         * 
         * @param type desired type
         * @param node expression
         * 
         * @return a casted expression or the original expression
         */
        protected RexNode ensureType(RelDataType type, RexNode node)
        {
            return ensureType(type, node, true);
        }

        /**
         * Ensures expression is interpreted as a specified type. 
         * The returned expression may be wrapped with a cast. 
         * 
         * @param type desired type
         * @param node expression
         * @param matchNullability whether to correct nullability of 
         *            specified type to match the expression; this 
         *            usually should be true, except for explicit  
         *            casts which may override default nullability
         * 
         * @return a casted expression or the original expression
         */
        protected RexNode ensureType(
            RelDataType type, 
            RexNode node, 
            boolean matchNullability)
        {
            RelDataType targetType = type;
            if (matchNullability) {
                targetType = matchNullability(type, node);
            }
            if (node.getType() != targetType) {
                return builder.makeCast(targetType, node);
            }
            return node;
        }

        /** Ensure's type's nullability matches value's nullability */
        protected RelDataType matchNullability(
            RelDataType type, RexNode value)
        {
            boolean typeNullability = type.isNullable();
            boolean valueNullability = value.getType().isNullable();
            if (typeNullability != valueNullability) {
                return builder.getTypeFactory().createTypeWithNullability(
                    type, valueNullability);
            }
            return type;
        }
    }
    
    /**
     * Expands a decimal cast expression
     * 
     * <p>TODO: appropriate rounding, check for overflow
     */
    class CastExpander extends RexExpander
    {
        /** Constructs a CastExpander */
        public CastExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        // implement RexExpander 
        public boolean canExpand(RexCall call)
        {
            return call.isA(RexKind.Cast)
                && RexUtil.requiresDecimalExpansion(call, false);
        }
        
        // implement RexExpander
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            Util.pre(call.isA(RexKind.Cast), "call.isA(RexKind.Cast)");
            Util.pre(operands.length == 1, "operands.length == 1");
            if (RexLiteral.isNullLiteral(operands[0])) {
                return RexRebuilder.rebuildCall(call, operands, builder);
            }
            RexNode operand = RexUtil.clone(operands[0]);
            RelDataType fromType = operand.getType();
            RelDataType toType = call.getType();
            if (!SqlTypeUtil.isDecimal(fromType) 
                && !SqlTypeUtil.isDecimal(toType)) 
            {
                return RexRebuilder.rebuildCall(call, operands, builder);
            }

            if (SqlTypeUtil.isIntType(toType)) {
                return ensureType(
                    toType,
                    scaleDown(decodeValue(operand), fromType.getScale()),
                    false);
            } else if (SqlTypeUtil.isApproximateNumeric(toType)) {
                return ensureType(
                    toType,
                    scaleDownDouble(
                        decodeValue(operand), fromType.getScale()),
                    false);
            } else if (SqlTypeUtil.isApproximateNumeric(fromType)) {
                return encodeValue(
                    scaleUp(operand, toType.getScale()),
                    toType,
                    false, 
                    true);
            }
            
            int fromScale = fromType.getScale();
            int toScale = toType.getScale();
            int fromDigits = fromType.getPrecision() - fromScale;
            int toDigits = toType.getPrecision() - toScale;
            // NOTE: precision 19 overflows when its underlying 
            // bigint representation overflows
            boolean checkOverflow =
                toType.getPrecision() < 19 && toDigits < fromDigits;
            
            if (SqlTypeUtil.isIntType(fromType)) {
                return encodeValue(
                    scaleUp(operand, toType.getScale()),
                    toType,
                    false, 
                    checkOverflow);
            } else if (SqlTypeUtil.isDecimal(fromType) 
                && SqlTypeUtil.isDecimal(toType))
            {
                RexNode value = decodeValue(operand);
                RexNode scaled = null;
                if (fromScale == toScale) {
                    scaled = value;
                } else if (fromScale < toScale) {
                    scaled = scaleUp(value, toScale-fromScale);
                } else {
                    scaled = scaleDown(value, fromScale-toScale);
                }
                return encodeValue(scaled, toType, false, checkOverflow);
            } else {
                throw Util.needToImplement(
                    "Reduce decimal cast from "+fromType + " to "+toType);
            }
        }
    }
    
    /**
     * Expands a decimal arithmetic expression
     */
    class BinaryArithmeticExpander extends RexExpander
    {
        RelDataType typeA, typeB;
        int scaleA, scaleB;
        
        /** Constructs an ArithmeticExpander */
        public BinaryArithmeticExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        // implement RexExpander
        public boolean canExpand(RexCall call)
        {
            if (!(call.isA(RexKind.Arithmetic)||call.isA(RexKind.Comparison))
                || call.isA(RexKind.MinusPrefix)) 
            {
                return false;
            }
            return RexUtil.requiresDecimalExpansion(call, false);
        }
        
        // implement RexExpander
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            Util.pre(
                (call.isA(RexKind.Arithmetic)||call.isA(RexKind.Comparison)),
                "call.isA(RexKind.Arithmetic or RexKind.Comparison)");
            Util.pre(operands.length == 2, "operands.length == 2");
            RelDataType typeA = operands[0].getType();
            RelDataType typeB = operands[1].getType();
            assert(SqlTypeUtil.isNumeric(typeA) 
                && SqlTypeUtil.isNumeric(typeB));
            assert(SqlTypeUtil.isDecimal(typeA) 
                || SqlTypeUtil.isDecimal(typeB));

            if (SqlTypeUtil.isApproximateNumeric(typeA) 
                || SqlTypeUtil.isApproximateNumeric(typeB)) 
            {
                int castIndex = 
                    SqlTypeUtil.isApproximateNumeric(typeA) ? 1 : 0;
                int otherIndex = (castIndex==0) ? 1 : 0;
                RexNode[] newOperands = new RexNode[2];
                newOperands[castIndex] = 
                    ensureType(real8, operands[castIndex]);
                newOperands[otherIndex] = operands[otherIndex];
                return builder.makeCall(call.getOperator(), newOperands);
            }

            analyzeOperands(operands);
            if (call.isA(RexKind.Plus)) {
                return expandPlusMinus(call, operands);
            } else if (call.isA(RexKind.Minus)) {
                return expandPlusMinus(call, operands);
            } else if (call.isA(RexKind.Divide)) {
                return expandDivide(call, operands);
            } else if (call.isA(RexKind.Times)) {
                return expandTimes(call, operands);
            } else if (call.isA(RexKind.Comparison)) {
                return expandComparison(call, operands);
            } else {
                throw Util.newInternal(
                    "ReduceDecimalsRule could not expand "
                    + call.getOperator());
            }
        }

        /**
         * Convenience method for reading characteristics of operands (such 
         * as scale, precision, whole digits) into an ArithmeticExpander. 
         * The operands are restricted by the following contraints:
         * 
         * <ul>
         *   <li>there are exactly two operands
         *   <li>both are exact numeric types
         *   <li>at least the operands is a decimal
         * </ul>
         */
        private void analyzeOperands(RexNode[] operands)
        {
            assert(operands.length == 2);
            typeA = operands[0].getType();
            typeB = operands[1].getType();
            assert(SqlTypeUtil.isExactNumeric(typeA)
                && SqlTypeUtil.isExactNumeric(typeB));
            assert(SqlTypeUtil.isDecimal(typeA)
                || SqlTypeUtil.isDecimal(typeB));

            scaleA = typeA.getScale();
            scaleB = typeB.getScale();
        }
        
        private RexNode expandPlusMinus(RexCall call, RexNode[] operands)
        {
            RelDataType outType = call.getType();
            int outScale = outType.getScale();
            return encodeValue(
                builder.makeCall(
                    call.getOperator(), 
                    ensureScale(accessValue(operands[0]),scaleA,outScale),
                    ensureScale(accessValue(operands[1]),scaleB,outScale)),
                outType);
        }

        private RexNode expandDivide(RexCall call, RexNode[] operands)
        {
            RelDataType outType = call.getType();
            RexNode dividend = 
                builder.makeCall(call.getOperator(),
                    ensureType(real8, accessValue(operands[0])),
                    ensureType(real8, accessValue(operands[1])));
            RexNode rescale = 
                builder.makeCall(
                    SqlStdOperatorTable.multiplyOperator,
                    dividend, 
                    makeScaleFactor(outType.getScale() - scaleA + scaleB));
            return encodeValue(rescale, outType);
        }

        private RexNode expandTimes(RexCall call, RexNode[] operands)
        {
            return encodeValue(
                builder.makeCall(
                    call.getOperator(),
                    accessValue(operands[0]),
                    accessValue(operands[1])),
                call.getType());
        }
        
        private RexNode expandComparison(RexCall call, RexNode[] operands)
        {
            int commonScale = Math.max(scaleA, scaleB);
            return builder.makeCall(
                call.getOperator(),
                ensureScale(accessValue(operands[0]), scaleA, commonScale),
                ensureScale(accessValue(operands[1]), scaleB, commonScale));
        }
    }
    
    /** 
     * Expands miscellaneous functions which are not handled by other 
     * expanders yet require special handling (i.e. pass through and 
     * casting as a double don't work).
     * 
     * <p>NOTE: currently not used (hasn't been needed yet)
     */
    class FunctionExpander extends RexExpander
    {
        public FunctionExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        // implement RexExpander
        public boolean canExpand(RexCall call)
        {
            return (call.getOperator() == SqlStdOperatorTable.absFunc);
        }
        
        // implement RexExpander
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            if (call.getOperator() == SqlStdOperatorTable.absFunc) {
                assert (call.operands.length == 1);
                return encodeValue(
                    builder.makeCall(
                        SqlStdOperatorTable.absFunc,
                        decodeValue(operands[0])),
                    operands[0].getType());
            }
            return RexRebuilder.rebuildCall(call, operands, builder);
        }
    }
    
    /**
     * An expander that substitutes decimals with their integer 
     * representations. The output is reinterpreted as the input type.
     */
    class PassThroughExpander extends RexExpander
    {
        public PassThroughExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        public boolean canExpand(RexCall call)
        {
            return RexUtil.requiresDecimalExpansion(call, false);
        }
        
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            RexNode[] newOperands = new RexNode[operands.length];
            for (int i=0; i < operands.length; i++) {
                newOperands[i] = accessValue(operands[i]);
            }
            return encodeValue(
                builder.makeCall(call.getOperator(), newOperands),
                call.getType());
        }
    }
    
    /**
     * An expander which casts decimal arguments as doubles
     */
    class CastAsDoubleExpander extends RexExpander
    {
        public CastAsDoubleExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        public boolean canExpand(RexCall call)
        {
            return RexUtil.requiresDecimalExpansion(call, false);
        }
        
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            RexNode[] newOperands = new RexNode[operands.length];
            for (int i=0; i < operands.length; i++) {
                if (SqlTypeUtil.isDecimal(operands[i].getType())) {
                    newOperands[i] = ensureType(real8, operands[i]);
                } else {
                    newOperands[i] = operands[i];
                }
            }
            return builder.makeCall(call.getOperator(), newOperands);
        }
    }
    
    /**
     * An expander that simplifies multiple reinterpret calls. There are 
     * two cases of simplification we would like to consider.
     * 
     * <p>In the first case, say (1.0+1)*1, we are doing nested arithmetic.
     * The inner operation returns a decimal (Reinterpret(...)) which the 
     * outer operation immediately reuses: (Reinterpret(Reinterpret(...))).
     * Arithmetic overflow is handled by underlying integer operations, so 
     * we don't have to consider it. Simply remove the nested Reinterpret.
     * Note that nullability propagates too.
     * 
     * <p>In the second case, say cast (1.4 as decimal(3,1)), we are 
     * casting decimals of the same scale. If the outer precision is 
     * greater, we are allowed to remove the Reinterpret cast, provided 
     * we can change the type of the inner expression. If the outer 
     * precision is lesser, we would at least want to do an overflow 
     * check before changing the type. In either case, we don't really 
     * have a good way to change the output type so there is little we 
     * can do.
     */
    class ReinterpretExpander extends RexExpander
    {
        public ReinterpretExpander(RexBuilder builder)
        {
            super(builder);
        }
        
        public boolean canExpand(RexCall call)
        {
            return call.isA(RexKind.Reinterpret);
        }
        
        public RexNode expand(RexCall call, RexNode[] operands)
        {
            if (call.isA(RexKind.Reinterpret)) {
                assert(operands.length == 2);
                if (operands[0] instanceof RexCall) {
                    RexCall subCall = (RexCall) operands[0];
                    if (subCall.isA(RexKind.Reinterpret)) {
                        assert(subCall.operands.length == 2);
                        RexNode innerValue = subCall.operands[0];
                        if (canSimplify(call, subCall, innerValue)) {
                            return RexUtil.clone(innerValue);
                        }
                    }
                }
            }
            return RexRebuilder.rebuildCall(call, operands, builder);
        }
        
        /**
         * Detect, in a generic, but strict way, whether it is possible to 
         * simplify a reinterpret cast. The rules are as follows:
         * 
         * <ol>
         *   <li>If value is not the same basic type as outer, then we 
         *     cannot simplify
         *   <li>If the value is nullable but the inner or outer are not, then 
         *     we cannot simplify.
         *   <li>If inner is nullable but outer is not, we cannot simplify.
         *   <li>If an overflow check is required from either inner or 
         *     outer, we cannot simplify.
         *   <li>Otherwise, given the same type, and sufficient nullability 
         *     constraints, we can simplify.
         * </ol>
         * 
         * @param outer outer call to reinterpret
         * @param inner inner call to reinterpret
         * @param value inner value
         * @return whether the two reinterpret casts can be removed
         */
        private boolean canSimplify(
            RexCall outer, RexCall inner, RexNode value) 
        {
            RelDataType outerType = outer.getType();
            RelDataType innerType = inner.getType();
            RelDataType valueType = value.getType();
            boolean outerCheck = outer.operands[1].isAlwaysTrue();
            boolean innerCheck = inner.operands[1].isAlwaysTrue();
            
            if (outerType.getSqlTypeName() != valueType.getSqlTypeName()
                || outerType.getPrecision() != valueType.getPrecision()
                || outerType.getScale() != valueType.getScale()) 
            {
                return false;
            }
            if (valueType.isNullable() && 
                (!innerType.isNullable() || !outerType.isNullable())) 
            {
                return false;
            }
            if (innerType.isNullable() && !outerType.isNullable()) {
                return false;
            }
            if (innerCheck || outerCheck) {
                return false;
            }
            return true;
        }
    }
    
    /**
     * Class for rebuilding a {@link RexCall}, with new operands.
     * This even works for operators which do not support return 
     * type derivation (cast, reinterpret).
     */
    static class RexRebuilder
    {
        /**
         * Rebuilds a call with new operands. Note that if the operands
         * have not changed, then the original call will be returned.
         * 
         * @param call call which may have its operands replaced
         * @param newOperands new or original operands
         * @param builder factory for making the rebuilt call
         * @return rebuilt call or the original call
         */
        static RexNode rebuildCall(
            RexCall call, RexNode[] newOperands, RexBuilder builder)
        {
            if (call.operands == newOperands) {
                return call;
            }
            
            if (call.isA(RexKind.Cast)) {
                Util.pre(newOperands.length==1,"newOperands.length==1");
                return builder.makeCast(call.getType(), newOperands[0]);
            } else if (call.isA(RexKind.Reinterpret)) {
                Util.pre(newOperands.length==2,"newOperands.length==2");
                return builder.makeReinterpretCast(
                    call.getType(), newOperands[0], newOperands[1]);
            }
            return builder.makeCall(call.getOperator(), newOperands);
        }
    }
}

// End ReduceDecimalsRule.java