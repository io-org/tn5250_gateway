package org.as400.tn5250.mfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/*
TODO:
- we need add fire some events: 
	IP ask to connect (done). String eventType = "ask_for_connect"
	username asc to sign-in (done). String eventType = "ask_for_sign_in"
	IP connected. String eventType = "connected"
	username signed-in. String eventType = "signed_in"
	session timeout exceeded. String eventType = "session_timeout_exceeded"
	idle timeout exceeded. String eventType = "idle_timeout_exceeded"
	as400 host closed connection. String eventType = "host_closed connection"
*/
public class Tn5250Connection implements Runnable {
	public static final String MAX_SESSION_TIMEOUT_MINUTES = "max_session_timeout_minutes"; // Session length limit in minutes
	public static final String MAX_IDLE_TIMEOUT_MINUTES = "max_idle_timeout_minutes"; // Idle timeout Limit in minutes

	private final String connectionId;
	public final Socket tn5250;
	private final String as400Host;
	private final int as400Port;
	private final boolean as400Tls;
	private Map<String, int[]> fieldsToExtract;
	private String screens_dir;
	private final BlockingQueue<Map<String, Object>> outbox;
	public final BlockingQueue<Map<String, Object>> inboxConnect = new LinkedBlockingQueue<>();
	public final BlockingQueue<Map<String, Object>> inboxSignIn = new LinkedBlockingQueue<>();

	public static final String REZERVED_FIELD_TERMINAL_MODEL = "terminal_model";
	public static final String REZERVED_FIELD_CCSID = "ccsid";
	public static final String REZERVED_FIELD_DEVNAME = "device_name";
	public static final String REZERVED_FIELD_USERNAME = "username";

	/* SIGN-IN Screen Layout */
	public static final String KEY_FLD_VALUE = "value";
	public static final String KEY_FLD_ROW = "row";
	public static final String KEY_FLD_COL = "col";
	public static final String KEY_FLD_NAME = "name";

	/* Request */
	public static final String KEY_REQUEST_REQUEST_TYPE = "type";

	public static final String VAL_REQUEST_TYPE_CONNECT = "connect";
	public static final String VAL_REQUEST_TYPE_SIGN_IN = "sign_in";

	public static final String KEY_REQUEST_CONNECTION_ID = "connection_id";
	public static final String KEY_REQUEST_SESSION_ID = "session_id";
	public static final String KEY_REQUEST_TN5250_IP = "tn5250_ip";
	public static final String KEY_REQUEST_FIELDS = "fields";

	/* Response */
	public static final String KEY_RESPONSE_TYPE = "type";
	public static final String KEY_RESPONSE_ALLOW = "allow";
	public static final String KEY_RESPONSE_MESSAGE = "message";

	private static final int UNLIMITED_TIMEOUT_MINUTES = Integer.MAX_VALUE; // Unlimited

	private static final int DEFAULT_MAX_SESSION_TIMEOUT_MINUTES = UNLIMITED_TIMEOUT_MINUTES; // Unlimited
	private static final int DEFAULT_MAX_IDLE_TIMEOUT_MINUTES = UNLIMITED_TIMEOUT_MINUTES; // Unlimited

	private volatile int max_session_timeout_minutes = DEFAULT_MAX_SESSION_TIMEOUT_MINUTES;
	private volatile int max_idle_timeout_minutes = DEFAULT_MAX_IDLE_TIMEOUT_MINUTES;

	private boolean signInScreenDetected;
	private boolean firstSignInScreenDetected;

	private List<Map<String, Object>> signInScreenLayout;

	private Charset ebcdicCharset;
	/*
	 * TIMEOUT_CHECK_INTERVAL_MS sets each socket’s read timeout to one second. When
	 * a read times out, the forwarding loop checks whether the session or idle
	 * limit has expired.
	 * Without periodic wakeups, a quiet connection could block in read()
	 * indefinitely and never check its limits.
	 * The tradeoff is a periodic wakeup while the connection is idle.
	 */
	private static final int TIMEOUT_CHECK_INTERVAL_MS = 1000;
	private static final long TN5250_NEGOTIATION_TIMEOUT_MS = 600000;
	private volatile long sessionStartedAtNanos;
	private volatile long lastActivityAtNanos;
	private String sessionId;
	private final TelnetClientInfo telnetClientInfo = new TelnetClientInfo();

