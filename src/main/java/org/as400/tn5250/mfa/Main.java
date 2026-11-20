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
	public static final String REPO_URL = "https://github.com/io-org/TN5250-Gateway.git";
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

		if (args.length < 1) {
			printHelp();
			System.exit(0);
			return;
		}
		boolean debug = true;

		String cmd = args[0];
		switch (cmd) {
			case "help", "h", "?", "--help", "-h" -> {
				printHelp();
				System.exit(0);
				return;
			}
			case "--debug", "-d" -> {
				debug = true;
			}
		}
		String noColorEnvVar = System.getenv("NO_COLOR");

		boolean noColor = noColorEnvVar != null;

		if (debug) {
			String classPath = System.getProperty("java.class.path");
			String javaVersion = System.getProperty("java.version");
			String osName = System.getProperty("os.name");
			String osVersion = System.getProperty("os.version");
			String osArch = System.getProperty("os.arch");
			String commandLine = info.commandLine().orElse("Command line not present");

			System.err.println("Java class path: %s".formatted(classPath));
			System.err.println("Java version:    %s".formatted(javaVersion));
			System.err.println("OS name:         %s".formatted(osName));
			System.err.println("OS version:      %s".formatted(osVersion));
			System.err.println("OS architecture: %s".formatted(osArch));
			System.err.println("Command line:    %s".formatted(commandLine));

			for (int i = 0; i < args.length; i++) {
				System.err.println("args[%d] = \"%s\"".formatted(i, args[i]));
			}
		}

		if (args.length > 1) {
			for (int i = 1; i < args.length; i++) {
				switch (args[i]) {
					case "--no-color", "-nc" -> noColor = true;

					default -> {
						System.err.println("Unknown flag or option!");
						System.exit(-1);
						return;
					}
				}
			}
		}
		Server server = new Server();
		server.serve();
	}

	/**
	 *
	 * @return
	 */
	public static String createHeader() {
		return "\n" + MANIFEST_IMPLEMENTATION_TITLE + "\nVersion: " + MANIFEST_IMPLEMENTATION_VERSION + "\nJVM version: " + ISystemProperties.JAVA_VERSION + "\nBuild: " + MANIFEST_BUILD_TIME + "\nCommit: " + GIT_COMMIT_ID_FULL + "\nClone: " + REPO_URL + "\n";
	}

	private static void printHelp() {
		StringBuilder sb = new StringBuilder();
		// sb.append("01234567890123456789012345678901234567890123456789012345678901234567890123456789\n");
		sb.append("                                                                                \n");
		sb.append("Usage:                                                                          \n");
		sb.append("                                                                                \n");
		sb.append("tn5250-gateway <command> [flags...] [options...]                                            \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Commands:                                                                       \n");
		sb.append("                                                                                \n");
		sb.append(" help              Print help message.                                          \n");
		sb.append(" wait              Start listening for requests on stdin.                       \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Flags:                                                                          \n");
		sb.append("                                                                                \n");
		sb.append(" --no-color,   -nc Disable ANSI colors (same effect as NO_COLOR env var).       \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Options:                                                                        \n");
		sb.append("                                                                                \n");
		sb.append(" --verbose,     -v Verbosity level: 0 - zero (default), 1 - min, 2 - max.       \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Optional environment variables:                                                 \n");
		sb.append("                                                                                \n");
		sb.append(" NO_COLOR          Disable ANSI colors (same effect as --no-color, -n flags).   \n");
		sb.append("                                                                                \n");
		sb.append("                                                                                \n");
		sb.append("Exit Codes:                                                                     \n");
		sb.append("                                                                                \n");
		sb.append("   0               The command was successfully executed without errors.        \n");
		sb.append("   1               An error occurred during the execution of the command.       \n");
		sb.append(" 255               An unknown or invalid command, option or flag was provided.  \n");
		sb.append("                                                                                \n");
		// sb.append("01234567890123456789012345678901234567890123456789012345678901234567890123456789\n");
		System.err.println(sb);
	}
}
