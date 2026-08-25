package burpmcp.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceUtilTest {

    @Test
    void s256MatchesRfc7636AppendixBTestVector() {
        // https://www.rfc-editor.org/rfc/rfc7636#appendix-B
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expectedChallenge, PkceUtil.s256(verifier));
    }

    @Test
    void generateProducesUrlSafeVerifierWithinRfcLengthBounds() {
        PkceUtil.Pkce pkce = PkceUtil.generate();
        assertTrue(pkce.verifier().length() >= 43 && pkce.verifier().length() <= 128,
                "verifier length must be 43-128 chars per RFC 7636 §4.1: " + pkce.verifier().length());
        assertTrue(pkce.verifier().matches("[A-Za-z0-9\\-_]+"), "verifier must use only unreserved URL-safe characters");
        assertEquals("S256", pkce.method());
        assertEquals(PkceUtil.s256(pkce.verifier()), pkce.challenge());
    }

    @Test
    void randomStateIsNonEmptyAndVariesBetweenCalls() {
        String a = PkceUtil.randomState();
        String b = PkceUtil.randomState();
        assertTrue(a.length() > 0);
        assertTrue(!a.equals(b));
    }
}
