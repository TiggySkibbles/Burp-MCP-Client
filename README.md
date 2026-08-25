# Burp MCP Client

A Burp Suite extension that brings the core capability of [MCP Inspector](https://github.com/modelcontextprotocol/inspector) into Burp itself: connect to a Model Context Protocol (MCP) server, authenticate with OAuth 2.1, and browse/invoke its tools — with every JSON-RPC message and OAuth exchange visible in a dedicated traffic log, and the underlying HTTP traffic routed through Burp's own Proxy so it lands in Proxy History and can be sent to Repeater/Intruder like anything else.

This is an MVP: connection management, OAuth, the `initialize` handshake, and the Tools capability are implemented. Resources, Prompts, Sampling, Roots, Elicitation, Logging, and config import are not yet built — see [Roadmap](#roadmap) below.

## Requirements

- Burp Suite (Community or Professional) with Montoya API support
- JDK 17 or later on your `PATH` to build the extension (the build itself targets Java 17 via a Gradle toolchain, so a newer local JDK is fine — Gradle will provision 17 automatically if it can't find one)
- No other local tooling required — the project ships the Gradle wrapper

## Building

```sh
git clone https://github.com/TiggySkibbles/Burp-MCP-Client.git
cd Burp-MCP-Client
./gradlew shadowJar
```

This produces a self-contained (shaded) jar at `build/libs/burp-mcp-client-0.1.0.jar`, with its Jackson dependency relocated so it won't collide with other loaded extensions. `montoya-api` itself is *not* bundled — Burp supplies that at runtime.

To also run the unit tests:

```sh
./gradlew test
```

## Installing into Burp

1. In Burp, go to **Extensions → Installed → Add**.
2. Set **Extension type** to **Java**.
3. Select `build/libs/burp-mcp-client-0.1.0.jar`.
4. A new **MCP Client** tab appears in the main Burp UI.

## Usage

### Connecting to a server

In the **MCP Client** tab, fill in the **Server connection** panel:

- **Name** — a label for this profile.
- **Server URL** — the MCP server's HTTP endpoint (e.g. `https://example.com/mcp`).
- **Transport** — `AUTO` (recommended), `STREAMABLE_HTTP`, or `LEGACY_SSE`. In `AUTO` mode the extension first attempts a Streamable HTTP `initialize` POST; if the server responds `404`/`405`/`406`, it automatically falls back to the older two-endpoint HTTP+SSE transport.
- **Custom headers** — one `Name: Value` per line, sent on every request.
- **OAuth client id / secret** — optional. Leave blank to use Dynamic Client Registration (RFC 7591) if the authorization server supports it; set these to use a pre-registered static client instead.

Click **Connect**. Use **Save** in the **Saved servers** panel to persist the profile (stored in Burp's project file via the Montoya persistence API) for next time.

### Authentication

If the server requires auth, connecting will surface **Authorization required** in the status line and a **Sign in...** button in the **Authorization** panel — nothing opens a browser without you clicking that button. Signing in runs the full flow: RFC 9728 protected-resource discovery, RFC 8414 authorization-server discovery, Dynamic Client Registration (or your configured static client), PKCE, a one-shot loopback HTTP listener for the redirect, and token exchange. Tokens are stored per-server in Burp's project file, refreshed automatically when expired, and can be inspected or cleared from the **Authorization** panel at any time.

If a token expires or is revoked mid-session, the next request that hits a `401`/`403` will prompt you to sign in again rather than silently failing.

### Tools

Once connected, the **Tools** panel lists everything the server's `tools/list` returns. Selecting a tool renders a form generated from its JSON Schema (nested objects/arrays fall back to a raw-JSON field). **Invoke** calls the tool; a streaming response's progress notifications update live, and **Cancel** sends `notifications/cancelled` for the in-flight call. Results distinguish a **tool-reported error** (`isError: true` — a normal, successful JSON-RPC response) from a genuine **MCP protocol error** (e.g. `-32602 Invalid Params`) — these mean different things when you're debugging a server, so they're rendered differently.

### Network routing

By default, all MCP and OAuth HTTP traffic is routed through Burp's own Proxy listener rather than connecting directly. This means:

- It automatically inherits whatever upstream proxy/SOCKS config you already have set in Burp.
- It shows up in Burp's **Proxy → HTTP history** and can be sent to Repeater/Intruder/Scanner.
- The extension trusts Burp's own CA certificate for this (fetched once via `http://burp/cert` through the listener), since Burp terminates TLS at the listener.

If auto-discovery of the listener's address/port fails, or you'd rather point at a specific listener, set it manually in the connection panel's **Manual proxy listener** field. Check **Connect directly (bypass Burp's Proxy listener)** to skip this entirely and connect straight to the server (no Proxy History visibility, no upstream proxy).

### Traffic log

The panel docked on the right shows every JSON-RPC message and raw HTTP exchange, tagged `MCP` or `OAuth`, in chronological order — click a row to see the full raw detail, including headers and bearer tokens in full (nothing is masked; this is a security tool). It's in-memory only and capped at 5,000 entries — it is **not** written to the Burp project file, since it holds secrets. Use **Export to file...** to save it, or **Clear** to empty it.

## Roadmap

Not yet implemented, but the architecture (a pluggable transport layer, a method-handler registry in `MessageRouter`, and self-contained UI panels) was built to accept these without reworking the core:

- Resources and Prompts tabs
- A Sampling panel (for servers that ask the client to run an LLM completion)
- Roots configuration
- Elicitation
- A Logging/notifications pane
- Importing existing `claude_desktop_config.json` / VS Code `mcp.json` server lists
- Client ID Metadata Documents (CIMD) as a Dynamic Client Registration alternative
- The 2026-07-28 stateless MCP protocol revision

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).
