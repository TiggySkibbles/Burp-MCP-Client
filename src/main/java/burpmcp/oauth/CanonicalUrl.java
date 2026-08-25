package burpmcp.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonicalizes an MCP server URI for use as the OAuth {@code resource} parameter (RFC 8707)
 * and as a stable persistence key: lowercase scheme/host, default ports stripped, no fragment,
 * trailing slash preserved only when the path is exactly "/".
 */
public final class CanonicalUrl {

    private CanonicalUrl() {
    }

    public static String canonicalize(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean isDefaultPort = port == -1
                || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        // Strip a trailing slash unless the path is just "/".
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (!isDefaultPort) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    /** SHA-256 hex digest of the canonical form, safe for use as a persistence key. */
    public static String keyFor(URI uri) {
        String canonical = canonicalize(uri);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
