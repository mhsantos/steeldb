package lasige.steeldb.Replica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.log4j.Logger;

import bftsmart.reconfiguration.views.View;
import bftsmart.tom.util.TOMUtil;
//import bftsmart.util.Printer;

import lasige.steeldb.Replica.normalizaton.FirebirdNormalizer;
import lasige.steeldb.Replica.normalizaton.NoNormalizer;
import lasige.steeldb.Replica.normalizaton.Normalizer;
import lasige.steeldb.comm.FinishTransactionRequest;
import lasige.steeldb.comm.LoginRequest;
import lasige.steeldb.comm.MasterChangeRequest;
import lasige.steeldb.comm.Message;
import lasige.steeldb.comm.OpcodeList;
import lasige.steeldb.comm.RollbackRequest;
import lasige.steeldb.jdbc.BFTDatabaseMetaData;
import lasige.steeldb.jdbc.BFTRowSet;
import lasige.steeldb.jdbc.ResultSetData;
import lasige.steeldb.statemanagement.DBConnectionParams;

public class MessageProcessor {
	private int replicaId;
	private SessionManager sm;
	private int master;
	private Normalizer norm;
	private View currentView;
	private long lastMasterChange;
	private boolean installingState;
	
    private static Logger logger = Logger.getLogger("steeldb_processor");

    public MessageProcessor(int id, String driver, String url) {
		this.replicaId = id;
		this.norm = getNormalizer(driver);
		this.sm = new SessionManager(url);
		this.master = 0;
		installingState = false;
	}
	
	protected Message processExecute(Message m, int clientId) {
		ConnManager connManager = sm.getConnManager(clientId);
		String sql = (String)m.getContents();
//		logger.debug("---- Client: " + clientId + ". processExecute(): " + sql);
		Statement s;
		Message reply = null;
		if(connManager.isAborted()) {
			return new Message(OpcodeList.ABORTED, null, false, master);
		}
		int content = 0;
		//normalize statement
		String normSql = norm.normalize(sql);
		try {
			s = connManager.createStatement();
			if(s.execute(normSql))
				content = 1; // 1 = true, 0 = false
			reply = new Message(OpcodeList.EXECUTE_OK, content,false, master);
//			logger.debug(clientId + "--- RESULT EXECUTE " + content);
		} catch (SQLException sqle) {
//			logger.error("processExecute() error", sqle);
			sqle.printStackTrace();
			reply = new Message(OpcodeList.EXECUTE_ERROR, null,false, master);
		}
		return reply;


	}

	protected Message processExecuteBatch(Message m, int clientId) {
		Message reply = null;
		ConnManager connManager = sm.getConnManager(clientId);
		if(connManager.isAborted()) {
			return new Message(OpcodeList.ABORTED, null, false, master);
		}
		@SuppressWarnings("unchecked")
		LinkedList<String> batch = (LinkedList<String>) m.getContents();
		try {
			Statement s = connManager.createStatement();
//			logger.debug("processExecuteBatch() start");
			for(String sql: batch) {
				s.addBatch(sql);
//				logger.debug("processExecuteBatch(): " + sql);
			}
//			logger.debug("processExecuteBatch() end");
			int[] resultArray = s.executeBatch();
			ArrayList<Integer> result = new ArrayList<Integer>(resultArray.length);
			for(int i = 0; i < resultArray.length; i++)
				result.add(resultArray[i]);
			reply = new Message(OpcodeList.EXECUTE_BATCH_OK, result, false, master);
		} catch (SQLException sqle) {
//			logger.error("processExecuteBatch() error", sqle);
			sqle.printStackTrace();
			reply = new Message(OpcodeList.EXECUTE_BATCH_ERROR, null, false, master);
		}
		return reply;
	}

	protected Message processExecuteQuery(Message m, int clientId) {
		Message reply = null;
		ConnManager connManager = sm.getConnManager(clientId);
		if(connManager.isAborted()) {
			return new Message(OpcodeList.ABORTED, null, false, master);
		}
		String sql = (String)m.getContents();
		logger.debug("---- Client: " + clientId + ". QUERY: " + sql);
//		Printer.println("---- Client: " + clientId + ". QUERY: " + sql, "amarelo");
		Statement s;
		try {
			s = connManager.createStatement();
			ResultSet rs = s.executeQuery(sql);
			ResultSetData rsd = new ResultSetData(rs);
				logger.debug("---- Result EXECUTE QUERY: " + rsd);
				logger.debug("---- rsd.metadata hash: " + Arrays.toString(TOMUtil.computeHash(TOMUtil.getBytes(rsd.getMetadata()))) + ", rsd.getRows hash: " + Arrays.toString(TOMUtil.computeHash(TOMUtil.getBytes(rsd.getRows()))));
			reply = new Message(OpcodeList.EXECUTE_QUERY_OK, rsd, true, master);
		} catch (SQLException sqle) {
			logger.error("processExecuteQuery() error", sqle);
//			sqle.printStackTrace();
			reply = new Message(OpcodeList.EXECUTE_QUERY_ERROR, null, true, master);
		}
		return reply;

	}

