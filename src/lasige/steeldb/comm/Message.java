package lasige.steeldb.comm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import javax.sql.RowSet;

import lasige.steeldb.jdbc.BFTRowSet;

public class Message implements Serializable {
	
	private static final long serialVersionUID = -137793546194980087L;
	
	private int opcode;
	private boolean unordered;
	private Object content; //content must also be Serializable
	private int statementOption;
	private RowSet rowSet;
	private int clientId;
	protected int operationId;
	protected final int master;

	public Message(int opcode, Object content, boolean unordered) {
		this.opcode = opcode;
		this.content = content;
		this.unordered = unordered;
		master = 0;
	}
	
	public Message(int opcode, Object content, boolean unordered, int master) {
		this.opcode = opcode;
		this.content = content;
		this.unordered = unordered;
		this.master = master;
	}
	
	public Message(int opcode, Object content, boolean unordered, int master, int statementOption) {
		this(opcode, content, unordered, master);
		this.statementOption = statementOption;
	}
	
	/**
	 * Returns the object as a array of bytes
	 * @return array of bytes corresponding to this object
	 * @throws IOException 
	 */
	public byte[] getBytes(){
		ByteArrayOutputStream bStream = new ByteArrayOutputStream();
		try {
			ObjectOutputStream oStream = new ObjectOutputStream( bStream );
			oStream.writeObject(this);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		byte[] byteVal = bStream.toByteArray();

		return byteVal;
	}
		
	/**
	 * Returns the opcode of the message
	 * @return integer corresponding to this message opcode
	 */
	public int getOpcode() {
		return this.opcode;
	}
	
	/**
	 * Returns this message contents as an Object
	 * @return the contents of this message. Can be null.
	 */
	public Object getContents() {
		return this.content;
	}

	/**
	 * Is is message to be read-only by the protocol?
	 * @return true if it is read-only, false otherwise
	 */
	public boolean isUnordered() {
		return this.unordered;
	}
	
	public static Message getMessage(byte[] bytes) {
		Message m = null;
		try {
			ByteArrayInputStream bStream = new ByteArrayInputStream(bytes);
			ObjectInputStream oStream = new ObjectInputStream(bStream);
			m = (Message)oStream.readObject();
			Object obj = m.getContents();
			if(obj instanceof BFTRowSet) {
				((BFTRowSet)obj).loadResourceBundle();
			}
			bStream.close();
			oStream.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return m;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("{opcode: " + opcode +", ");
		if(content == null) {
			sb.append("content: NULL, ");
		} else {
			sb.append("content: " + content.toString() + ", ");
		}
		sb.append("Unordered: " + Boolean.toString(unordered) + " }");
		return sb.toString();
		
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((content == null) ? 0 : content.hashCode());
		result = prime * result + opcode;
		result = prime * result + (unordered ? 1231 : 1237);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if(!(obj instanceof Message))
			return false;
		Message other = (Message) obj;
		if (content == null) {
			if (other.content != null)
				return false;
		} else if (!content.equals(other.content))
			return false;
		if (opcode != other.opcode)
			return false;
		if (unordered != other.unordered)
			return false;
		return true;
	}

	public int getStatementOption() {
		return this.statementOption;
	}

	public void setStatementOption(int statementOption) {
		this.statementOption = statementOption;
	}

	public RowSet getRowSet() {
		return rowSet;
	}

	public void setRowset(RowSet rowSet) {
		this.rowSet = rowSet;
	}
	
	public void setClientId(int clientId) throws Exception {
		if(this.clientId > 0)
			throw new Exception("Client id already set");
		this.clientId = clientId;
	}
	
	public int getClientId() {
		return clientId;
	}
	
	public int getOperationId() {
		return operationId;
	}
	
	public int getMaster() {
		return master;
	}
}

