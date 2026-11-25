package org.as400.tn5250.mfa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import tools.jackson.databind.ObjectMapper;

public class Utils {
        public static final ObjectMapper OM = new ObjectMapper();

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static String getTimestampForFileName() {
        return LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
    }

    public static String readJsonObject(Reader reader) throws IOException {
        StringBuilder jsonBuilder = new StringBuilder();
        int c;
        int braceDepth = 0;
        boolean started = false;
        boolean inQuotes = false;
        boolean escaped = false;

        while ((c = reader.read()) != -1) {
            char ch = (char) c;

            // Skip everything until the very first opening brace
            if (!started) {
                if (ch == '{') {
                    started = true;
                    braceDepth = 1;
                    jsonBuilder.append(ch);
                }
                continue;
            }

            jsonBuilder.append(ch);

            // Handle string literals so braces inside text (e.g., "text }") don't break
            // counting
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            // Count braces only when outside of JSON string values
            if (!inQuotes) {
                if (ch == '{') {
                    braceDepth++;
                } else if (ch == '}') {
                    braceDepth--;
                    // Stop reading as soon as the root object closes
                    if (braceDepth == 0) {
                        return jsonBuilder.toString();
                    }
                }
            }
        }

        throw new IllegalArgumentException("Stream ended before a complete JSON object was found.");
    }

    public static void main(String[] args) throws IOException {
        String streamData = "garbage prefix 123\n" +
                "more prefix {\n" +
                "  \"name\": \"Alice\",\n" +
                "  \"note\": \"Brace inside string } shouldn't stop it\",\n" +
                "  \"nested\": { \"id\": 42 }\n" +
                "} trailing garbage }\n" +
                "extra lines";

        try (BufferedReader reader = new BufferedReader(new StringReader(streamData))) {
            String cleanJson = readJsonObject(reader);
            System.err.println(cleanJson);
        }
    }
}
