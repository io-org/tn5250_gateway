package org.as400.tn5250.mfa;

import java.util.Set;

/** Extracts client identity values from Telnet subnegotiation without changing forwarded bytes. */
public final class TelnetClientInfo {
	private static final int DEFAULT_EBCDIC_CCSID = 37;
	
	private static final Set<String> TN5250_TERMINAL_TYPES = Set.of(
			"IBM-5555-C01", "IBM-5555-B01", "IBM-3477-FC", "IBM-3477-FG",
			"IBM-3180-2", "IBM-3179-2", "IBM-3196-A1", "IBM-5292-2",
			"IBM-5291-1", "IBM-5251-11");
	private static final int IAC = 255, SB = 250, SE = 240;
	private static final int TTYPE = 24, OLD_ENVIRON = 36, NEW_ENVIRON = 39;
	private static final int MAX_PENDING = 4096;
	private byte[] pending = new byte[0];
	private boolean terminalLogged;
	private boolean deviceLogged;
	private boolean ccsidLogged;
	private boolean usernameLogged;
	private String terminalModel;
	private String devname;
	private String username;
	private int ccsid = -1;
	private int maxRows = -1;
	private int maxColumns = -1;

	synchronized void inspect(byte[] bytes, int length) {
		int keep = Math.min(pending.length, MAX_PENDING);
		byte[] data = new byte[keep + length];
		System.arraycopy(pending, pending.length - keep, data, 0, keep);
		System.arraycopy(bytes, 0, data, keep, length);
		int cursor = 0;
		while (cursor + 4 < data.length) {
			if ((data[cursor] & 0xff) != IAC || (data[cursor + 1] & 0xff) != SB) { cursor++; continue; }
			int option = data[cursor + 2] & 0xff;
			int end = cursor + 3;
			while (end + 1 < data.length && !((data[end] & 0xff) == IAC && (data[end + 1] & 0xff) == SE)) end++;
			if (end + 1 >= data.length) break;
			if (option == TTYPE && !terminalLogged && end > cursor + 3 && (data[cursor + 3] & 0xff) == 0) {
				terminalModel = ascii(data, cursor + 4, end);
				if (!terminalModel.isEmpty()) {
					setMaximumDimensions(terminalModel);
					System.err.println("[TN5250] Terminal type / model: " + terminalModel);
					if (maxRows > 0) {
						System.err.println("[TN5250] Maximum screen size: " + maxRows + "x" + maxColumns);
					}
					terminalLogged = true;
				}
			} else if (option == OLD_ENVIRON || option == NEW_ENVIRON) {
				if (!usernameLogged) {
					username = findEnvironmentValue(data, cursor + 3, end, "USERNAME");
					if (username == null || username.isEmpty()) username = findEnvironmentValue(data, cursor + 3, end, "USER");
					if (username != null && !username.isEmpty()) {
						System.err.println("[TN5250] Client username: " + username);
						usernameLogged = true;
					}
				}
				if (!deviceLogged) {
					devname = findEnvironmentValue(data, cursor + 3, end, "DEVNAME");
					if (devname != null && !devname.isEmpty()) {
						System.err.println("[TN5250] Requested device name (DEVNAME): " + devname);
						deviceLogged = true;
					}
				}
				if (!ccsidLogged) {
					String ccsidStr = findEnvironmentValue(data, cursor + 3, end, "CODEPAGE");
					if (ccsidStr == null || ccsidStr.isEmpty())
						ccsidStr = findEnvironmentValue(data, cursor + 3, end, "CCSID");
					if (ccsidStr != null && !ccsidStr.isEmpty()) {
						ccsid = Integer.parseInt(ccsidStr);
						System.err.println("[TN5250] Code page / CCSID (client CODEPAGE): " + ccsid);
						ccsidLogged = true;
					}
				}
			}
			cursor = end + 2;
		}
		int remainder = Math.min(data.length - cursor, MAX_PENDING);
		pending = new byte[remainder];
		System.arraycopy(data, data.length - remainder, pending, 0, remainder);
	}