	private boolean connectVerified;
	private boolean signinVerified;

	public Tn5250Connection( //
			String connectionId,
			Socket tn5250, //
			String as400Host, //
			int as400Port, //
			boolean as400Tls, //
			List<Map<String, Object>> signInScreenLayout,
			BlockingQueue<Map<String, Object>> outbox,
			String screens_dir) {
		this.connectionId = connectionId;
		this.tn5250 = tn5250;
		this.as400Host = as400Host;
		this.as400Port = as400Port;
		this.as400Tls = as400Tls;
		this.outbox = outbox;
		this.signInScreenLayout = signInScreenLayout;
		this.fieldsToExtract = new LinkedHashMap<>();
		this.screens_dir = screens_dir;
		for (Map<String, Object> m : signInScreenLayout) {
			String fieldName = (String) m.get(KEY_FLD_NAME);
			if (fieldName != null) {
				int row = (Integer) m.get(KEY_FLD_ROW);
				int col = (Integer) m.get(KEY_FLD_COL);
				fieldsToExtract.put(fieldName, new int[] { row, col });
			}
		}
	}

	private Map<String, Object> verify(Map<String, Object> request, BlockingQueue<Map<String, Object>> inbox) {
		try {
			outbox.put(request);
			return inbox.take();
		} catch (InterruptedException e) {
			e.printStackTrace();
			Thread.currentThread().interrupt();
			return Collections.EMPTY_MAP;
		}
	}

	@Override
	public void run() {
		sessionStartedAtNanos = System.nanoTime();
		lastActivityAtNanos = sessionStartedAtNanos;
		SocketFactory socketFactory = as400Tls ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();

		try (Socket as400 = socketFactory.createSocket(as400Host, as400Port)) {
			// Explicitly start the handshake (or let it trigger automatically on first
			// read/write)
			// target.startHandshake();

			// try (Socket target = new Socket(TARGET_HOST, TARGET_PORT)) {
			tn5250.setTcpNoDelay(true);
			as400.setTcpNoDelay(true);
			tn5250.setSoTimeout(TIMEOUT_CHECK_INTERVAL_MS);
			as400.setSoTimeout(TIMEOUT_CHECK_INTERVAL_MS);
			System.err.println("Connected to AS/400");

			Thread clientToHost = new Thread(
					() -> forwardClientToHost(tn5250, as400, fieldsToExtract), "Client-To-Host");
			Thread hostToClient = new Thread(
					() -> forwardHostToClient(as400, tn5250), "Host-To-Client");

			clientToHost.start();
			hostToClient.start();
			clientToHost.join();
			hostToClient.join();
		} catch (Exception e) {
			System.err.println("TCP/IP Session error: " + e.getMessage());
		} finally {
			try {
				tn5250.close();
			} catch (IOException ignored) {
			}
			System.err.println("TCP/IP Session closed");
		}
	}

