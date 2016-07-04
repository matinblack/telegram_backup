package de.fabianonline.telegram_backup;

import com.github.badoualy.telegram.tl.api.*;
import com.github.badoualy.telegram.tl.core.TLVector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.sql.Array;

import de.fabianonline.telegram_backup.UserManager;
import de.fabianonline.telegram_backup.StickerConverter;

class Database {
	private Connection conn;
	private Statement stmt;
	private UserManager user_manager;
	
	public Database(UserManager user_manager) {
		this.user_manager = user_manager;
		try {
			Class.forName("org.sqlite.JDBC");
		} catch(ClassNotFoundException e) {
			CommandLineController.show_error("Could not load jdbc-sqlite class.");
		}
		
		String path = "jdbc:sqlite:" +
			user_manager.getFileBase() +
			Config.FILE_NAME_DB;
		
		try {
			conn = DriverManager.getConnection(path);
			stmt = conn.createStatement();
		} catch (SQLException e) {
			CommandLineController.show_error("Could not connect to SQLITE database.");
		}
		
		this.init();
	}
	
	private void init() {
		try {
			int version;
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='database_versions'");
			rs.next();
			if (rs.getInt(1)==0) {
				version = 0;
			} else {
				rs.close();
				rs = stmt.executeQuery("SELECT MAX(version) FROM database_versions");
				rs.next();
				version = rs.getInt(1);
				rs.close();
			}
			System.out.println("Database version: " + version);
			
			if (version==0) {
				System.out.println("  Updating to version 1...");
				stmt.executeUpdate("CREATE TABLE messages ("
						+ "id INTEGER PRIMARY KEY ASC, "
						+ "dialog_id INTEGER, "
						+ "to_id INTEGER, "
						+ "from_id INTEGER, "
						+ "from_type TEXT, "
						+ "text TEXT, "
						+ "time TEXT, "
						+ "has_media BOOLEAN, "
						+ "sticker TEXT, "
						+ "data BLOB,"
						+ "type TEXT)");
				stmt.executeUpdate("CREATE TABLE dialogs ("
						+ "id INTEGER PRIMARY KEY ASC, "
						+ "name TEXT, "
						+ "type TEXT)");
				stmt.executeUpdate("CREATE TABLE people ("
						+ "id INTEGER PRIMARY KEY ASC, "
						+ "first_name TEXT, "
						+ "last_name TEXT, "
						+ "username TEXT, "
						+ "type TEXT)");
				stmt.executeUpdate("CREATE TABLE database_versions ("
						+ "version INTEGER)");
				stmt.executeUpdate("INSERT INTO database_versions (version) VALUES (1)");
				version = 1;
			}
			if (version==1) {
				System.out.println("  Updating to version 2...");
				stmt.executeUpdate("ALTER TABLE people RENAME TO 'users'");
				stmt.executeUpdate("ALTER TABLE users ADD COLUMN phone TEXT");
				stmt.executeUpdate("INSERT INTO database_versions (version) VALUES (2)");
				version = 2;
			}
			
			System.out.println("Database is ready.");
		} catch (SQLException e) {
			System.out.println(e.getSQLState());
			e.printStackTrace();
		}
	}
	
	public int getTopMessageID() {
		try {
			ResultSet rs = stmt.executeQuery("SELECT MAX(id) FROM messages");
			rs.next();
			return rs.getInt(1);
		} catch (SQLException e) {
			return 0;
		}
	}
	
	public int getMessageCount() {
		try {
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM messages");
			rs.next();
			return rs.getInt(1);
		} catch (SQLException e) {
			throw new RuntimeException("Could not get count of messages.");
		}
	}
	
	public LinkedList<Integer> getMissingIDs() {
		try {
			LinkedList<Integer> missing = new LinkedList<Integer>();
			int max = getTopMessageID();
			ResultSet rs = stmt.executeQuery("SELECT id FROM messages ORDER BY id");
			rs.next();
			int id=rs.getInt(1);
			for (int i=1; i<=max; i++) {
				if (i==id) {
					rs.next();
					if (rs.isClosed()) {
						id = Integer.MAX_VALUE;
					} else {
						id=rs.getInt(1);
					}
				} else if (i<id) {
					missing.add(i);
				}
			}
			return missing;
		} catch(SQLException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not get list of ids.");
		}
	}
	
