package burpmcp.oauth;

import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Builds/parses RFC 7591 Dynamic Client Registration request/response bodies. The actual HTTP
 * call is made by {@link OAuthManager} so it can push the exchange to the traffic log like every
 * other OAuth call.
 */
public final class DynamicClientRegistration {

    public record RegisteredClient(String clientId, String clientSecret) {
    }

    private DynamicClientRegistration() {
    }

    public static String buildRequestBody(String clientName, String redirectUri) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("client_name", clientName);
        ArrayNode redirectUris = body.putArray("redirect_uris");
        redirectUris.add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        // Public/native client using PKCE — no client secret to protect.
        body.put("token_endpoint_auth_method", "none");
        try {
            return Json.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize DCR request body", e);
        }
    }

    public static RegisteredClient parseResponse(String responseBody) throws IOException {
        JsonNode json = Json.MAPPER.readTree(responseBody);
        String clientId = json.path("client_id").asText(null);
        if (clientId == null || clientId.isBlank()) {
            throw new IOException("Dynamic Client Registration response is missing client_id");
        }
        String clientSecret = json.hasNonNull("client_secret") ? json.get("client_secret").asText() : null;
        return new RegisteredClient(clientId, clientSecret);
    }
}
