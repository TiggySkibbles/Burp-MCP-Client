package burpmcp.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** RFC 9728 OAuth 2.0 Protected Resource Metadata, as served from {@code /.well-known/oauth-protected-resource}. */
public record ProtectedResourceMetadata(
        String resource,
        @JsonProperty("authorization_servers") List<String> authorizationServers,
        @JsonProperty("bearer_methods_supported") List<String> bearerMethodsSupported,
        @JsonProperty("scopes_supported") List<String> scopesSupported
) {
}