	protected Message processExecuteUpdate(Message m, int clientId) {
		logger.debug("ExecuteUpdate. clientId: " + clientId);
		ConnManager connManager = sm.getConnManager(clientId);
		
		if(connManager.isAborted()) {
			return new Message(OpcodeList.ABORTED, null, false, master);
		}

		Message reply = null;
		String sql = (String)m.getContents();

		logger.debug("---- Client: " + clientId + ". UPDATE: " + sql);
//		Printer.println("---- Client: " + clientId + ". UPDATE: " + sql, "verde");
		
		Statement s;
		try {
			s = connManager.createStatement();
			int result = -1;
			if(m.getStatementOption() > 0) {
				result = s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
				BFTRowSet rowset = new BFTRowSet();
				rowset.populate(s.getGeneratedKeys());
				reply = new Message(OpcodeList.EXECUTE_UPDATE_OK, result, false, master, Statement.RETURN_GENERATED_KEYS);
				reply.setRowset(rowset);
			} else {
				result = s.executeUpdate(sql);
				reply = new Message(OpcodeList.EXECUTE_UPDATE_OK, result, false, master);
			}
			logger.debug(clientId + "--- RESULT UPDATE: " + result);
		} catch (SQLException sqle) {
			logger.error("processExecuteUpdate() error", sqle);
//			sqle.printStackTrace();
			reply = new Message(OpcodeList.EXECUTE_UPDATE_ERROR, null, false, master);
		}
		
		return reply;
	}

	protected Message processLogin(Message m, int clientId) {
		logger.debug("ProcessLogin. clientId: " + clientId);
		LoginRequest lr = (LoginRequest)m.getContents();
		String database = lr.getDatabase(replicaId);
		String user = lr.getUser(replicaId);
		String password = lr.getPassword(replicaId);
		Message reply;
		if(sm.connect(clientId, database, user, password)) {
			reply = new Message(OpcodeList.LOGIN_OK, null, false, master);
		} else {
			reply = new Message(OpcodeList.LOGIN_ERROR, null, false, master);
		}
		return reply;
	}
	
	protected Message getDBMetadata(int clientId) {
		Message reply = null;
		ConnManager connManager = sm.getConnManager(clientId);
		
		DatabaseMetaData dbm;
		try {
			logger.debug("connManager is not null: " + (connManager != null));
			dbm = connManager.getMetaData();
			BFTDatabaseMetaData bftDBM = new BFTDatabaseMetaData(dbm);
			if(dbm != null)
				reply = new Message(OpcodeList.GET_DB_METADATA_OK, bftDBM, false, master);
			else
				reply = new Message(OpcodeList.GET_DB_METADATA_ERROR, bftDBM, false, master);

			return reply;
		} catch (SQLException sqle) {
			logger.error("getDBMetadata() error: ", sqle);
			sqle.printStackTrace();
			return new Message(OpcodeList.GET_DB_METADATA_ERROR, null, false, master);
		}
	}

	protected Message processCommit(Message m, int clientId) {
//		Printer.println("Transaction commit. Client " + clientId, "vermelho");
		FinishTransactionRequest finishReq = (FinishTransactionRequest) m.getContents();
		LinkedList<byte[]> resHashes = finishReq.getResHashes();
		LinkedList<Message> operations = finishReq.getOperations();
		Message reply = null;
		if(operations != null && operations.size() > 0) {
			reply = processFinishTransaction(m, clientId, OpcodeList.COMMIT);
		}
		if(reply == null || reply.getOpcode() == OpcodeList.COMMIT_OK){
			try {
				ConnManager connManager = sm.getConnManager(clientId);
				connManager.commit();
				if(reply == null)
					reply = new Message(OpcodeList.COMMIT_OK, null, false, master);
			} catch (SQLException sqle) {
				logger.error("processCommit() error", sqle);
//				sqle.printStackTrace();
				reply = new Message(OpcodeList.COMMIT_ERROR, null, true, master);
			}
		}
		return reply;
	}
	
