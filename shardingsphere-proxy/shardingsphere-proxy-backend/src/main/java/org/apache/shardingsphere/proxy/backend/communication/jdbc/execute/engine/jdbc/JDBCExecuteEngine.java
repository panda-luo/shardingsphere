/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.communication.jdbc.execute.engine.jdbc;

import org.apache.shardingsphere.infra.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.executor.kernel.model.ExecutionGroup;
import org.apache.shardingsphere.infra.executor.sql.ExecutorConstant;
import org.apache.shardingsphere.infra.executor.sql.context.ExecutionContext;
import org.apache.shardingsphere.infra.executor.sql.context.ExecutionUnit;
import org.apache.shardingsphere.infra.executor.sql.group.ExecutionGroupEngine;
import org.apache.shardingsphere.infra.executor.sql.execute.raw.RawSQLExecuteUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.raw.execute.callback.RawSQLExecutorCallback;
import org.apache.shardingsphere.infra.executor.sql.execute.raw.execute.result.ExecuteResult;
import org.apache.shardingsphere.infra.executor.sql.execute.raw.execute.result.query.ExecuteQueryResult;
import org.apache.shardingsphere.infra.executor.sql.execute.raw.execute.result.query.QueryHeader;
import org.apache.shardingsphere.infra.executor.sql.group.raw.RawExecutionGroupEngine;
import org.apache.shardingsphere.infra.executor.sql.execute.resourced.jdbc.StatementExecuteUnit;
import org.apache.shardingsphere.infra.executor.sql.execute.resourced.jdbc.executor.ExecutorExceptionHandler;
import org.apache.shardingsphere.infra.executor.sql.execute.resourced.jdbc.executor.SQLExecutor;
import org.apache.shardingsphere.infra.executor.sql.execute.resourced.jdbc.StatementOption;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.execute.SQLExecuteEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.statement.accessor.JDBCAccessor;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.transaction.TransactionStatus;
import org.apache.shardingsphere.proxy.backend.context.BackendExecutorContext;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.exception.TableModifyInTransactionException;
import org.apache.shardingsphere.proxy.backend.response.BackendResponse;
import org.apache.shardingsphere.proxy.backend.response.query.QueryResponse;
import org.apache.shardingsphere.proxy.backend.response.update.UpdateResponse;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.type.TableAvailable;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.DDLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.DeleteStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.InsertStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.UpdateStatement;
import org.apache.shardingsphere.transaction.core.TransactionType;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * SQL Execute engine for JDBC.
 */
public final class JDBCExecuteEngine implements SQLExecuteEngine {
    
    private final BackendConnection backendConnection;
    
    private final JDBCAccessor accessor;
    
    private final SQLExecutor sqlExecutor;
    
    private final RawProxyExecutor rawExecutor;
    
    public JDBCExecuteEngine(final BackendConnection backendConnection, final JDBCAccessor accessor) {
        this.backendConnection = backendConnection;
        this.accessor = accessor;
        sqlExecutor = new SQLExecutor(BackendExecutorContext.getInstance().getExecutorEngine(), backendConnection.isSerialExecute());
        rawExecutor = new RawProxyExecutor(BackendExecutorContext.getInstance().getExecutorEngine(), backendConnection.isSerialExecute());
    }
    
    @Override
    public void checkExecutePrerequisites(final ExecutionContext executionContext) {
        if (isExecuteDDLInXATransaction(executionContext.getSqlStatementContext().getSqlStatement())) {
            throw new TableModifyInTransactionException(getTableName(executionContext.getSqlStatementContext()));
        }
    }
    
    private boolean isExecuteDDLInXATransaction(final SQLStatement sqlStatement) {
        TransactionStatus transactionStatus = backendConnection.getTransactionStatus();
        return TransactionType.XA == transactionStatus.getTransactionType() && sqlStatement instanceof DDLStatement && transactionStatus.isInTransaction();
    }
    
    private String getTableName(final SQLStatementContext<?> sqlStatementContext) {
        if (sqlStatementContext instanceof TableAvailable && !((TableAvailable) sqlStatementContext).getAllTables().isEmpty()) {
            return ((TableAvailable) sqlStatementContext).getAllTables().iterator().next().getTableName().getIdentifier().getValue();
        }
        return "unknown_table";
    }
    
