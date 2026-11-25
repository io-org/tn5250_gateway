package org.as400.tn5250.mfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Main class.
 * </p>
 *
 */
public class Main {

	/*
	 * pom.xml
	 *
	 * <manifestEntries> <Build-Time>${maven.build.timestamp}</Build-Time>
	 * <Implementation-Title>TN5250-Gateway</Implementation-Title>
	 * <Implementation-Version>${project.version}</Implementation-Version>
	 * <Implementation-Vendor>TN5250-Gateway project</Implementation-Vendor>
	 * </manifestEntries>
	 */
	public static final String CLONE_URL = "https://github.com/io-org/tn5250_gateway.git";
	/**
	 * Constant <code>MANIFEST_IMPLEMENTATION_TITLE</code> See pom.xml
	 */
	public static final String MANIFEST_IMPLEMENTATION_TITLE;
	/**
	 * Constant <code>MANIFEST_IMPLEMENTATION_VERSION</code> See pom.xml
	 */
	public static final String MANIFEST_IMPLEMENTATION_VERSION;
	/**
	 * Constant <code>MANIFEST_IMPLEMENTATION_VENDOR</code> See pom.xml
	 */
	public static final String MANIFEST_IMPLEMENTATION_VENDOR;
	/**
	 * Constant <code>MANIFEST_BUILD_TIME</code> See pom.xml
	 */
	public static final String MANIFEST_BUILD_TIME;

	public static String GIT_BUILD_TIME;
	public static String GIT_BUILD_VERSION;
	public static String GIT_COMMIT_ID_ABBREV;
	public static String GIT_COMMIT_ID_FULL;

	static {
		try (InputStream is = Main.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
			Manifest manifest = new Manifest(is);
			Attributes attributes = manifest.getMainAttributes();

			MANIFEST_IMPLEMENTATION_TITLE = attributes.getValue("Implementation-Title");
			MANIFEST_IMPLEMENTATION_VERSION = attributes.getValue("Implementation-Version");
			MANIFEST_IMPLEMENTATION_VENDOR = attributes.getValue("Implementation-Vendor");
			MANIFEST_BUILD_TIME = attributes.getValue("Build-Time");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try (InputStream is = Main.class.getClassLoader().getResourceAsStream("git.properties")) {
			Properties properties = new Properties();
			properties.load(is);
			GIT_BUILD_TIME = properties.getProperty("git.build.time");
			GIT_BUILD_VERSION = properties.getProperty("git.build.version");
			GIT_COMMIT_ID_ABBREV = properties.getProperty("git.commit.id.abbrev");
			GIT_COMMIT_ID_FULL = properties.getProperty("git.commit.id.full");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	// import org.apache.log4j.Level;
	// import org.apache.log4j.Logger;
	// static {
	//
	// Logger.getRootLogger().setLevel(Level.OFF);
	// }

	static {
		PrintStream filterOut = new PrintStream(System.err) {
			@Override
			public void println(String l) {
				if (!l.startsWith("SLF4J")) {
					super.println(l);
				}
			}
		};
		System.setErr(filterOut);
	}

	/**
	 * <p>
	 * main.
	 * </p>
	 *
	 * @param args
	 *                 an array of {@link java.lang.String} objects
	 * @throws java.lang.Exception
	 *                                 if any.
	 */
	public static void main(String[] args) throws Exception {

		final var handle = ProcessHandle.current();
		final var info = handle.info();

		Logger rootLogger = Logger.getLogger("");
		// Logger rootLogger = Logger.getGlobal();
		rootLogger.setLevel(Level.OFF); // Suppress all logging

		// Optional: Disable console handler if it exists
		for (var handler : rootLogger.getHandlers()) {
			if (handler instanceof ConsoleHandler) {
				handler.setLevel(Level.OFF);
			}
		}
		System.err.println(createHeader());

		boolean debug = false;
		String noColorEnvVar = System.getenv("NO_COLOR");
		boolean noColor = noColorEnvVar != null;

		if (args.length > 0) {
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--debug", "-d" -> debug = true;
					case "--no-color", "-nc" -> noColor = true;
					case "help", "h", "?", "--help", "-h" -> {
						printHelp();
						System.exit(0);
						return;
					}
					default -> {
						System.err.println("Unknown flag or option!");
						System.exit(-1);
						return;
					}
				}
			}
		}

		if (debug) {
			String commandLine = info.commandLine().orElse("Command line not present");

			System.err.println("Build:           %s".formatted(MANIFEST_BUILD_TIME));
			System.err.println("Commit:          %s".formatted(GIT_COMMIT_ID_FULL));
			System.err.println("Clone URL:       %s".formatted(CLONE_URL));;
			System.err.println("Java class path: %s".formatted(ISystemProperties.CLASSPATH));
			System.err.println("Java version:    %s".formatted(ISystemProperties.JAVA_VERSION));
			System.err.println("OS name:         %s".formatted(ISystemProperties.OS_NAME));
			System.err.println("OS version:      %s".formatted(ISystemProperties.OS_VERSION));
			System.err.println("OS architecture: %s".formatted(ISystemProperties.OS_ARCH));
			System.err.println("Version:         %s".formatted(MANIFEST_IMPLEMENTATION_VERSION));
			System.err.println("Command line:    %s".formatted(commandLine));
			for (int i = 0; i < args.length; i++) {
				System.err.println("args[%d] = \"%s\"".formatted(i, args[i]));
			}
		}

		Server server = new Server();
		server.serve(debug);
	}

	/**
	 *
	 * @return
	 */
	public static String createHeader() {
		return "\n" + MANIFEST_IMPLEMENTATION_TITLE + "\n";
	}

	private static void printHelp() {
		StringBuilder sb = new StringBuilder();
		// sb.append("01234567890123456789012345678901234567890123456789012345678901234567890123456789\n");
		sb.append("                                                                                \n");
		sb.append("Usage:                                                                          \n");
		sb.append("                                                                                \n");
		sb.append("tn5250-gateway [command] [flags...]                                             \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Commands:                                                                       \n");
		sb.append("                                                                                \n");
		sb.append(" help           Print help message.                                             \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Flags:                                                                          \n");
		sb.append("                                                                                \n");
		sb.append(" --debug,    -d Enabel debug mode (prints messages to stderr).                  \n");
		sb.append(" --no-color, -n Disable ANSI colors (same effect as NO_COLOR env var).          \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Optional environment variables:                                                 \n");
		sb.append("                                                                                \n");
		sb.append(" NO_COLOR       Disable ANSI colors (same effect as --no-color, -n flags).      \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Exit Codes:                                                                     \n");
		sb.append("                                                                                \n");
		sb.append("   0            The command was successfully executed without errors.           \n");
		sb.append("   1            An error occurred during the execution of the command.          \n");
		sb.append(" 255            An unknown or invalid command, option or flag was provided.     \n");
		sb.append("                                                                                \n");
		// sb.append("01234567890123456789012345678901234567890123456789012345678901234567890123456789\n");
		System.err.println(sb);
	}
}