	/**
	 * This method is used for commit and rollback operations.
	 * During commit, queued operations in all replicas must be confronted
	 * to the operations executed on the master to garantee that the
	 * master is not byzantine. During rollback the same happens. In cases
	 * of error messages when the transaction has to be aborted, the error
	 * has to be reproduced in the other replicas also to validate that
	 * the master is correct.
	 * @param m The commit or rollback request.
	 * @param clientId The client id used to get the queue of operations.
	 * @param opcode Commit or rollback.
	 * @return Success or error on commit or rollback.
	 */
	private Message processFinishTransaction(Message m, int clientId, int opcode) {
		logger.debug("--- Entering processFinishTransaction(). Client: " + clientId);
		ConnManager connManager = sm.getConnManager(clientId);
		Message reply = null;
		int opcodeError = OpcodeList.COMMIT_ERROR;
		int opcodeOK = OpcodeList.COMMIT_OK;
		if(opcode == OpcodeList.ROLLBACK_SEND) {
			opcodeError = OpcodeList.ROLLBACK_ERROR;
			opcodeOK = OpcodeList.ROLLBACK_OK;
		}
		if(this.master != this.replicaId || installingState) {
			FinishTransactionRequest finishReq = (FinishTransactionRequest) m.getContents();
			LinkedList<byte[]> resHashes = finishReq.getResHashes();
			LinkedList<Message> operations = finishReq.getOperations();
			if(operations.size() != resHashes.size()) {
				logger.error("Operations size differs from results: " + operations.size() + "," + resHashes.size());
//				System.out.println("Operations size differs from results: " + operations.size() + "," + resHashes.size());
			}
//			Queue<Message> ops = operations;
			LinkedList<byte[]> results = new LinkedList<byte[]>();
			connManager.setCommitingTransaction(true);
			for(Message msg: operations) {
				switch(msg.getOpcode()) {
				case OpcodeList.EXECUTE:
					reply = processExecute(msg,clientId);
					break;
				case OpcodeList.EXECUTE_BATCH:
					reply = processExecuteBatch(msg, clientId);
					break;
				case OpcodeList.EXECUTE_QUERY:
					reply = processExecuteQuery(msg, clientId);
					break;
				case OpcodeList.EXECUTE_UPDATE:
					reply = processExecuteUpdate(msg, clientId);
					break;
				default:
					reply = new Message(OpcodeList.COMMIT_ERROR, null, false, master);
				}
				Object replyContent = reply.getContents();
				byte[] replyBytes = null;
				replyBytes = TOMUtil.getBytes(String.valueOf(replyContent));
				byte[] replyHash = TOMUtil.computeHash(replyBytes);
				results.add(replyHash);
			}
			if(!compareHashes(resHashes, results)){
				logger.debug("## Results hashes don't match ##. Results.size(): " + results.size() + ", resHashes.size(): " + resHashes.size() + ", ops.size()" + operations.size());
				reply = new Message(opcodeError, null, true, master);
			} else {
				logger.debug("### Results hashes matches. Results.size(): " + results.size());
				reply = new Message(opcodeOK, null, false, master);
			}
		} else {
			reply = new Message(opcodeOK, null, false, master);
		}
		logger.debug("--- Exiting processFinishTransaction()");
		return reply;
	}
	
