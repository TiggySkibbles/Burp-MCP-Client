package burpmcp.persistence;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burpmcp.util.Json;
import burpmcp.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@code api.persistence().extensionData()}. Structured values are stored as JSON-serialized
 * strings under a small set of keys, keeping the Montoya-facing surface trivial regardless of how
 * rich the underlying record shapes get. Client secrets and OAuth tokens stored this way are
 * cleartext in the Burp project file, consistent with Burp's own general storage model.
 */
public final class PersistenceService {

    private static final String PROFILES_KEY = "mcpclient.profiles";
    private static final String OAUTH_CHILD = "mcpclient.oauth";
    private static final String TOKENS_SUFFIX = ".tokens";
    private static final String DISCOVERY_SUFFIX = ".metadata";

    private final PersistedObject root;

    public PersistenceService(MontoyaApi api) {
        this.root = api.persistence().extensionData();
    }

    public List<ServerProfile> loadProfiles() {
        String json = root.getString(PROFILES_KEY);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return Json.MAPPER.readValue(json, new TypeReference<List<ServerProfile>>() {
            });
        } catch (Exception e) {
            Log.error("Failed to parse stored server profiles; starting with an empty list", e);
            return new ArrayList<>();
        }
    }

    public void saveProfiles(List<ServerProfile> profiles) {
        try {
            root.setString(PROFILES_KEY, Json.MAPPER.writeValueAsString(profiles));
        } catch (Exception e) {
            Log.error("Failed to save server profiles", e);
        }
    }

    public void saveTokens(String resourceKeyHash, StoredTokens tokens) {
        PersistedObject oauthRoot = childOrCreate(OAUTH_CHILD);
        try {
            oauthRoot.setString(resourceKeyHash + TOKENS_SUFFIX, Json.MAPPER.writeValueAsString(tokens));
        } catch (Exception e) {
            Log.error("Failed to save OAuth tokens", e);
        }
    }

    public StoredTokens loadTokens(String resourceKeyHash) {
        PersistedObject oauthRoot = childOrCreate(OAUTH_CHILD);
        String json = oauthRoot.getString(resourceKeyHash + TOKENS_SUFFIX);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(json, StoredTokens.class);
        } catch (Exception e) {
            Log.error("Failed to parse stored OAuth tokens", e);
            return null;
        }
    }

    public void clearTokens(String resourceKeyHash) {
        childOrCreate(OAUTH_CHILD).deleteString(resourceKeyHash + TOKENS_SUFFIX);
    }

    public void saveDiscovery(String resourceKeyHash, CachedDiscovery discovery) {
        PersistedObject oauthRoot = childOrCreate(OAUTH_CHILD);
        try {
            oauthRoot.setString(resourceKeyHash + DISCOVERY_SUFFIX, Json.MAPPER.writeValueAsString(discovery));
        } catch (Exception e) {
            Log.error("Failed to save OAuth discovery cache", e);
        }
    }

    public CachedDiscovery loadDiscovery(String resourceKeyHash) {
        PersistedObject oauthRoot = childOrCreate(OAUTH_CHILD);
        String json = oauthRoot.getString(resourceKeyHash + DISCOVERY_SUFFIX);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(json, CachedDiscovery.class);
        } catch (Exception e) {
            Log.error("Failed to parse cached OAuth discovery", e);
            return null;
        }
    }

    private PersistedObject childOrCreate(String key) {
        PersistedObject child = root.getChildObject(key);
        if (child == null) {
            child = PersistedObject.persistedObject();
            root.setChildObject(key, child);
        }
        return child;
    }
}
