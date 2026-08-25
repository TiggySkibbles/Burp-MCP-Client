package burpmcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 PKCE code verifier/challenge generation, mandatory for MCP's OAuth 2.1-based auth flow. */
public final class PkceUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PkceUtil() {
    }

    public record Pkce(String verifier, String challenge, String method) {
    }

    /** 43-128 char unreserved-charset verifier (we generate 96 bytes -> 128 base64url chars) plus its S256 challenge. */
    public static Pkce generate() {
        byte[] verifierBytes = new byte[96];
        RANDOM.nextBytes(verifierBytes);
        String verifier = URL_ENCODER.encodeToString(verifierBytes);

        String challenge = s256(verifier);
        return new Pkce(verifier, challenge, "S256");
    }

    public static String s256(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
