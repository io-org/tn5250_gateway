package org.as400.tn5250.mfa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Collects TN5250 Telnet records and decodes them as text screens. */
public final class Tn5250ScreenDecoder {
	private static final int IAC = 0xFF;
	private static final int EOR = 0xEF;

	private final Charset ebcdicCharset;
	private final ByteArrayOutputStream record = new ByteArrayOutputStream();
	private boolean previousWasIac;

	public Tn5250ScreenDecoder(Charset ebcdicCharset) {
		this.ebcdicCharset = ebcdicCharset;
	}

	/** Returns each complete 5250 screen found in the supplied network data. */
	public List<String> accept(byte[] bytes, int length) {
		List<String> screens = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			int value = bytes[i] & 0xFF;
			record.write(value);

			if (previousWasIac) {
				if (value == EOR) {
					byte[] completeRecord = record.toByteArray();
					record.reset();
					String screen = decode(completeRecord);
					if (screen != null) {
						screens.add(screen);
					}
				}
				previousWasIac = false;
			} else if (value == IAC) {
				previousWasIac = true;
			}
		}
		return screens;
	}

	private String decode(byte[] raw) {
		byte[] record = removeTelnet(raw);
		if (record.length < 10 || (record[2] & 0xFF) != 0x12 || (record[3] & 0xFF) != 0xA0) {
			return null;
		}

		int rows = 24;
		int cols = 80;
		char[] screen = blankScreen(rows, cols);
		int[] cursor = { 0 };

		int pos = 9;
		while (pos < record.length) {
			if ((record[pos++] & 0xFF) != 0x04) {
				continue;
			}
			if (pos >= record.length) {
				break;
			}

			int command = record[pos++] & 0xFF;
			if (command == 0x40) { // Clear Unit (24x80)
				rows = 24;
				cols = 80;
				screen = blankScreen(rows, cols);
				cursor[0] = 0;
			} else if (command == 0x3A) { // Clear Unit Alternate (27x132)
				rows = 27;
				cols = 132;
				screen = blankScreen(rows, cols);
				cursor[0] = 0;
			} else if (command == 0x11) { // Write to Display
				pos += 2;
				while (pos < record.length) {
					int order = record[pos] & 0xFF;
					if (order == 0x04) {
						break;
					}
					if (order == 0x11 && pos + 2 < record.length) { // SBA
						int row = clamp((record[pos + 1] & 0xFF) - 1, 0, rows - 1);
						int col = clamp((record[pos + 2] & 0xFF) - 1, 0, cols - 1);
						cursor[0] = row * cols + col;
						pos += 3;
					} else if (order == 0x02 && pos + 3 < record.length) { // Repeat to Address
						int row = clamp((record[pos + 1] & 0xFF) - 1, 0, rows - 1);
						int col = clamp((record[pos + 2] & 0xFF) - 1, 0, cols - 1);
						int target = row * cols + col;
						int repeated = record[pos + 3] & 0xFF;
						int count = 0;
						while (cursor[0] != target && count++ < screen.length) {
							writeByte(screen, cursor, repeated);
						}
						writeByte(screen, cursor, repeated);
						pos += 4;
					} else if (order == 0x1D) { // Start Field
						pos += 4;
					} else if (order == 0x13) { // Insert Cursor
						pos += 3;
					} else if (order == 0x03) { // Erase to Address
						pos += 4;
					} else if (order == 0x12 || order == 0x14) { // WEA / TD
						pos += 2;
					} else {
						writeByte(screen, cursor, order);
						pos++;
					}
				}
			} else {
				break;
			}
		}

		StringBuilder result = new StringBuilder(rows * (cols + 1));
		for (int row = 0; row < rows; row++) {
			if (row > 0) {
				result.append('\n');
			}
			result.append(screen, row * cols, cols);
		}
		return result.toString();
	}

	private char[] blankScreen(int rows, int cols) {
		char[] screen = new char[rows * cols];
		Arrays.fill(screen, ' ');
		return screen;
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private void writeByte(char[] screen, int[] cursor, int value) {
		char ch;
		if (value < 0x40) {
			ch = ' ';
		} else {
			String decoded = new String(new byte[] { (byte) value }, ebcdicCharset);
			ch = decoded.isEmpty() ? '?' : decoded.charAt(0);
		}
		screen[cursor[0]] = ch;
		cursor[0] = (cursor[0] + 1) % screen.length;
	}

	private byte[] removeTelnet(byte[] raw) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < raw.length; i++) {
			int value = raw[i] & 0xFF;
			if (value != IAC || i + 1 >= raw.length) {
				out.write(value);
				continue;
			}

			int command = raw[++i] & 0xFF;
			if (command == EOR) {
				continue;
			}
			if (command == IAC) {
				out.write(IAC);
			} else if (command >= 0xFB && command <= 0xFE) {
				i++; // Negotiation command includes an option byte.
			} else if (command == 0xFA) {
				while (i + 1 < raw.length) {
					if ((raw[i++] & 0xFF) == IAC && (raw[i] & 0xFF) == 0xF0) {
						break;
					}
				}
			}
		}
		return out.toByteArray();
	}
}