    @Override
    public BackendResponse execute(final ExecutionContext executionContext) throws SQLException {
        Collection<ExecuteResult> executeResults = execute(executionContext,
                executionContext.getSqlStatementContext().getSqlStatement() instanceof InsertStatement, ExecutorExceptionHandler.isExceptionThrown());
        ExecuteResult executeResult = executeResults.iterator().next();
        if (executeResult instanceof ExecuteQueryResult) {
            return getExecuteQueryResponse(((ExecuteQueryResult) executeResult).getQueryHeaders(), executeResults);
        } else {
            UpdateResponse result = new UpdateResponse(executeResults);
            if (executionContext.getSqlStatementContext().getSqlStatement() instanceof InsertStatement) {
                result.setType("INSERT");
            } else if (executionContext.getSqlStatementContext().getSqlStatement() instanceof DeleteStatement) {
                result.setType("DELETE");
            } else if (executionContext.getSqlStatementContext().getSqlStatement() instanceof UpdateStatement) {
                result.setType("UPDATE");
            }
            return result;
        }
    }
    
    private Collection<ExecuteResult> execute(final ExecutionContext executionContext, final boolean isReturnGeneratedKeys, final boolean isExceptionThrown) throws SQLException {
        int maxConnectionsSizePerQuery = ProxyContext.getInstance().getMetaDataContexts().getProps().<Integer>getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
        return ExecutorConstant.MANAGED_RESOURCE ? executeWithManagedResource(executionContext, maxConnectionsSizePerQuery, isReturnGeneratedKeys, isExceptionThrown)
                : executeWithUnmanagedResource(executionContext, maxConnectionsSizePerQuery);
    }
    
    private Collection<ExecuteResult> executeWithManagedResource(final ExecutionContext executionContext,
                                                                 final int maxConnectionsSizePerQuery, final boolean isReturnGeneratedKeys, final boolean isExceptionThrown) throws SQLException {
        DatabaseType databaseType = ProxyContext.getInstance().getMetaDataContexts().getDatabaseType();
        return sqlExecutor.execute(createExecutionGroups(executionContext.getExecutionUnits(), maxConnectionsSizePerQuery, isReturnGeneratedKeys, executionContext.getRouteContext()),
                new ProxySQLExecutorCallback(databaseType, executionContext.getSqlStatementContext(), backendConnection, accessor, isExceptionThrown, isReturnGeneratedKeys, true),
                new ProxySQLExecutorCallback(databaseType, executionContext.getSqlStatementContext(), backendConnection, accessor, isExceptionThrown, isReturnGeneratedKeys, false));
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection<ExecutionGroup<StatementExecuteUnit>> createExecutionGroups(final Collection<ExecutionUnit> executionUnits, final int maxConnectionsSizePerQuery, 
                                                                                   final boolean isReturnGeneratedKeys, final RouteContext routeContext) throws SQLException {
        Collection<ShardingSphereRule> rules = ProxyContext.getInstance().getMetaData(backendConnection.getSchemaName()).getRuleMetaData().getRules();
        ExecutionGroupEngine groupEngine = accessor.getExecutionGroupEngine(backendConnection, maxConnectionsSizePerQuery, new StatementOption(isReturnGeneratedKeys), rules);
        return (Collection<ExecutionGroup<StatementExecuteUnit>>) groupEngine.group(routeContext, executionUnits);
    }
    
    private Collection<ExecuteResult> executeWithUnmanagedResource(final ExecutionContext executionContext, final int maxConnectionsSizePerQuery) throws SQLException {
        Collection<ShardingSphereRule> rules = ProxyContext.getInstance().getMetaData(backendConnection.getSchemaName()).getRuleMetaData().getRules();
        Collection<ExecutionGroup<RawSQLExecuteUnit>> executionGroups = new RawExecutionGroupEngine(maxConnectionsSizePerQuery, rules).group(executionContext.getRouteContext(),
                executionContext.getExecutionUnits());
        // TODO handle query header
        return rawExecutor.execute(executionGroups, new RawSQLExecutorCallback());
    }
    
    private BackendResponse getExecuteQueryResponse(final List<QueryHeader> queryHeaders, final Collection<ExecuteResult> executeResults) {
        QueryResponse result = new QueryResponse(queryHeaders);
        for (ExecuteResult each : executeResults) {
            result.getQueryResults().add(((ExecuteQueryResult) each).getQueryResult());
        }
        return result;
    }
}
