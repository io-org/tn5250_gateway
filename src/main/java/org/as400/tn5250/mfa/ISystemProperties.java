package org.as400.tn5250.mfa;

/**
 * <p>
 * ISystemProperties interface.</p>
 *

 * @since 1.0.21
 */
public interface ISystemProperties {
        
    String OS_NAME = System.getProperty("os.name");
    String OS_VERSION = System.getProperty("os.version");
    String OS_ARCH = System.getProperty("os.arch");
    String CLASSPATH = System.getProperty("java.class.path");
    String JAVA_HOME = System.getProperty("java.home");
    String JAVA_VERSION = System.getProperty("java.version");
    String JAVA_VENDOR = System.getProperty("java.vendor");
    String TMP_DIR = System.getProperty("java.io.tmpdir");
    String USER_NAME = System.getProperty("user.name");
    String USER_DIR = System.getProperty("user.dir");
    String USER_HOME = System.getProperty("user.home");
}
