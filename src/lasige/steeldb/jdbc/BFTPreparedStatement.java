package lasige.steeldb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;

import lasige.steeldb.comm.Message;
import lasige.steeldb.comm.MessageHandler;
import lasige.steeldb.comm.OpcodeList;

public class BFTPreparedStatement implements PreparedStatement{

	private boolean closed;
	private MessageHandler mHandler;
	private HashMap<Integer, String> parameters = new HashMap<Integer, String>();
	private String template;
	private String current;
	private LinkedList<String> batch = new LinkedList<String>();
	private int statementOption;
	private ResultSet generatedKeys;
	private Connection connection;
	private int[] parameterTypes;
	private int parameterCount = -1;
	// Used to fix the difference between the index of the parameter
	// array and the index from the statement beginning in 1
	private int parameterIndexOffset = -1;
	
	protected int maxRows = -1;
	protected int fetchSize = -1;
	protected int timeoutInMillis = -1;

	public BFTPreparedStatement(Connection connection, MessageHandler mHandler, String sql){
		this.connection = connection;
		this.mHandler = mHandler;
		parseSQL(sql.toLowerCase());
		this.template = sql.toLowerCase();
		this.current = sql.toLowerCase();
		this.closed = false;
		parameters = new HashMap<Integer, String>();
	}

	public BFTPreparedStatement(Connection connection, MessageHandler mHandler, String sql, int statementOption){
		this.connection = connection;
		this.mHandler = mHandler;
		parseSQL(sql.toLowerCase());
		this.template = sql.toLowerCase();
		this.current = sql.toLowerCase();
		this.closed = false;
		this.statementOption = statementOption;
		parameters = new HashMap<Integer, String>();
	}

	private void parseSQL(String sql) {
		int index = 0;
		int count = 0;
		index = sql.indexOf('?');
		while(index > -1) {
			count++;
			index = sql.indexOf('?', (index+1));
		}
		parameterCount = count;
		if(parameterCount > 0)
			parameterTypes = new int[parameterCount];
	}
	
	private Object process(int opcode) throws SQLException {
		if (closed) {
			throw new SQLException("Can't execute after statement has been closed");
		}
		if (current.contains("?")) {
			current = updateSql(current);
		}
		Message query;
		if(statementOption > 0)
			query = new Message(opcode, current, true, statementOption);
		else
			query = new Message(opcode, current, true);
		
		Message reply = null;
		if(this.getConnection().getAutoCommit())
			reply = mHandler.send(query, true);
		else
			reply = mHandler.send(query, false);

		if(reply.getOpcode() == OpcodeList.EXECUTE_QUERY_ERROR)
			throw new SQLException("Execute query error");
		else if(reply.getOpcode() == OpcodeList.EXECUTE_UPDATE_ERROR)
			throw new SQLException("Execute update error");
		else if(reply.getOpcode() == OpcodeList.TIMEOUT)
			throw new SQLException("Execution timeout");
		else if(reply.getOpcode() == OpcodeList.ABORTED)
			throw new SQLException("Transaction aborted");
		
		if(query.getStatementOption() > 0) {
			generatedKeys = reply.getRowSet();
		}
		return reply.getContents();
	}

	/**
	 * update the SQL string with parameters set by setXXX methods of {@link PreparedStatement}
	 * @param sql
	 * @param parameters
	 * @return updated SQL string
	 */
	private String updateSql(final String sql) {
		StringBuffer newSql = new StringBuffer(sql);

		int paramLoc = 1;
		while (getCharIndexFromSqlByParamLocation(sql, '?', paramLoc) > 0) {
			// check the user has set the needs parameters
			if (parameters.containsKey(paramLoc)) {
				int tt = getCharIndexFromSqlByParamLocation(newSql.toString(), '?', 1);
				newSql.deleteCharAt(tt);
				newSql.insert(tt, parameters.get(paramLoc));
			}
			paramLoc++;
		}

		return newSql.toString();

	}
	