	private void forwardClientToHost(Socket tn5250, Socket as400, Map<String, int[]> fieldsToExtract) {
		try {
			InputStream in = tn5250.getInputStream();
			OutputStream out = as400.getOutputStream();

			byte[] buffer = new byte[8192];
			int count;

			while (true) {
				try {
					count = in.read(buffer);
				} catch (SocketTimeoutException e) {
					if ((System.nanoTime() - sessionStartedAtNanos) / 1_000_000L >= TN5250_NEGOTIATION_TIMEOUT_MS) {
						System.err.println("Closing client that did not send a TN5250 data record");
						try {
							tn5250.close();
							as400.close();
						} catch (IOException ignored) {
						}
						return;
					}
					checkTimeouts(tn5250, as400);
					continue;
				}
				if (count == -1)
					break;
				lastActivityAtNanos = System.nanoTime();
				checkTimeouts(tn5250, as400);
				telnetClientInfo.inspect(buffer, count);
				int ccsid = telnetClientInfo.getCcsid();

				if (signInScreenDetected) {
					Map<String /* field name */, String /* field value */> fieldNameValueMap = extractAllInputFieldsOnEnter(buffer, count, ccsid, ebcdicCharset, fieldsToExtract);
					String username = fieldNameValueMap.get(REZERVED_FIELD_USERNAME);
					if (username == null) {
						try {
							as400.close();
							tn5250.close();
							System.err.println("Connection aborted: Bad username.");
						} catch (IOException e) {
							e.printStackTrace();
						}
						return;
					}
					if (!signinVerified) {
						String tn5250Ip = tn5250.getInetAddress().getHostAddress();
						// send verify request
						// wait while answer true or false or timeout come
						Map<String, Object> request = new LinkedHashMap<>();
						request.put(KEY_REQUEST_CONNECTION_ID, connectionId);
						request.put(KEY_REQUEST_SESSION_ID, sessionId);
						request.put(KEY_REQUEST_REQUEST_TYPE, VAL_REQUEST_TYPE_SIGN_IN);
						request.put(KEY_REQUEST_TN5250_IP, tn5250Ip);
						request.put(KEY_REQUEST_FIELDS, fieldNameValueMap);

						System.err.println("Verify sign-in...");
						Map<String, Object> response = verify(request, inboxSignIn);
						String connectionIdFromResponse = (String) response.get(KEY_REQUEST_CONNECTION_ID);
						String sessionIdFromResponse = (String) response.get(KEY_REQUEST_SESSION_ID);
						Boolean allow = (Boolean) response.get(KEY_RESPONSE_ALLOW);
						if ( //
						(connectionIdFromResponse == null || !connectionIdFromResponse.equals(connectionId)) || //
								(sessionIdFromResponse == null || !sessionIdFromResponse.equals(sessionId)) || //
								(allow == null || !allow)) {
							try {
								tn5250.close();
								as400.close();
								String message = (String) response.get(KEY_RESPONSE_MESSAGE);
								System.err.println(String.format("Session aborted: Client failed verification against active security policies.\nMessage: %s", message));
							} catch (IOException e) {
								e.printStackTrace();
							}
							signinVerified = false;
							return;
						}
						signinVerified = true;
						Integer obj_max_session_timeout_minutes = (Integer) response.get(MAX_SESSION_TIMEOUT_MINUTES);
						if (obj_max_session_timeout_minutes != null) {
							max_session_timeout_minutes = Math.min(max_session_timeout_minutes, obj_max_session_timeout_minutes.intValue());
						}
						Integer obj_max_idle_timeout_minutes = (Integer) response.get(MAX_IDLE_TIMEOUT_MINUTES);
						if (obj_max_idle_timeout_minutes != null) {
							max_idle_timeout_minutes = Math.min(max_idle_timeout_minutes, obj_max_idle_timeout_minutes.intValue());
						}
					}
				}
				out.write(buffer, 0, count);
				out.flush();
			}
		} catch (IOException e) {
			e.printStackTrace();
			// Connection terminated
		} finally {
			try {
				as400.shutdownOutput();
			} catch (IOException ignored) {
			}
		}
	}