	public void saveMessages(TLVector<TLAbsMessage> all) {
		try {
			PreparedStatement ps = conn.prepareStatement(
				"INSERT OR REPLACE INTO messages " +
				"(id, dialog_id, from_id, from_type, text, time, has_media, data, sticker, type) " +
				"VALUES " +
				"(?,  ?,         ?,       ?,         ?,    ?,    ?,         ?,    ?,       ?)");
			PreparedStatement ps_insert_or_ignore = conn.prepareStatement(
				"INSERT OR IGNORE INTO messages " +
				"(id, dialog_id, from_id, from_type, text, time, has_media, data, sticker, type) " +
				"VALUES " +
				"(?,  ?,         ?,       ?,         ?,    ?,    ?,         ?,    ?,       ?)");
			for (TLAbsMessage abs : all) {
				if (abs instanceof TLMessage) {
					TLMessage msg = (TLMessage) abs;
					ps.setInt(1, msg.getId());
					TLAbsPeer peer = msg.getToId();
					if (peer instanceof TLPeerChat) {
						ps.setInt(2, ((TLPeerChat)peer).getChatId());
						ps.setString(4, "chat");
					} else if (peer instanceof TLPeerChannel) {
						ps.setInt(2, ((TLPeerChannel)peer).getChannelId());
						ps.setString(4, "channel");
					} else if (peer instanceof TLPeerUser) {
						int id = ((TLPeerUser)peer).getUserId();
						if (id==this.user_manager.getUser().getId()) {
							id = msg.getFromId();
						}
						ps.setInt(2, id);
						ps.setString(4, "user");
					} else {
						throw new RuntimeException("Unexpected Peer type: " + peer.getClass().getName());
					}
					ps.setInt(3, msg.getFromId());
					String text = msg.getMessage();
					if ((text==null || text.equals("")) && msg.getMedia()!=null) {
						if (msg.getMedia() instanceof TLMessageMediaDocument) {
							text = ((TLMessageMediaDocument)msg.getMedia()).getCaption();
						} else if (msg.getMedia() instanceof TLMessageMediaPhoto) {
							text = ((TLMessageMediaPhoto)msg.getMedia()).getCaption();
						}
					}
					ps.setString(5, text);
					ps.setString(6, ""+msg.getDate());
					ps.setBoolean(7, msg.getMedia() != null);
					ByteArrayOutputStream stream = new ByteArrayOutputStream();
					msg.serializeBody(stream);
					ps.setBytes(8, stream.toByteArray());
					String sticker = null;
					if (msg.getMedia()!=null && msg.getMedia() instanceof TLMessageMediaDocument) {
						TLMessageMediaDocument md = (TLMessageMediaDocument)msg.getMedia();
						if (md.getDocument() instanceof TLDocument) {
							for (TLAbsDocumentAttribute attr : ((TLDocument)md.getDocument()).getAttributes()) {
								if (attr instanceof TLDocumentAttributeSticker) {
									sticker = StickerConverter.makeFilename((TLDocumentAttributeSticker)attr);
									break;
								}
							}
						}
					}
					if (sticker != null) {
						ps.setString(9, sticker);
					} else {
						ps.setNull(9, Types.VARCHAR);
					}
					ps.setString(10, "message");
					ps.addBatch();
				} else if (abs instanceof TLMessageService) {
					ps_insert_or_ignore.setInt(1, abs.getId());
					ps_insert_or_ignore.setNull(2, Types.INTEGER);
					ps_insert_or_ignore.setNull(3, Types.INTEGER);
					ps_insert_or_ignore.setNull(4, Types.VARCHAR);
					ps_insert_or_ignore.setNull(5, Types.VARCHAR);
					ps_insert_or_ignore.setNull(6, Types.INTEGER);
					ps_insert_or_ignore.setNull(7, Types.BOOLEAN);
					ps_insert_or_ignore.setNull(8, Types.BLOB);
					ps_insert_or_ignore.setNull(9, Types.VARCHAR);
					ps_insert_or_ignore.setString(10, "service_message");
					ps_insert_or_ignore.addBatch();
				} else if (abs instanceof TLMessageEmpty) {
					TLMessageEmpty msg = (TLMessageEmpty) abs;
					ps_insert_or_ignore.setInt(1, msg.getId());
					ps_insert_or_ignore.setNull(2, Types.INTEGER);
					ps_insert_or_ignore.setNull(3, Types.INTEGER);
					ps_insert_or_ignore.setNull(4, Types.VARCHAR);
					ps_insert_or_ignore.setNull(5, Types.VARCHAR);
					ps_insert_or_ignore.setNull(6, Types.INTEGER);
					ps_insert_or_ignore.setNull(7, Types.BOOLEAN);
					ps_insert_or_ignore.setNull(8, Types.BLOB);
					ps_insert_or_ignore.setNull(9, Types.VARCHAR);
					ps_insert_or_ignore.setString(10, "empty_message");
					ps_insert_or_ignore.addBatch();
				} else {
					throw new RuntimeException("Unexpected Message type: " + abs.getClass().getName());
				}
			}
			conn.setAutoCommit(false);
			ps.executeBatch();
			ps.clearBatch();
			ps_insert_or_ignore.executeBatch();
			ps_insert_or_ignore.clearBatch();
			conn.commit();
			conn.setAutoCommit(true);
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Exception shown above happened.");
		}
	}

