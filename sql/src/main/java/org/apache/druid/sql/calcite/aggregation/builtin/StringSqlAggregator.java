/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.calcite.aggregation.builtin;

import com.google.common.collect.ImmutableSet;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Optionality;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.java.util.common.HumanReadableBytes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.aggregation.ExpressionLambdaAggregatorFactory;
import org.apache.druid.query.aggregation.FilteredAggregatorFactory;
import org.apache.druid.query.filter.NotDimFilter;
import org.apache.druid.query.filter.NullFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.sql.calcite.aggregation.Aggregation;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.Expressions;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.rel.InputAccessor;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;
import org.apache.druid.sql.calcite.table.RowSignatures;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implements {@link org.apache.calcite.sql.fun.SqlLibraryOperators#STRING_AGG} and
 * {@link org.apache.calcite.sql.fun.SqlStdOperatorTable#LISTAGG}, as well as our extended versions of these
 * functions that include {@code maxSizeBytes}.
 */
public class StringSqlAggregator implements SqlAggregator
{
  private final SqlAggFunction function;

  public static final StringSqlAggregator STRING_AGG = new StringSqlAggregator(new StringAggFunction("STRING_AGG"));
  public static final StringSqlAggregator LISTAGG = new StringSqlAggregator(new StringAggFunction("LISTAGG"));

  public StringSqlAggregator(SqlAggFunction function)
  {
    this.function = function;
  }

  @Override
  public SqlAggFunction calciteFunction()
  {
    return function;
  }

  @Nullable
  @Override
  public Aggregation toDruidAggregation(
      PlannerContext plannerContext,
      VirtualColumnRegistry virtualColumnRegistry,
      String name,
      AggregateCall aggregateCall,
      InputAccessor inputAccessor,
      List<Aggregation> existingAggregations,
      boolean finalizeAggregations
  )
  {
    final List<DruidExpression> arguments = aggregateCall
        .getArgList()
        .stream()
        .map(i -> inputAccessor.getField(i))
        .map(rexNode -> Expressions.toDruidExpression(plannerContext, inputAccessor.getInputRowSignature(), rexNode))
        .collect(Collectors.toList());

    if (arguments.stream().anyMatch(Objects::isNull)) {
      return null;
    }

    RexNode separatorNode = inputAccessor.getField(aggregateCall.getArgList().get(1));
    if (!separatorNode.isA(SqlKind.LITERAL)) {
      // separator must be a literal
      return null;
    }

    final String separator;

    separator = RexLiteral.stringValue(separatorNode);

    if (separator == null) {
      // separator must not be null
      return null;
    }

    Integer maxSizeBytes = null;

    if (arguments.size() > 2) {
      RexNode maxBytes = inputAccessor.getField(aggregateCall.getArgList().get(2));
      if (!maxBytes.isA(SqlKind.LITERAL)) {
        // maxBytes must be a literal
        return null;
      }
      maxSizeBytes = ((Number) RexLiteral.value(maxBytes)).intValue();
    }

    final DruidExpression arg = arguments.get(0);
    final ExprMacroTable macroTable = plannerContext.getPlannerToolbox().exprMacroTable();

    final String initialvalue = "[]";
    final ColumnType elementType = ColumnType.STRING;
    final String fieldName;
    if (arg.isDirectColumnAccess()) {
      fieldName = arg.getDirectColumn();
    } else {
      fieldName = virtualColumnRegistry.getOrCreateVirtualColumnForExpression(arg, elementType);
    }

    final String finalizer = StringUtils.format("if(array_length(o) == 0, null, array_to_string(o, '%s'))", separator);
    final NotDimFilter dimFilter = new NotDimFilter(
        plannerContext.isUseBoundsAndSelectors()
        ? new SelectorDimFilter(fieldName, NullHandling.defaultStringValue(), null)
        : NullFilter.forColumn(fieldName)
    );
    if (aggregateCall.isDistinct()) {
      return Aggregation.create(
          // string_agg ignores nulls
          new FilteredAggregatorFactory(
              new ExpressionLambdaAggregatorFactory(
                  name,
                  ImmutableSet.of(fieldName),
                  null,
                  initialvalue,
                  null,
                  true,
                  false,
                  false,
                  StringUtils.format("array_set_add(\"__acc\", \"%s\")", fieldName),
                  StringUtils.format("array_set_add_all(\"__acc\", \"%s\")", name),
                  null,
                  finalizer,
                  maxSizeBytes != null ? new HumanReadableBytes(maxSizeBytes) : null,
                  macroTable
              ),
              dimFilter
          )
      );
    } else {
      return Aggregation.create(
          // string_agg ignores nulls
          new FilteredAggregatorFactory(
              new ExpressionLambdaAggregatorFactory(
                  name,
                  ImmutableSet.of(fieldName),
                  null,
                  initialvalue,
                  null,
                  true,
                  false,
                  false,
                  StringUtils.format("array_append(\"__acc\", \"%s\")", fieldName),
                  StringUtils.format("array_concat(\"__acc\", \"%s\")", name),
                  null,
                  finalizer,
                  maxSizeBytes != null ? new HumanReadableBytes(maxSizeBytes) : null,
                  macroTable
              ),
              dimFilter
          )
      );
    }
  }

  static class StringAggReturnTypeInference implements SqlReturnTypeInference
  {
    @Override
    public RelDataType inferReturnType(SqlOperatorBinding sqlOperatorBinding)
    {
      RelDataType type = sqlOperatorBinding.getOperandType(0);
      if (type instanceof RowSignatures.ComplexSqlType) {
        String columnName = "";
        if (sqlOperatorBinding instanceof SqlCallBinding) {
          columnName = ((SqlCallBinding) sqlOperatorBinding).getCall().operand(0).toString();
        }

        throw SimpleSqlAggregator.badTypeException(
            columnName,
            "STRING_AGG",
            ((RowSignatures.ComplexSqlType) type).getColumnType()
        );
      }
      return Calcites.createSqlTypeWithNullability(
          sqlOperatorBinding.getTypeFactory(),
          SqlTypeName.VARCHAR,
          true
      );
    }
  }

  private static class StringAggFunction extends SqlAggFunction
  {
    private static final StringAggReturnTypeInference RETURN_TYPE_INFERENCE = new StringAggReturnTypeInference();

    StringAggFunction(String name)
    {
      super(
          name,
          null,
          SqlKind.OTHER_FUNCTION,
          RETURN_TYPE_INFERENCE,
          InferTypes.ANY_NULLABLE,
          OperandTypes.or(
              OperandTypes.and(
                  OperandTypes.sequence(
                      StringUtils.format("'%s(expr, separator)'", name),
                      OperandTypes.ANY,
                      OperandTypes.STRING
                  ),
                  OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.STRING)
              ),
              OperandTypes.and(
                  OperandTypes.sequence(
                      StringUtils.format("'%s(expr, separator, maxSizeBytes)'", name),
                      OperandTypes.ANY,
                      OperandTypes.STRING,
                      OperandTypes.POSITIVE_INTEGER_LITERAL
                  ),
                  OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC)
              )
          ),
          SqlFunctionCategory.STRING,
          false,
          false,
          Optionality.IGNORED
      );
    }
  }
}
