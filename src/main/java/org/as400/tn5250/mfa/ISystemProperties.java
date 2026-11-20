package org.as400.tn5250.mfa;

/**
 * <p>
 * ISystemProperties interface.</p>
 *

 * @since 1.0.21
 */
public interface ISystemProperties {
    String JAVA_HOME = System.getProperty("java.home");
    String USER_NAME = System.getProperty("user.name");
    String USER_DIR = System.getProperty("user.dir");
    String USER_HOME = System.getProperty("user.home");
    String TMP_DIR = System.getProperty("java.io.tmpdir");
    String JAVA_VERSION = System.getProperty("java.version");
    String JAVA_VENDOR = System.getProperty("java.vendor");
    String OS_NAME = System.getProperty("os.name");
    String OS_VERSION = System.getProperty("os.version");
    String OS_ARCH = System.getProperty("os.arch");
}