	/**
	 * Process a rollback request from the client. If it was triggered by
	 * a master changed ocurred before, the transactions in the old master
	 * must be rolled back. The transactions in the remaining replicas must
	 * only to be reset. In cases where this rollback wasn't triggered due
	 * to master changes occured before, it is called the normal behavior,
	 * where processFinishTransaction compares operations and responses hashes.
	 * @param m the RollBackRequest message
	 * @param clientId the client who sent the message
	 * @return ROLLBACK_OK or ROLLBACK_ERROR if there was any exception.
	 */
	protected Message processRollback(Message m, int clientId) {
		Message reply = null;
		RollbackRequest rollReq = (RollbackRequest)m.getContents();
		logger.debug("---- processRollback(). " + rollReq.getOldMaster() + " " + replicaId);
		if(rollReq.isMasterChanged()) {
			ConnManager connManager = sm.getConnManager(clientId);
			if(rollReq.getOldMaster() == replicaId) {
				try {
					logger.debug("Rolling back the transaction in the old master");
					connManager.rollback();
				} catch (SQLException sqle) {
					logger.error("processRollback() error", sqle);
//					sqle.printStackTrace();
					reply = new Message(OpcodeList.ROLLBACK_ERROR, null, false, master);
					return reply;
				}
			} else {
				logger.debug("Reseting transaction properties");
				connManager.reset();
			}
			reply = new Message(OpcodeList.ROLLBACK_OK, null, false, master);
		} else {
			FinishTransactionRequest finishReq = rollReq.getFinishReq();
			if(finishReq.getOperations() != null && finishReq.getOperations().size() > 0) {
				m = new Message(OpcodeList.ROLLBACK_SEND, rollReq.getFinishReq(), false);
				reply = processFinishTransaction(m, clientId, OpcodeList.ROLLBACK_SEND);
			}
			if(reply == null || reply.getOpcode() == OpcodeList.ROLLBACK_OK) {
				try {
					ConnManager connManager = sm.getConnManager(clientId);
					connManager.rollback();
					if(reply == null)
						reply = new Message(OpcodeList.ROLLBACK_OK, null, false, master);
				} catch (SQLException sqle) {
					logger.error("processRollback() error", sqle);
//					sqle.printStackTrace();
					reply = new Message(OpcodeList.ROLLBACK_ERROR, null, true, master);
				}
			}
		}
		return reply;
	}

	protected Message processAutoCommit(Message m, int clientId) {
		Message reply = null;
		boolean autoCommit = (Boolean)m.getContents();
		ConnManager connManager = sm.getConnManager(clientId);
		if(!autoCommit) {
			logger.debug("Transaction begin. ClientId: " + clientId + ", autocommit: " + autoCommit);
//			Printer.println("Transaction begin. ClientId: " + clientId, "vermelho");
		}
		try {
			connManager.setAutoCommit(autoCommit);
			reply = new Message(OpcodeList.COMMIT_AUTO_OK,null, true, master);
		} catch (SQLException sqle) {
			logger.error("processAutoCommit() error", sqle);
//			sqle.printStackTrace();
			reply = new Message(OpcodeList.COMMIT_AUTO_ERROR,null, true, master);
		}
		return reply;
	}

	/**
	 * Process a request from the client to change the master.
	 * Request to the session manager to abort all open read-write transactions.
	 * Change the current master to be the next one in a round robin fashion. 
	 * 
	 * @param m the message requesting a master change. It is useful to record the client
	 * id and prevent the same client to request multiple master changes 
	 * @return the message confirming that the transactions were aborted, the master changed
	 * and the id of the new master
	 */
	public Message processMasterChange(Message m) {
		Message reply;
		long secondsSinceLastMC = System.currentTimeMillis() - lastMasterChange;
//		Printer.println("processMasterChange() called by client " + m.getClientId(), "azul");
		logger.info("processMasterChange() called by client " + m.getClientId());
		lastMasterChange = System.currentTimeMillis();
		int oldMaster = m.getMaster();
		master = (oldMaster + 1) % currentView.getN();
		logger.info("processMastrChange(). Old: " + oldMaster + ". New: " + master);
//		Printer.println("processMastrChange(). Old: " + oldMaster + ". New: " + master, "azul");
		Message replyToLastRequest = executePreviousOps((MasterChangeRequest)m.getContents(), m.getClientId());
		if(replyToLastRequest != null)
			reply = new Message(OpcodeList.MASTER_CHANGE_OK, replyToLastRequest, false, master);
		else {
			reply = new Message(OpcodeList.MASTER_CHANGE_ERROR, null, false, master);
			logger.info("error on master change");
			System.out.println("error on master change");
		}
		return reply;
	}
	
	/**
	 * Execute the pending operations in open read write transactions in the new master.
	 * @return true if all operations were executed with success
	 */
	private Message executePreviousOps(MasterChangeRequest MCReq, int clientId) {
		LinkedList<Message> operations = MCReq.getOperations();
		LinkedList<byte[]> resHashes = MCReq.getResHashes();
		Message reply = null;
		if(resHashes == null && operations.size() > 1) {
			logger.error("resHashes is null but operations.size is greater than 1");
			return null;
		}
		else if(resHashes.size()+1 != operations.size()) {
			logger.error("Previous ops on master change. Number of operations differs from number of replies.");
			return null;
		} else {
			logger.info("resHashes.size: " + resHashes.size() + ", operations.size: " + operations.size());
		}
			
		for(int i = 0; i < operations.size(); i++) {
			Message msg = operations.get(i);
			switch(msg.getOpcode()) {
			case OpcodeList.EXECUTE:
				reply = processExecute(msg,clientId);
				break;
			case OpcodeList.EXECUTE_BATCH:
				reply = processExecuteBatch(msg, clientId);
				break;
			case OpcodeList.EXECUTE_QUERY:
				reply = processExecuteQuery(msg, clientId);
				break;
			case OpcodeList.EXECUTE_UPDATE:
				reply = processExecuteUpdate(msg, clientId);
				break;
			default:
				reply = null;
			}
			Object replyContent = reply.getContents();
			byte[] replyBytes = TOMUtil.getBytes(String.valueOf(replyContent));
			byte[] replyHash = TOMUtil.computeHash(replyBytes);
			if(i < resHashes.size()) {
				if(!Arrays.equals(replyHash, resHashes.get(i))) {
					return null;
				}
			} else {
				logger.info("executed operation after master change: " + msg.getContents());
			}
		}
		return reply;
	}
	