	/**
	 * Get the index of given char from the SQL string by parameter location
	 * </br> The -1 will be return, if nothing found
	 *
	 * @param sql
	 * @param cchar
	 * @param paramLoc
	 * @return
	 */
	private int getCharIndexFromSqlByParamLocation(final String sql, final char cchar, final int paramLoc) {
		int signalCount = 0;
		int charIndex = -1;
		int num = 0;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '\'' || c == '\\')// record the count of char "'" and char "\"
			{
				signalCount++;
			} else if (c == cchar && signalCount % 2 == 0) {// check if the ? is really the parameter
				num++;
				if (num == paramLoc) {
					charIndex = i;
					break;
				}
			}
		}
		return charIndex;
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		Object o = process(OpcodeList.EXECUTE_QUERY);
		ResultSet rs = (ResultSet)o;
		current = template;
		parameters.clear();
		return rs;
	}

	@Override
	public int executeUpdate() throws SQLException {
		int result = (Integer)process(OpcodeList.EXECUTE_UPDATE);
		current = template;
		parameters.clear();
		return result;
	}

	@Override
	public int[] executeBatch() throws SQLException {
		if (closed) {
			throw new SQLException("Can't execute after statement has been closed");
		}
		Message query = new Message(OpcodeList.EXECUTE_BATCH, batch, true);
		Message reply = mHandler.send(query, this.getConnection().getAutoCommit());
		if(reply.getOpcode() == OpcodeList.EXECUTE_BATCH_ERROR)
			throw new SQLException("Error executing batch");
		else if(reply.getOpcode() == OpcodeList.TIMEOUT)
			throw new SQLException("Timeout on batch execution");
		else if(reply.getOpcode() == OpcodeList.ABORTED)
			throw new SQLException("Transaction aborted");
		clearBatch();
		@SuppressWarnings("unchecked")
		ArrayList<Integer> result = (ArrayList<Integer>)reply.getContents();
		int[] returnArray = new int[result.size()];
		for(int i = 0; i < returnArray.length; i++)
			returnArray[i] = result.get(i);
		return returnArray;
	}

	@Override
	public ResultSet executeQuery(String arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int executeUpdate(String arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean execute(String arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void cancel() throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.DOUBLE;
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);		
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.FLOAT;
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);		
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.INTEGER;
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);		
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.BIGINT;
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.SMALLINT;
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		StringBuffer buf = new StringBuffer();
		buf.append('\'');
		for(int i = 0; i < x.length(); i++) {
			char c = x.charAt(i);
			if(c == '\'') {
				buf.append('\'');
				buf.append('\'');
			} else if(c == '\\') {
				switch(c) {
				case 't':
					buf.append('\t');
					break;
				case 'r':
					buf.append('\r');
					break;
				case 'n':
					buf.append('\n');
					break;
				case 'b':
					buf.append('\b');
					break;
				case 'f':
					buf.append('\f');
					break;
				case '\\':
					buf.append('\\');
					break;
				default:
					buf.append(c);
				}
			} else {
				buf.append(c);
			}
		}
		buf.append('\'');
		String cleanX = buf.toString();
		this.parameters.put(parameterIndex, cleanX);
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.VARCHAR;
	}

	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {
		String efficientTime = formatTime(x);
		this.parameters.put(parameterIndex,"'"+efficientTime+"'");
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.TIME;
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		String efficientTime = formatTime(x);
		this.parameters.put(parameterIndex,"'"+efficientTime+"'");
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.TIMESTAMP;
	}

	@Override
	public void addBatch() throws SQLException {
		current = updateSql(current);
		batch.addLast(current);
		current = template;
		clearParameters();
	}

	@Override
	public void clearBatch() throws SQLException {
		this.batch = new LinkedList<String>();
		this.parameters.clear();
		current = template;

	}

	@Override
	public void clearWarnings() throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void close() throws SQLException {
		this.closed = true;
	}

	@Override
	public void addBatch(String arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean execute(String arg0, int arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean execute(String arg0, int[] arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean execute(String arg0, String[] arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int executeUpdate(String arg0, int arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int executeUpdate(String arg0, int[] arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int executeUpdate(String arg0, String[] arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Connection getConnection() throws SQLException {
		return this.connection;
	}

	@Override
	public int getFetchDirection() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getFetchSize() throws SQLException {
		return this.fetchSize;
	}

	@Override
	public int getMaxFieldSize() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getMaxRows() throws SQLException {
		return this.maxRows;
//		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getMoreResults(int arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getQueryTimeout() throws SQLException {
		return timeoutInMillis / 1000;
	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getResultSetType() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getUpdateCount() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isClosed() throws SQLException {
		return this.closed;
	}

	@Override
	public boolean isPoolable() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setCursorName(String arg0) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setEscapeProcessing(boolean arg0) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setFetchDirection(int arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setFetchSize(int arg0) throws SQLException {
		this.fetchSize = arg0;
	}

	@Override
	public void setMaxFieldSize(int arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setMaxRows(int arg0) throws SQLException {
		this.maxRows = arg0;
	}

	@Override
	public void setPoolable(boolean arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setQueryTimeout(int arg0) throws SQLException {
		this.timeoutInMillis = arg0;
	}

	@Override
	public boolean isWrapperFor(Class<?> arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> T unwrap(Class<T> arg0) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void clearParameters() throws SQLException {
		this.parameters.clear();
	}

	@Override
	public boolean execute() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setArray(int arg0, Array arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setAsciiStream(int arg0, InputStream arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setAsciiStream(int arg0, InputStream arg1, int arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setAsciiStream(int arg0, InputStream arg1, long arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBigDecimal(int arg0, BigDecimal arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBinaryStream(int arg0, InputStream arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBinaryStream(int arg0, InputStream arg1, int arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBinaryStream(int arg0, InputStream arg1, long arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setBlob(int arg0, InputStream arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBlob(int arg0, InputStream arg1, long arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		this.parameters.put(parameterIndex,""+x);			
	}

	@Override
	public void setByte(int arg0, byte arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setBytes(int arg0, byte[] arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setCharacterStream(int arg0, Reader arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setCharacterStream(int arg0, Reader arg1, int arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setCharacterStream(int arg0, Reader arg1, long arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setClob(int arg0, Clob arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setClob(int arg0, Reader arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setClob(int arg0, Reader arg1, long arg2) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {
		String efficientTime = formatTime(x);
		efficientTime = efficientTime.substring(0, 10);
		this.parameters.put(parameterIndex,"'"+efficientTime+"'");
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.DATE;
	}

	@Override
	public void setDate(int arg0, Date arg1, Calendar arg2) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNCharacterStream(int arg0, Reader arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNCharacterStream(int arg0, Reader arg1, long arg2)
	throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNClob(int arg0, NClob arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNClob(int arg0, Reader arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNClob(int arg0, Reader arg1, long arg2) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNString(int arg0, String arg1) throws SQLException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void setNull(int parameterIndex, int arg1) throws SQLException {
		this.parameters.put(parameterIndex,"null");
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.NULL;
	}

	@Override
	public void setNull(int parameterIndex, int arg1, String arg2) throws SQLException {
		setNull(parameterIndex, arg1);
	}

	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {
		if(x == null)
			setNull(parameterIndex, Types.OTHER);
		else if(x instanceof Boolean)
			setBoolean(parameterIndex, ((Boolean)x).booleanValue());
		else if(x instanceof Double)
			setDouble(parameterIndex, ((Double)x).doubleValue());
		else if(x instanceof Float)
			setFloat(parameterIndex, ((Float)x).floatValue());
		else if(x instanceof Integer)
			setInt(parameterIndex, ((Integer)x).intValue());
		else if(x instanceof Long)
			setLong(parameterIndex, ((Long)x).longValue());
		else if(x instanceof Short)
			setShort(parameterIndex, ((Short)x).shortValue());
		else if(x instanceof String)
			setString(parameterIndex, (String)x);
		else if(x instanceof Time)
			setTime(parameterIndex, (Time)x);
		else if(x instanceof Timestamp)
			setTimestamp(parameterIndex, (Timestamp)x);
		else
			throw new UnsupportedOperationException();
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		this.parameterTypes[parameterIndex + parameterIndexOffset] = Types.TIMESTAMP;
		setObject(parameterIndex, x);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
	throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setSQLXML(int arg0, SQLXML arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setTime(int arg0, Time arg1, Calendar arg2) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setTimestamp(int arg0, Timestamp arg1, Calendar arg2)
	throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setURL(int arg0, URL arg1) throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setUnicodeStream(int arg0, InputStream arg1, int arg2)
	throws SQLException {
		throw new UnsupportedOperationException();
	}

	public void setStatementOption(int statementOption) {
		this.statementOption = statementOption;
	}

	public ResultSet getGeneratedKeys() {
		return this.generatedKeys;
	}

	@Override
	public void closeOnCompletion() throws SQLException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCloseOnCompletion() throws SQLException {
		throw new UnsupportedOperationException();
	}

	private String formatTime(Object value)  throws SQLException {
		String timeAsString = value.toString();
		String formatedTime = timeAsString;
		int length = timeAsString.length();
		if(length == 23 && timeAsString.charAt(4) == '-') {
			if(timeAsString.endsWith("00:00:00.000")) {
				formatedTime = timeAsString.substring(0, 10);
			}
		} else if(length == 22 && timeAsString.charAt(4) == '-') {
			if(timeAsString.endsWith("00:00:00.00")) {
				formatedTime = timeAsString.substring(0, 10);
			}
		} else if(length == 21 && timeAsString.charAt(4) == '-') {
			if(timeAsString.endsWith("00:00:00.0")) {
				formatedTime = timeAsString.substring(0, 10);
			}
		} else if(length == 19 && timeAsString.charAt(4) == '-') {
			if(timeAsString.endsWith("00:00:00")) {
				formatedTime = timeAsString.substring(0, 10);
			}
		} else if(length == 16 && timeAsString.charAt(4) == '-') {
			if(timeAsString.endsWith("00:00")) {
				formatedTime = timeAsString.substring(0, 10);
			}
		}
		return formatedTime;
	}

}
