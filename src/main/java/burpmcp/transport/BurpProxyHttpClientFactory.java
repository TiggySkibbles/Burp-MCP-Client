package burpmcp.transport;

import burp.api.montoya.MontoyaApi;
import burpmcp.util.Json;
import burpmcp.util.Log;
import com.fasterxml.jackson.databind.JsonNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;

/**
 * Builds the {@link HttpClient} used for all MCP/OAuth traffic. By default this routes through
 * Burp's own Proxy listener, so outbound requests inherit Burp's configured upstream proxy and
 * (as of Burp 2025.8+, which passes {@code text/event-stream} through live rather than buffering
 * it) land in Burp's own Proxy History, sendable to Repeater/Intruder like any other request.
 *
 * <p>Doing this requires trusting Burp's own MITM CA certificate, since Burp terminates TLS at
 * its listener. That cert is bootstrapped on first use via a plain HTTP GET to {@code http://burp/cert}
 * routed through the listener itself.
 */
public final class BurpProxyHttpClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final MontoyaApi api;

    private volatile InetSocketAddress cachedListener;
    private volatile SSLContext cachedSslContext;

    public BurpProxyHttpClientFactory(MontoyaApi api) {
        this.api = api;
    }

    public HttpClient buildClient(NetworkSettings settings) throws TransportException {
        if (settings.bypassBurpProxy()) {
            return HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
        }

        InetSocketAddress listener = resolveListenerAddress(settings);
        SSLContext sslContext = resolveBurpCaTrustingSslContext(listener);
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(listener))
                .sslContext(sslContext)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private InetSocketAddress resolveListenerAddress(NetworkSettings settings) {
        if (settings.hasManualListenerOverride()) {
            return new InetSocketAddress(settings.manualListenerHost(), settings.manualListenerPort());
        }
        try {
            String json = api.burpSuite().exportProjectOptionsAsJson("project_options.proxy");
            JsonNode root = Json.MAPPER.readTree(json);
            JsonNode listeners = root.path("proxy").path("request_listeners");
            for (JsonNode listenerNode : listeners) {
                if (listenerNode.path("running").asBoolean(false)) {
                    String bindAddr = listenerNode.path("listen_specific_address").asText("127.0.0.1");
                    if (bindAddr == null || bindAddr.isBlank() || bindAddr.equals("*")) {
                        bindAddr = "127.0.0.1";
                    }
                    int port = listenerNode.path("listener_port").asInt(8080);
                    return new InetSocketAddress(bindAddr, port);
                }
            }
            Log.info("No running Burp proxy listener found in project options; using default 127.0.0.1:8080");
        } catch (Exception e) {
            Log.error("Could not discover Burp's proxy listener from project options; using default 127.0.0.1:8080", e);
        }
        return new InetSocketAddress("127.0.0.1", 8080);
    }

    private synchronized SSLContext resolveBurpCaTrustingSslContext(InetSocketAddress listener) throws TransportException {
        if (cachedSslContext != null && Objects.equals(cachedListener, listener)) {
            return cachedSslContext;
        }
        X509Certificate burpCa = fetchBurpCaCertificate(listener);
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("burp-ca", burpCa);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            cachedSslContext = sslContext;
            cachedListener = listener;
            return sslContext;
        } catch (Exception e) {
            throw new TransportException("Failed to build an SSLContext trusting Burp's CA certificate", e);
        }
    }

    private X509Certificate fetchBurpCaCertificate(InetSocketAddress listener) throws TransportException {
        try {
            HttpClient bootstrapClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(listener))
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://burp/cert"))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = bootstrapClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new TransportException("Fetching Burp's CA certificate via http://burp/cert returned HTTP "
                        + response.statusCode() + " — is the Burp proxy listener at " + listener + " running?");
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(new ByteArrayInputStream(response.body()));
            return (X509Certificate) cert;
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Failed to fetch Burp's CA certificate through listener " + listener, e);
        }
    }
}