	/** Client environment value, e.g. DEVNAME or CODEPAGE, between VAR/USERVAR and VALUE markers. */
	private static String findEnvironmentValue(byte[] data, int start, int end, String requestedVariable) {
		String value = null;
		for (int i = start; i < end; i++) {
			int marker = data[i] & 0xff;
			if ((marker == 0 || marker == 3) && i + 1 < end) {
				int next = i + 1;
				while (next < end && (data[next] & 0xff) != 0 && (data[next] & 0xff) != 1 && (data[next] & 0xff) != 2 && (data[next] & 0xff) != 3) next++;
				String variable = ascii(data, i + 1, next);
				if (requestedVariable.equalsIgnoreCase(variable) && next < end && (data[next] & 0xff) == 1) {
					int valueStart = next + 1, valueEnd = valueStart;
					while (valueEnd < end && (data[valueEnd] & 0xff) != 0 && (data[valueEnd] & 0xff) != 1 && (data[valueEnd] & 0xff) != 2 && (data[valueEnd] & 0xff) != 3) valueEnd++;
					value = ascii(data, valueStart, valueEnd);
				}
				i = next - 1;
			}
		}
		return value;
	}

// 	private static String findEnvironmentValue(byte[] data, int start, int end, String requestedVariable) {
//     for (int i = start; i < end; i++) {
//         int b = data[i] & 0xff;
//         if (b == 0x02 || b == 0xFF) { // Skip escaped byte (ESC <byte> or IAC IAC)
//             i++;
//             continue;
//         }
//         if ((b == 0 || b == 3) && i + 1 < end) { // VAR (0) or USERVAR (3)
//             int nameStart = i + 1;
//             int next = scanToMarker(data, nameStart, end);
//             String variable = unescapeAscii(data, nameStart, next);

//             if (next < end && (data[next] & 0xff) == 1) { // VALUE (1)
//                 int valStart = next + 1;
//                 int valEnd = scanToMarker(data, valStart, end);
//                 if (requestedVariable.equalsIgnoreCase(variable)) {
//                     return unescapeAscii(data, valStart, valEnd);
//                 }
//                 i = valEnd - 1;
//             } else {
//                 i = next - 1;
//             }
//         }
//     }
//     return null;
// }

// private static int scanToMarker(byte[] data, int pos, int end) {
//     while (pos < end) {
//         int b = data[pos] & 0xff;
//         if (b == 0x02 || b == 0xFF) {
//             pos += 2; // Skip ESC/IAC and the literal byte after it
//             continue;
//         }
//         if (b == 0 || b == 1 || b == 2 || b == 3) {
//             break;
//         }
//         pos++;
//     }
//     return Math.min(pos, end);
// }

// private static String unescapeAscii(byte[] data, int start, int end) {
//     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(end - start);
//     for (int i = start; i < end; i++) {
//         int b = data[i] & 0xff;
//         if ((b == 0x02 || b == 0xFF) && i + 1 < end) {
//             out.write(data[++i]); // Write the literal escaped byte, skip the prefix
//         } else {
//             out.write(b);
//         }
//     }
//     return out.toString(java.nio.charset.StandardCharsets.US_ASCII);
// }


	/** Returns the client-reported terminal model, or null if it has not been negotiated. */
	public synchronized String getTerminalModel() { return terminalModel; }

	/** Returns maximum rows supported by the negotiated terminal model, or -1 if unknown. */
	public synchronized int getMaxRows() { return maxRows; }

	/** Returns maximum columns supported by the negotiated terminal model, or -1 if unknown. */
	public synchronized int getMaxColumns() { return maxColumns; }

	private void setMaximumDimensions(String model) {
		String normalized = model.trim().toUpperCase(java.util.Locale.ROOT);
		if (normalized.equals("IBM-3477-FC") || normalized.equals("IBM-3477-FG")
				|| normalized.equals("IBM-3180-2")) {
			maxRows = 27;
			maxColumns = 132;
		} else if (TN5250_TERMINAL_TYPES.contains(normalized)) {
			maxRows = 24;
			maxColumns = 80;
		}
	}

	/** Returns the client-requested device name, or null if it has not been negotiated. */
	public synchronized String getDevname() { return devname; }

	/** Returns a username sent by the client in Telnet environment negotiation, or null if absent. */
	public synchronized String getUsername() { return username; }

	/** Returns the client-reported code page/CCSID, or null if it has not been negotiated. */
	public synchronized int getCcsid() { return ccsid == -1 ? DEFAULT_EBCDIC_CCSID : ccsid; }
	
	private static String ascii(byte[] data, int start, int end) {
		StringBuilder value = new StringBuilder();
		for (int i = start; i < end; i++) { int ch = data[i] & 0xff; if (ch == IAC && i + 1 < end && (data[i + 1] & 0xff) == IAC) i++; value.append((char) ch); }
		return value.toString().trim();
	}
}
