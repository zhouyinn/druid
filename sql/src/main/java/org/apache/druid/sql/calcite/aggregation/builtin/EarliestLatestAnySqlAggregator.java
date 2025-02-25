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

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.apache.calcite.util.Optionality;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.InvalidSqlInput;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.any.DoubleAnyAggregatorFactory;
import org.apache.druid.query.aggregation.any.FloatAnyAggregatorFactory;
import org.apache.druid.query.aggregation.any.LongAnyAggregatorFactory;
import org.apache.druid.query.aggregation.any.StringAnyAggregatorFactory;
import org.apache.druid.query.aggregation.first.DoubleFirstAggregatorFactory;
import org.apache.druid.query.aggregation.first.FloatFirstAggregatorFactory;
import org.apache.druid.query.aggregation.first.LongFirstAggregatorFactory;
import org.apache.druid.query.aggregation.first.StringFirstAggregatorFactory;
import org.apache.druid.query.aggregation.last.DoubleLastAggregatorFactory;
import org.apache.druid.query.aggregation.last.FloatLastAggregatorFactory;
import org.apache.druid.query.aggregation.last.LongLastAggregatorFactory;
import org.apache.druid.query.aggregation.last.StringLastAggregatorFactory;
import org.apache.druid.query.aggregation.post.FinalizingFieldAccessPostAggregator;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.sql.calcite.aggregation.Aggregation;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.Expressions;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.rel.InputAccessor;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EarliestLatestAnySqlAggregator implements SqlAggregator
{
  public static final SqlAggregator EARLIEST = new EarliestLatestAnySqlAggregator(
      AggregatorType.EARLIEST,
      EarliestLatestBySqlAggregator.EARLIEST_BY.calciteFunction()
  );
  public static final SqlAggregator LATEST = new EarliestLatestAnySqlAggregator(
      AggregatorType.LATEST,
      EarliestLatestBySqlAggregator.LATEST_BY.calciteFunction()
  );
  public static final SqlAggregator ANY_VALUE = new EarliestLatestAnySqlAggregator(AggregatorType.ANY_VALUE, null);

  enum AggregatorType
  {
    EARLIEST {
      @Override
      AggregatorFactory createAggregatorFactory(
          String name,
          String fieldName,
          String timeColumn,
          ColumnType type,
          Integer maxStringBytes
      )
      {
        switch (type.getType()) {
          case LONG:
            return new LongFirstAggregatorFactory(name, fieldName, timeColumn);
          case FLOAT:
            return new FloatFirstAggregatorFactory(name, fieldName, timeColumn);
          case DOUBLE:
            return new DoubleFirstAggregatorFactory(name, fieldName, timeColumn);
          case STRING:
          case COMPLEX:
            return new StringFirstAggregatorFactory(name, fieldName, timeColumn, maxStringBytes);
          default:
            throw SimpleSqlAggregator.badTypeException(fieldName, "EARLIEST", type);
        }
      }
    },

    LATEST {
      @Override
      AggregatorFactory createAggregatorFactory(
          String name,
          String fieldName,
          String timeColumn,
          ColumnType type,
          Integer maxStringBytes
      )
      {
        switch (type.getType()) {
          case LONG:
            return new LongLastAggregatorFactory(name, fieldName, timeColumn);
          case FLOAT:
            return new FloatLastAggregatorFactory(name, fieldName, timeColumn);
          case DOUBLE:
            return new DoubleLastAggregatorFactory(name, fieldName, timeColumn);
          case STRING:
          case COMPLEX:
            return new StringLastAggregatorFactory(name, fieldName, timeColumn, maxStringBytes);
          default:
            throw SimpleSqlAggregator.badTypeException(fieldName, "LATEST", type);
        }
      }
    },

    ANY_VALUE {
      @Override
      AggregatorFactory createAggregatorFactory(
          String name,
          String fieldName,
          String timeColumn,
          ColumnType type,
          Integer maxStringBytes
      )
      {
        switch (type.getType()) {
          case LONG:
            return new LongAnyAggregatorFactory(name, fieldName);
          case FLOAT:
            return new FloatAnyAggregatorFactory(name, fieldName);
          case DOUBLE:
            return new DoubleAnyAggregatorFactory(name, fieldName);
          case STRING:
            return new StringAnyAggregatorFactory(name, fieldName, maxStringBytes);
          default:
            throw SimpleSqlAggregator.badTypeException(fieldName, "ANY", type);
        }
      }
    };

    abstract AggregatorFactory createAggregatorFactory(
        String name,
        String fieldName,
        String timeColumn,
        ColumnType outputType,
        Integer maxStringBytes
    );
  }

  private final AggregatorType aggregatorType;
  private final SqlAggFunction function;

  private EarliestLatestAnySqlAggregator(final AggregatorType aggregatorType, final SqlAggFunction replacementAggFunc)
  {
    this.aggregatorType = aggregatorType;
    this.function = new EarliestLatestSqlAggFunction(aggregatorType, replacementAggFunc);
  }

  @Override
  public SqlAggFunction calciteFunction()
  {
    return function;
  }

  @Nullable
  @Override
  public Aggregation toDruidAggregation(
      final PlannerContext plannerContext,
      final VirtualColumnRegistry virtualColumnRegistry,
      final String name,
      final AggregateCall aggregateCall,
      final InputAccessor inputAccessor,
      final List<Aggregation> existingAggregations,
      final boolean finalizeAggregations
  )
  {
    final List<RexNode> rexNodes = inputAccessor.getFields(aggregateCall.getArgList());

    final List<DruidExpression> args = Expressions.toDruidExpressions(plannerContext, inputAccessor.getInputRowSignature(), rexNodes);

    if (args == null) {
      return null;
    }

    final String aggregatorName = finalizeAggregations ? Calcites.makePrefixedName(name, "a") : name;
    final ColumnType outputType = Calcites.getColumnTypeForRelDataType(aggregateCall.getType());
    if (outputType == null) {
      throw DruidException.forPersona(DruidException.Persona.ADMIN)
                          .ofCategory(DruidException.Category.DEFENSIVE)
                          .build(
                              "Cannot convert output SQL type[%s] to a Druid type for function [%s]",
                              aggregateCall.getName(),
                              aggregateCall.getType().getSqlTypeName()
                          );
    }

    final String fieldName = getColumnName(plannerContext, virtualColumnRegistry, args.get(0), rexNodes.get(0));

    if (!inputAccessor.getInputRowSignature().contains(ColumnHolder.TIME_COLUMN_NAME)
        && (aggregatorType == AggregatorType.LATEST || aggregatorType == AggregatorType.EARLIEST)) {
      // This code is being run as part of the exploratory volcano planner, currently, the definition of these
      // aggregators does not tell Calcite that they depend on a __time column being in existence, instead we are
      // allowing the volcano planner to explore paths that put projections which eliminate the time column in between
      // the table scan and the aggregation and then relying on this check to tell Calcite that the plan is bogus.
      // In some future, it would be good to make the aggregator definition capable of telling Calcite that it depends
      // on a __time column to be in existence.  Or perhaps we should just kill these aggregators and have everything
      // move to the _BY aggregators that require an explicit definition.  Either way, for now, we set this potential
      // error and let the volcano planner continue exploring
      plannerContext.setPlanningError(
          "LATEST and EARLIEST aggregators implicitly depend on the __time column, but the "
          + "table queried doesn't contain a __time column.  Please use LATEST_BY or EARLIEST_BY "
          + "and specify the column explicitly."
      );
      return null;
    }

    final AggregatorFactory theAggFactory;
    switch (args.size()) {
      case 1:
        theAggFactory = aggregatorType.createAggregatorFactory(aggregatorName, fieldName, null, outputType, null);
        break;
      case 2:
        int maxStringBytes;
        try {
          maxStringBytes = RexLiteral.intValue(rexNodes.get(1));
        }
        catch (AssertionError ae) {
          plannerContext.setPlanningError(
              "The second argument '%s' to function '%s' is not a number",
              rexNodes.get(1),
              aggregateCall.getName()
          );
          return null;
        }
        theAggFactory = aggregatorType.createAggregatorFactory(
            aggregatorName,
            fieldName,
            null,
            outputType,
            maxStringBytes
        );
        break;
      default:
        throw InvalidSqlInput.exception(
            "Function [%s] expects 1 or 2 arguments but found [%s]",
            aggregateCall.getName(),
            args.size()
        );
    }

    return Aggregation.create(
        Collections.singletonList(theAggFactory),
        finalizeAggregations ? new FinalizingFieldAccessPostAggregator(name, aggregatorName) : null
    );
  }

  static String getColumnName(
      PlannerContext plannerContext,
      VirtualColumnRegistry virtualColumnRegistry,
      DruidExpression arg,
      RexNode rexNode
  )
  {
    String columnName;
    if (arg.isDirectColumnAccess()) {
      columnName = arg.getDirectColumn();
    } else {
      final RelDataType dataType = rexNode.getType();
      columnName = virtualColumnRegistry.getOrCreateVirtualColumnForExpression(arg, dataType);
    }
    return columnName;
  }

  static class EarliestLatestReturnTypeInference implements SqlReturnTypeInference
  {
    private final int ordinal;

    public EarliestLatestReturnTypeInference(int ordinal)
    {
      this.ordinal = ordinal;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding sqlOperatorBinding)
    {
      RelDataType type = sqlOperatorBinding.getOperandType(this.ordinal);
      // For non-number and non-string type, which is COMPLEX type, we set the return type to VARCHAR.
      if (!SqlTypeUtil.isNumeric(type) &&
          !SqlTypeUtil.isString(type)) {
        return sqlOperatorBinding.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
      } else {
        return type;
      }
    }
  }

  private static class TimeColIdentifer extends SqlIdentifier
  {

    public TimeColIdentifer()
    {
      super("__time", SqlParserPos.ZERO);
    }

    @Override
    public <R> R accept(SqlVisitor<R> visitor)
    {

      try {
        return super.accept(visitor);
      }
      catch (CalciteContextException e) {
        if (e.getCause() instanceof SqlValidatorException) {
          throw DruidException.forPersona(DruidException.Persona.ADMIN)
                              .ofCategory(DruidException.Category.INVALID_INPUT)
                              .build(
                                  e,
                                  "Query could not be planned. A possible reason is [%s]",
                                  "LATEST and EARLIEST aggregators implicitly depend on the __time column, but the "
                                  + "table queried doesn't contain a __time column.  Please use LATEST_BY or EARLIEST_BY "
                                  + "and specify the column explicitly."
                              );

        } else {
          throw e;
        }
      }
    }
  }

  private static class EarliestLatestSqlAggFunction extends SqlAggFunction
  {
    private static final EarliestLatestReturnTypeInference EARLIEST_LATEST_ARG0_RETURN_TYPE_INFERENCE =
        new EarliestLatestReturnTypeInference(0);

    private final SqlAggFunction replacementAggFunc;

    EarliestLatestSqlAggFunction(AggregatorType aggregatorType, SqlAggFunction replacementAggFunc)
    {
      super(
          aggregatorType.name(),
          null,
          SqlKind.OTHER_FUNCTION,
          EARLIEST_LATEST_ARG0_RETURN_TYPE_INFERENCE,
          InferTypes.RETURN_TYPE,
          OperandTypes.or(
              OperandTypes.ANY,
              OperandTypes.sequence(
                  "'" + aggregatorType.name() + "(expr, maxBytesPerString)'",
                  OperandTypes.ANY,
                  OperandTypes.and(OperandTypes.NUMERIC, OperandTypes.LITERAL)
              )
          ),
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false,
          Optionality.FORBIDDEN
      );
      this.replacementAggFunc = replacementAggFunc;
    }

    @Override
    public SqlNode rewriteCall(
        SqlValidator validator,
        SqlCall call
    )
    {
      // Rewrite EARLIEST/LATEST to EARLIEST_BY/LATEST_BY to make
      // reference to __time column explicit so that Calcite tracks it

      if (replacementAggFunc == null) {
        return call;
      }

      List<SqlNode> operands = call.getOperandList();

      SqlParserPos pos = call.getParserPosition();

      if (operands.isEmpty() || operands.size() > 2) {
        throw InvalidSqlInput.exception(
            "Function [%s] expects 1 or 2 arguments but found [%s]",
            getName(),
            operands.size()
        );
      }

      List<SqlNode> newOperands = new ArrayList<>();
      newOperands.add(operands.get(0));
      newOperands.add(new TimeColIdentifer());

      if (operands.size() == 2) {
        newOperands.add(operands.get(1));
      }

      return replacementAggFunc.createCall(pos, newOperands);
    }
  }
}
