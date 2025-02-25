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

package org.apache.druid.query.aggregation.tdigestsketch.sql;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.tdigestsketch.TDigestSketchAggregatorFactory;
import org.apache.druid.query.aggregation.tdigestsketch.TDigestSketchUtils;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.sql.calcite.aggregation.Aggregation;
import org.apache.druid.sql.calcite.aggregation.Aggregations;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.rel.InputAccessor;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;

import javax.annotation.Nullable;
import java.util.List;

public class TDigestGenerateSketchSqlAggregator implements SqlAggregator
{
  private static final SqlAggFunction FUNCTION_INSTANCE = new TDigestGenerateSketchSqlAggregator.TDigestGenerateSketchSqlAggFunction();
  private static final String NAME = "TDIGEST_GENERATE_SKETCH";

  @Override
  public SqlAggFunction calciteFunction()
  {
    return FUNCTION_INSTANCE;
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
    final RexNode inputOperand = inputAccessor.getField(aggregateCall.getArgList().get(0));
    final DruidExpression input = Aggregations.toDruidExpressionForNumericAggregator(
        plannerContext,
        inputAccessor.getInputRowSignature(),
        inputOperand
    );
    if (input == null) {
      return null;
    }

    final AggregatorFactory aggregatorFactory;
    final String aggName = StringUtils.format("%s:agg", name);

    Integer compression = TDigestSketchAggregatorFactory.DEFAULT_COMPRESSION;
    if (aggregateCall.getArgList().size() > 1) {
      RexNode compressionOperand = inputAccessor.getField(aggregateCall.getArgList().get(1));
      if (!compressionOperand.isA(SqlKind.LITERAL)) {
        // compressionOperand must be a literal in order to plan.
        return null;
      }
      compression = ((Number) RexLiteral.value(compressionOperand)).intValue();
    }

    // Look for existing matching aggregatorFactory.
    for (final Aggregation existing : existingAggregations) {
      for (AggregatorFactory factory : existing.getAggregatorFactories()) {
        if (factory instanceof TDigestSketchAggregatorFactory) {
          final TDigestSketchAggregatorFactory theFactory = (TDigestSketchAggregatorFactory) factory;
          final boolean matches = TDigestSketchUtils.matchingAggregatorFactoryExists(
              virtualColumnRegistry,
              input,
              compression,
              (TDigestSketchAggregatorFactory) factory
          );

          if (matches) {
            // Found existing one. Use this.
            return Aggregation.create(
                theFactory
            );
          }
        }
      }
    }

    // No existing match found. Create a new one.
    if (input.isDirectColumnAccess()) {
      aggregatorFactory = new TDigestSketchAggregatorFactory(
          aggName,
          input.getDirectColumn(),
          compression
      );
    } else {
      String virtualColumnName = virtualColumnRegistry.getOrCreateVirtualColumnForExpression(
          input,
          ColumnType.FLOAT
      );
      aggregatorFactory = new TDigestSketchAggregatorFactory(aggName, virtualColumnName, compression);
    }

    return Aggregation.create(aggregatorFactory);
  }

  private static class TDigestGenerateSketchSqlAggFunction extends SqlAggFunction
  {
    private static final String SIGNATURE_WITH_COMPRESSION = "'" + NAME + "(column, compression)'";

    TDigestGenerateSketchSqlAggFunction()
    {
      super(
          NAME,
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.OTHER),
          null,
          OperandTypes.or(
              OperandTypes.ANY,
              OperandTypes.and(
                  OperandTypes.sequence(SIGNATURE_WITH_COMPRESSION, OperandTypes.ANY, OperandTypes.LITERAL),
                  OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.NUMERIC)
              )
          ),
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false
      );
    }
  }
}