	public void saveChats(TLVector<TLAbsChat> all) {
		try {
			PreparedStatement ps_insert_or_replace = conn.prepareStatement(
				"INSERT OR REPLACE INTO dialogs " +
					"(id, name, type) "+
					"VALUES " +
					"(?,  ?,    ?)");
			PreparedStatement ps_insert_or_ignore = conn.prepareStatement(
				"INSERT OR IGNORE INTO dialogs " +
					"(id, name, type) "+
					"VALUES " +
					"(?,  ?,    ?)");

			for(TLAbsChat abs : all) {
				ps_insert_or_replace.setInt(1, abs.getId());
				ps_insert_or_ignore.setInt(1, abs.getId());
				if (abs instanceof TLChatEmpty) {
					ps_insert_or_ignore.setNull(2, Types.VARCHAR);
					ps_insert_or_ignore.setString(3, "empty_chat");
					ps_insert_or_ignore.addBatch();
				} else if (abs instanceof TLChatForbidden) {
					ps_insert_or_replace.setString(2, ((TLChatForbidden)abs).getTitle());
					ps_insert_or_replace.setString(3, "chat");
					ps_insert_or_replace.addBatch();
				} else if (abs instanceof TLChannelForbidden) {
					ps_insert_or_replace.setString(2, ((TLChannelForbidden)abs).getTitle());
					ps_insert_or_replace.setString(3, "channel");
					ps_insert_or_replace.addBatch();
				} else if (abs instanceof TLChat) {
					ps_insert_or_replace.setString(2, ((TLChat) abs).getTitle());
					ps_insert_or_replace.setString(3, "chat");
					ps_insert_or_replace.addBatch();
				} else if (abs instanceof TLChannel) {
					ps_insert_or_replace.setString(2, ((TLChannel)abs).getTitle());
					ps_insert_or_replace.setString(3, "channel");
					ps_insert_or_replace.addBatch();
				} else {
					throw new RuntimeException("Unexpected " + abs.getClass().getName());
				}
			}
			conn.setAutoCommit(false);
			ps_insert_or_ignore.executeBatch();
			ps_insert_or_ignore.clearBatch();
			ps_insert_or_replace.executeBatch();
			ps_insert_or_replace.clearBatch();
			conn.commit();
			conn.setAutoCommit(true);
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Exception shown above happened.");
		}
	}

	public void saveUsers(TLVector<TLAbsUser> all) {
		try {
			PreparedStatement ps_insert_or_replace = conn.prepareStatement(
				"INSERT OR REPLACE INTO users " +
					"(id, first_name, last_name, username, type, phone) " +
					"VALUES " +
					"(?,  ?,          ?,         ?,        ?,    ?)");
			PreparedStatement ps_insert_or_ignore = conn.prepareStatement(
				"INSERT OR IGNORE INTO users " +
					"(id, first_name, last_name, username, type, phone) " +
					"VALUES " +
					"(?,  ?,          ?,         ?,        ?,    ?)");
			for (TLAbsUser abs : all) {
				if (abs instanceof TLUser) {
					TLUser user = (TLUser)abs;
					ps_insert_or_replace.setInt(1, user.getId());
					ps_insert_or_replace.setString(2, user.getFirstName());
					ps_insert_or_replace.setString(3, user.getLastName());
					ps_insert_or_replace.setString(4, user.getUsername());
					ps_insert_or_replace.setString(5, "user");
					ps_insert_or_replace.setString(6, user.getPhone());
					ps_insert_or_replace.addBatch();
				} else if (abs instanceof TLUserEmpty) {
					ps_insert_or_ignore.setInt(1, abs.getId());
					ps_insert_or_ignore.setNull(2, Types.VARCHAR);
					ps_insert_or_ignore.setNull(3, Types.VARCHAR);
					ps_insert_or_ignore.setNull(4, Types.VARCHAR);
					ps_insert_or_ignore.setString(5, "empty_user");
					ps_insert_or_ignore.setNull(6, Types.VARCHAR);
					ps_insert_or_ignore.addBatch();
				} else {
					throw new RuntimeException("Unexpected " + abs.getClass().getName());
				}
			}
			conn.setAutoCommit(false);
			ps_insert_or_ignore.executeBatch();
			ps_insert_or_ignore.clearBatch();
			ps_insert_or_replace.executeBatch();
			ps_insert_or_replace.clearBatch();
			conn.commit();
			conn.setAutoCommit(true);
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Exception shown above happened.");
		}
	}
	
	public LinkedList<TLMessage> getMessagesWithMedia() {
		try {
			LinkedList<TLMessage> list = new LinkedList<TLMessage>();
			ResultSet rs = stmt.executeQuery("SELECT data FROM messages WHERE has_media=1");
			while (rs.next()) {
				ByteArrayInputStream stream = new ByteArrayInputStream(rs.getBytes(1));
				TLMessage msg = new TLMessage();
				msg.deserializeBody(stream, TLApiContext.getInstance());
				list.add(msg);
			}
			rs.close();
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Exception occured. See above.");
		}
	}
}