	public Message processSetFetchSize(Message m, int clientId) {
		Message reply = new Message(OpcodeList.UNKNOWN_OP, null, true, master);
		return reply;
	}
	public Message processSetMaxRows(Message m, int clientId) {
		Message reply = new Message(OpcodeList.UNKNOWN_OP, null, true, master);
		return reply;
	}

	protected Message processClose(Message m, int clientId) {
		logger.debug("processClose(): " + clientId);
		Message reply;
		try {
			sm.close(clientId);
			reply = new Message(OpcodeList.CLOSE_OK, null, true, master);
		} catch (SQLException sqle) {
			logger.error("processClose() error", sqle);
//			sqle.printStackTrace();
			reply = new Message(OpcodeList.CLOSE_ERROR, null, true, master);
		}
		return reply;
	}

	private Normalizer getNormalizer(String driver) {
		if(driver.equalsIgnoreCase("org.firebirdsql.jdbc.FBDriver"))
			return new FirebirdNormalizer();
		else
			return new NoNormalizer();
	}
	
	/**
	 * Gets from the session manager the open read-write transactions.
	 * @return A map with the client id and the list of operations executed
	 * in the transaction until the moment of the checkpoint.
	 */
	protected List<DBConnectionParams> getConnections() {
		return sm.getConnections();
	}
	
	/**
	 * This method is executed in the replica that requested the state transfer.
	 * It is called after the replica installed the dump and before execute the log
	 * of operations. It opens the connections to the database, creates the
	 * transactions and execute the operations that ran in the seeder before the
	 * checkpoint was taken. After running all the operations, the replica will
	 * have the state necessary to process the log and close the open transactions.
	 * @param transactions The map with client id and operations of the read-write
	 * transactions.
	 * @param connParams The parameters to log into the database and execute the
	 * operations. 
	 */
	protected void restoreOpenConnections(List<DBConnectionParams> connections, String database) {
		for(DBConnectionParams connection : connections) {
			logger.debug("----RESTORING CONNECTION FOR CLIENT " + connection.getClientId());
			sm.connect(connection.getClientId(), database, connection.getUser(), connection.getPassword());
		}
		logger.debug("----RESTORED CONNECTIONS");
	}
	
	
	
	/**
	 * Iterates through every item in the pending operations list and
	 * compute the hash value for the operation.
	 * @return a list with the hashes for each operation
	 */
	private LinkedList<byte[]> pendingOpsHashes(Queue<Message> queue) {
		LinkedList<byte[]> queueHashes = new LinkedList<byte[]>();
		for(Message m : queue) {
			byte[] hash = TOMUtil.computeHash(TOMUtil.getBytes(m.getContents()));
			queueHashes.add(hash);
		}
		return queueHashes;
	}

	private boolean compareHashes(LinkedList<byte[]> a, LinkedList<byte[]> b) {
		if(a == null)
			if(b ==  null)
				return true;
			else
				return false;
		if(a != null)
			if(b == null)
				return false;
			else {
				if(a.size() != b.size())
					return false;
				else {
					for(int i = 0; i < a.size(); i++) {
//						logger.debug("compareHashes " + i + ". a:" + Arrays.toString(a.get(i)) + ", b: " + Arrays.toString(b.get(i)));
						if(!(Arrays.equals(a.get(i), b.get(i))))
							return false;
					}
				}
			}
		return true;
	}
	
	protected void setCurrentView(View view) {
		currentView = view;
	}
	
	protected void setMaster(int master) {
		this.master = master;
	}
	
	protected int getMaster() {
		return master;
	}
	
	protected void setInstallingState(boolean installing) {
		this.installingState = installing;
	}
}
