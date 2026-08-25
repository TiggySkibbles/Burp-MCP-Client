package burpmcp.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** RFC 8414 OAuth 2.0 Authorization Server Metadata, as served from {@code /.well-known/oauth-authorization-server}. */
public record AuthorizationServerMetadata(
        String issuer,
        @JsonProperty("authorization_endpoint") String authorizationEndpoint,
        @JsonProperty("token_endpoint") String tokenEndpoint,
        @JsonProperty("registration_endpoint") String registrationEndpoint,
        @JsonProperty("scopes_supported") List<String> scopesSupported,
        @JsonProperty("code_challenge_methods_supported") List<String> codeChallengeMethodsSupported
) {
    public boolean supportsDynamicClientRegistration() {
        return registrationEndpoint != null && !registrationEndpoint.isBlank();
    }
}
