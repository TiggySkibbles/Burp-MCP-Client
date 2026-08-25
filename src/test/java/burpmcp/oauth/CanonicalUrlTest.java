package burpmcp.oauth;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalUrlTest {

    @Test
    void lowercasesSchemeAndHost() {
        assertEquals("https://example.com/mcp", CanonicalUrl.canonicalize(URI.create("HTTPS://Example.COM/mcp")));
    }

    @Test
    void stripsDefaultHttpsPort() {
        assertEquals("https://example.com/mcp", CanonicalUrl.canonicalize(URI.create("https://example.com:443/mcp")));
    }

    @Test
    void stripsDefaultHttpPort() {
        assertEquals("http://example.com/mcp", CanonicalUrl.canonicalize(URI.create("http://example.com:80/mcp")));
    }

    @Test
    void keepsNonDefaultPort() {
        assertEquals("https://example.com:8443/mcp", CanonicalUrl.canonicalize(URI.create("https://example.com:8443/mcp")));
    }

    @Test
    void stripsTrailingSlashExceptForRoot() {
        assertEquals("https://example.com/mcp", CanonicalUrl.canonicalize(URI.create("https://example.com/mcp/")));
        assertEquals("https://example.com/", CanonicalUrl.canonicalize(URI.create("https://example.com/")));
    }

    @Test
    void emptyPathBecomesRoot() {
        assertEquals("https://example.com/", CanonicalUrl.canonicalize(URI.create("https://example.com")));
    }

    @Test
    void preservesQueryString() {
        assertEquals("https://example.com/mcp?tenant=acme", CanonicalUrl.canonicalize(URI.create("https://example.com/mcp?tenant=acme")));
    }

    @Test
    void keyForIsStableAndDeterministic() {
        String a = CanonicalUrl.keyFor(URI.create("https://Example.com:443/mcp/"));
        String b = CanonicalUrl.keyFor(URI.create("HTTPS://example.com/mcp"));
        assertEquals(a, b);
        assertEquals(64, a.length(), "SHA-256 hex digest should be 64 chars");
    }
}
