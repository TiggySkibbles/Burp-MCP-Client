package burpmcp.util;

import burp.api.montoya.logging.Logging;

/**
 * Thin static accessor around Burp's {@link Logging} API, set once during
 * {@code BurpExtension.initialize()}. Falls back to stderr if used before that (e.g. in unit tests).
 */
public final class Log {

    private static volatile Logging delegate;

    private Log() {
    }

    public static void init(Logging logging) {
        delegate = logging;
    }

    public static void info(String message) {
        if (delegate != null) {
            delegate.logToOutput(message);
        } else {
            System.out.println(message);
        }
    }

    public static void error(String message) {
        if (delegate != null) {
            delegate.logToError(message);
        } else {
            System.err.println(message);
        }
    }

    public static void error(String message, Throwable t) {
        error(message + ": " + t);
        if (delegate != null) {
            delegate.logToError(t);
        } else {
            t.printStackTrace();
        }
    }
}
