package com.taosdata.jdbc.ws;

import com.taosdata.jdbc.*;
import com.taosdata.jdbc.utils.ReqId;
import com.taosdata.jdbc.utils.SqlSyntaxValidator;
import com.taosdata.jdbc.ws.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.taosdata.jdbc.utils.SqlSyntaxValidator.getDatabaseName;

public class WSStatement extends AbstractStatement {
    private final Logger log = LoggerFactory.getLogger(WSStatement.class);


    protected Transport transport;
    private String database;
    private final AbstractConnection connection;

    private boolean closed;
    private ResultSet resultSet;

    private int queryTimeout = 0;

    public WSStatement(Transport transport, String database, AbstractConnection connection, Long instanceId) {
        this.transport = transport;
        this.database = database;
        this.connection = connection;
        this.instanceId = instanceId;
        this.connection.registerStatement(this.instanceId, this);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        long b = System.currentTimeMillis();
        long reqid = ReqId.getReqID();
        ResultSet rs = this.executeQuery(sql, reqid);
        long e = System.currentTimeMillis();
        if ((e - b) > 1000){
            log.info("thid: {}, reqid: 0x{}, executeQuery {}, finished, cost {} ms",
                    Thread.currentThread().getId(),
                    Long.toHexString(reqid),
                    sql, e -b);
        }
        return rs;
    }

    public ResultSet executeQuery(String sql, Long reqId) throws SQLException {

        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);

        this.execute(sql, reqId);
        return this.resultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return this.executeUpdate(sql, (Long) null);
    }

    public int executeUpdate(String sql, Long reqId) throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);

        this.execute(sql, reqId);
        return affectedRows;
    }

    @Override
    public void close() throws SQLException {
        if (!isClosed()) {
            this.connection.unregisterStatement(this.instanceId);
            this.closed = true;
            if (resultSet != null && !resultSet.isClosed()) {
                resultSet.close();
            }
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return this.execute(sql, (Long) null);
    }

    public boolean execute(String sql, Long reqId) throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);

        if (null == reqId)
            reqId = ReqId.getReqID();

        byte[] sqlBytes = sql.getBytes();

        // write version and sqlLen in little endian byte sequence
        byte[] result = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putInt(sqlBytes.length).array();
        Response response = transport.send(Action.BINARY_QUERY.getAction(),
                reqId, 0, 6, result, sqlBytes);

        QueryResp queryResp = (QueryResp) response;
        if (Code.SUCCESS.getCode() != queryResp.getCode()) {
            throw TSDBError.createSQLException(queryResp.getCode(), queryResp.getMessage());
        }
        if (SqlSyntaxValidator.isUseSql(sql)) {
            this.database = getDatabaseName(sql);
            this.connection.setCatalog(this.database);
            this.connection.setClientInfo(TSDBDriver.PROPERTY_KEY_DBNAME, this.database);
        }
        if (queryResp.isUpdate()) {
            this.resultSet = null;
            this.affectedRows = queryResp.getAffectedRows();
            return false;
        } else {
            this.resultSet = new BlockResultSet(this, this.transport, queryResp, this.database);
            this.affectedRows = -1;
            return true;
        }
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);
        if (seconds < 0)
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_INVALID_VARIABLE);

        this.queryTimeout = seconds;
        transport.setTimeout(seconds * 1000L);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);
        return this.resultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        if (isClosed())
            throw TSDBError.createSQLException(TSDBErrorNumbers.ERROR_STATEMENT_CLOSED);

        return affectedRows;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.connection;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }
}
