# Slack transport integration contract

Parent creates these plain Java objects; there are no configuration bindings or implicit Fusion beans.
Transport does not own the supplied mapper, HTTP clients, scheduler, or token suppliers.
Parent must close Socket Mode before closing these dependencies.

## Web API

```java
new SlackWebApi(jsonMapper, extendedHttpClient,
    new SlackWebApi.Settings(URI.create("https://slack.com/api/"),
        Duration.ofSeconds(20), 1024 * 1024, 1024 * 1024),
    appTokenSupplier, botTokenSupplier);
```

- `openConnection(): CompletionStage<URI>` calls `apps.connections.open` with the `xapp-` credential.
- `postMessage(Map<String,Object>): CompletionStage<Map<String,Object>>` calls `chat.postMessage` with the `xoxb-` credential.
- `openView(Map<String,Object>): CompletionStage<Map<String,Object>>` calls `views.open` with the `xoxb-` credential.
- All calls are same-origin JSON POSTs, bounded on request/response bytes, with a request timeout and **no application retries**.
- The injected client MUST use `Redirect.NEVER`, have no authenticator and no cookie handler.
  Parent must not configure request listeners which log authorization or bodies, or replay POSTs.
- `SlackApiException.status()`, `.error()` and `.retryAfter(): Optional<Duration>` are safe metadata, not raw Slack responses.
  A 429 must be deferred by the parent; never blindly replay a publication whose outcome is uncertain.
- No failure exposes a response body, credential, WebSocket ticket, request URI, or nested transport exception.
- HTTPS is required except numeric loopback HTTP fixtures.

## Socket Mode

```java
new SlackSocketMode(jsonMapper, webApi, webSocketHttpClient, scheduledExecutor,
    new SlackSocketMode.Settings(Duration.ofSeconds(20),
        Duration.ofSeconds(1), Duration.ofSeconds(30), 1024 * 1024),
    envelope -> {
        // Authorize and durably admit/deduplicate before returning. Do not run the agent here.
        // Throw on failed durable admission: no ACK is sent; reconnect allows Slack redelivery.
        return Optional.empty(); // ACK only; Optional.of(map) supplies an interaction response.
    }, () -> ThreadLocalRandom.current().nextDouble());
```

- `start()` is idempotent and schedules an outbound connection; `state()` returns `State`.
- `close()` is terminal and idempotent, cancels connection/reconnect work and aborts the owned socket without waiting for a peer close handshake.
  It does not shut down borrowed dependencies.
- `EnvelopeHandler.handle(Map<String,Object>): Optional<Map<String,Object>>` receives the complete envelope, not just its payload.
  It runs serially on WebSocket delivery, must return promptly (Slack requires ACK within 3 seconds), and must finish durable admission before returning.
  `Optional.empty()` means successful admission with a bare ACK, **not** rejection.
  Throw to withhold ACK.
- ACK always contains `envelope_id`; an optional `payload` is included only when `accepts_response_payload` is true.
  Parent handles durable deduplication and interaction business logic; transport holds no envelope cache.
- Text fragments and ACKs are bounded by `maxMessageBytes` in UTF-8 bytes.
- Socket URLs are obtained afresh on each reconnect; no API tokens are attached to the WebSocket handshake.
  Supply a redirect-never, authenticator-free, cookie-free JDK HTTP client for WebSockets.
- Refresh/disconnect, malformed/oversized messages, callback failures, ACK failures and socket errors reconnect with injected scheduling and bounded exponential jitter.
  `hello` resets the failure backoff; `warning` is informational.
  `disconnect` reasons `disabled` / `link_disabled` and terminal credential failures enter `DISABLED` without reconnecting.
  Recreate the transport after credentials/settings are repaired.
- Server Retry-After on connection discovery is a minimum delay (can exceed the normal backoff maximum).
  Jitter supplier returns a value in `[0,1)`; no sleeps or dedicated transport executor are created.

Documentation consulted: Slack Socket Mode and apps.connections.open references, and Fusion HTTP client/JSON documentation via Context7.