	private void forwardHostToClient(Socket as400, Socket tn5250) {
		String tn5250Ip = tn5250.getInetAddress().getHostAddress();
		int num = 0;
		Tn5250ScreenDecoder screenDecoder = null;
		Charset screenDecoderCharset = null;
		try {
			InputStream in = as400.getInputStream();
			OutputStream out = tn5250.getOutputStream();

			byte[] buffer = new byte[8192];
			int count;

			while (true) {
				try {
					count = in.read(buffer);
				} catch (SocketTimeoutException e) {
					checkTimeouts(tn5250, as400);
					continue;
				}
				if (count == -1)
					break;

				telnetClientInfo.inspect(buffer, count);
				int ccsid = telnetClientInfo.getCcsid();
				String devname = telnetClientInfo.getDevname();
				String terminalModel = telnetClientInfo.getTerminalModel();
				String username = telnetClientInfo.getUsername();

				System.err.println(String.format("Device:         %s", devname));
				System.err.println(String.format("Terminal Model: %s", terminalModel));
				System.err.println(String.format("CCSID:          %s", ccsid));
				System.err.println(String.format("Username:       %s", username));

				if (num > (as400Tls ? 4 : 3) && !firstSignInScreenDetected) {
					try {
						as400.close();
						tn5250.close();
						System.err.println("Connection aborted: Bad client.");
					} catch (IOException e) {
						e.printStackTrace();
					}
					return;
				}
				num++;
				if (ccsid != -1) {
					String javaCharsetName = com.ibm.as400.access.NLS.ccsidToEncoding(ccsid); // Returns "Cp037" or "IBM037"
					ebcdicCharset = Charset.forName(javaCharsetName);
				}

				if (!connectVerified) {
					Map<String, Object> request = new LinkedHashMap<>();
					request.put(KEY_REQUEST_CONNECTION_ID, connectionId);
					request.put(KEY_REQUEST_REQUEST_TYPE, VAL_REQUEST_TYPE_CONNECT);
					request.put(KEY_REQUEST_TN5250_IP, tn5250Ip);
					System.err.println("Verify connect...");
					Map<String, Object> response = verify(request, inboxConnect);
					String connectionIdFromResponse = (String) response.get(KEY_REQUEST_CONNECTION_ID);
					String sessionIdFromResponse = (String) response.get(KEY_REQUEST_SESSION_ID);
					Boolean allow = (Boolean) response.get(KEY_RESPONSE_ALLOW);
					if ( //
					(connectionIdFromResponse == null || !connectionIdFromResponse.equals(connectionId)) || //
							(sessionIdFromResponse != null) || //
							(allow == null || !allow)) {
						try {
							as400.close();
							tn5250.close();
							String message = (String) response.get(KEY_RESPONSE_MESSAGE);
							System.err.println(String.format("Connection aborted: Client failed verification against active security policies.\nMessage: %s", message));
						} catch (IOException e) {
							e.printStackTrace();
						}
						connectVerified = false;
						return;
					}
					connectVerified = true;

					Integer obj_max_session_timeout_min = (Integer) response.get(MAX_SESSION_TIMEOUT_MINUTES);
					if (obj_max_session_timeout_min != null) {
						max_session_timeout_minutes = Math.min(max_session_timeout_minutes, obj_max_session_timeout_min.intValue());
					}
					Integer obj_max_idle_timeout_min = (Integer) response.get(MAX_IDLE_TIMEOUT_MINUTES);
					if (obj_max_idle_timeout_min != null) {
						max_idle_timeout_minutes = Math.min(max_idle_timeout_minutes, obj_max_idle_timeout_min.intValue());
					}
				}

				if (screenDecoder == null || screenDecoderCharset != ebcdicCharset) {
					screenDecoder = new Tn5250ScreenDecoder(ebcdicCharset);
					screenDecoderCharset = ebcdicCharset;
				}

				List<String> screens = screenDecoder.accept(buffer, count);
				for (String screen : screens) {
					if (screen.trim().isEmpty()) {
						continue;
					}
					signInScreenDetected = isMatchSignInScreen(screen, signInScreenLayout);
					if (signInScreenDetected) {
						sessionId = UUID.randomUUID().toString();
						signinVerified = false;
					}
					if (signInScreenDetected && firstSignInScreenDetected == false) {
						firstSignInScreenDetected = true;
					}

					System.err.println(screen);
					if (screens_dir != null) {
						String timestamp = Utils.getTimestampForFileName();
						Path scr = Path.of(screens_dir, "connection-" + connectionId, "session-" + sessionId, "screen-" + timestamp + ".txt");
						Path parent = scr.getParent();
						Files.createDirectories(parent);
						Files.writeString(scr, screen);
					}
				}

				lastActivityAtNanos = System.nanoTime();
				checkTimeouts(tn5250, as400);
				out.write(buffer, 0, count);
				out.flush();
			}
		} catch (IOException e) {
			// Connection terminated
		} finally {
			try {
				tn5250.shutdownOutput();
			} catch (IOException ignored) {
			}
		}
	}

	private void checkTimeouts(Socket tn5250, Socket as400) throws IOException {
		long now = System.nanoTime();
		if (elapsedMinutes(sessionStartedAtNanos, now) >= max_session_timeout_minutes) {
			closeForTimeout(tn5250, as400, "Maximum session duration exceeded");
		}
		if (elapsedMinutes(lastActivityAtNanos, now) >= max_idle_timeout_minutes) {
			closeForTimeout(tn5250, as400, "Maximum idle duration exceeded");
		}
	}

	private static long elapsedMinutes(long sinceNanos, long nowNanos) {
		return (nowNanos - sinceNanos) / 60_000_000_000L;
	}

	private static void closeForTimeout(Socket tn5250, Socket as400, String reason) throws IOException {
		System.err.println(reason);
		try {
			tn5250.close();
		} finally {
			as400.close();
		}
		throw new IOException(reason);
	}

	/**
	 * Scans the full client inbound packet for an Enter event, maps all SBA/SF
	 * field blocks sequentially, and decodes every text data fragment out of
	 * EBCDIC.
	 */
	private Map<String /* field name */, String /* field value */> extractAllInputFieldsOnEnter(byte[] buffer, int len, int ccsid, Charset ebcdicCharset, Map<String, int[]> fieldsToExtract) {
		if (ccsid == -1)
			return Collections.EMPTY_MAP;
		Map<String /* field name */, String /* field value */> fieldNameValueMap = new LinkedHashMap<>();
		// String javaCharsetName = com.ibm.as400.access.NLS.ccsidToEncoding(ccsid); //
		// Returns "Cp037"
		// Charset EBCDIC_CHARSET = Charset.forName(javaCharsetName);
		// Charset EBCDIC_CHARSET = Charset.forName(as400Charset);
		int aidIndex = -1;

		// Find the Enter Key Attention Identifier (0xF1)
		for (int i = 0; i < len; i++) {
			if ((buffer[i] & 0xFF) == 0xF1) {
				aidIndex = i;
				break;
			}
		}

		// Not a screen submission packet
		if (aidIndex == -1) {
			return Collections.EMPTY_MAP;
		}

		// List<String> discoveredFields = new ArrayList<>();
		int idx = 0;

		// Skip baseline row/col cursor byte placements if bounds permit
		if (idx + 2 < len) {
			idx += 2;
		}

		// Loop through the entire data stream to capture all input blocks sequential
		// order
		while (idx < len) {
			int currentByte = buffer[idx] & 0xFF;

			// 0x11 = Set Buffer Address (followed by 1 byte Row, 1 byte Col)
			// 0x1D = Start Field / Field Attribute command standard byte
			int row = -1;
			int col = -1;
			if (currentByte == 0x11 || currentByte == 0x1D) {

				if (currentByte == 0x11) {
					if (idx + 2 < len) {
						// In 5250 inbound streams, Byte 1 is Row, Byte 2 is Col
						row = buffer[idx + 1] & 0xFF;
						col = (buffer[idx + 2] & 0xFF);
						// int col = (buffer[idx + 2] & 0xFF) + 1; // Convert 0-index to 1-index display
						// coordinate

						System.err.println("[Data Stream] Found field data block starting at Row: " + row + ", Col: " + col);
					}

					idx += 3; // Skip SBA command (1 byte) + Row (1 byte) + Col (1 byte)
				} else {
					idx += 1; // Skip the attribute command byte itself
				}

				int startText = idx;
				int textLen = 0;

				// Read text until hitting the next structural marker token sequence
				while (idx < len) {
					int b = buffer[idx] & 0xFF;
					if (b == 0x11 || b == 0x1D || b == 0xFF) {
						break;
					}
					textLen++;
					idx++;
				}

				String fieldName = searchField(row, col, fieldsToExtract);
				if (fieldName != null) {
					if (textLen > 0) {
						String decoded = new String(buffer, startText, textLen, ebcdicCharset).trim();
						// Keep non-empty entries (ignoring zero-length padding inputs)
						// if (!decoded.isEmpty()) {
						fieldNameValueMap.put(fieldName, decoded);
						// }
					}
				}
				// Continue immediately without double incrementing
				continue;
			}
			idx++;
		}
		return fieldNameValueMap;
	}

	private static String searchField(int row, int col, Map<String /* field name */, int[]> fieldsToExtract) {
		for (Map.Entry<String /* field name */, int[]> entry : fieldsToExtract.entrySet()) {
			String fieldName = entry.getKey();
			int[] rc = entry.getValue();
			int r = rc[0];
			int c = rc[1];
			if (r == row && c == col) {
				return fieldName;
			}
		}
		return null;
	}

	private static boolean isMatchSignInScreen(String screen, List<Map<String, Object>> signInScreenLayout) {
		String[] lines = screen.split("\n");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			for (Map<String, Object> m : signInScreenLayout) {
				String fieldValue = (String) m.get(KEY_FLD_VALUE);
				if (fieldValue == null)
					continue;
				int row = (Integer) m.get(KEY_FLD_ROW);
				if (row != i + 1)
					continue;
				int col = (Integer) m.get(KEY_FLD_COL);
				String val = (String) m.get(KEY_FLD_VALUE);
				String value = line.substring(col - 1, val.length());
				if (!val.equals(value)) {
					return false;
				}
			}
		}
		return true;

	}
}