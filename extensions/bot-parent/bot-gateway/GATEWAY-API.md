# Restricted services gateway API

## Entrypoint and scope

`io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway --config /services/config.json`
starts a TLS fixed-provider-only gateway; `--check` performs a bounded TLS readiness probe.
It has no GitHub RPC, Git, Slack API, or CONNECT routes and is not an arbitrary proxy.
The existing GitHub gateway remains unchanged.
`DockerRestrictedServices` selects this entrypoint for trusted runtime identifiers beginning with `yca-slack-`; all other identifiers retain the legacy GitHub entrypoint.
The operator-supplied services image must contain the corresponding entrypoint.

## Configuration contract

JSON fields: `serverName`, `port`, `keyStore`, `keyStorePasswordEnv`, `inferencePath`, `inferenceTarget`, `lifetimeSeconds`, `timeoutSeconds`, `maxInferenceBytes`, `maxResponseBytes`, `maxConcurrent`.
The inference route is exactly `/inference/<lowercase UUID>/chat/completions`.
Only authenticated JSON POST requests without a query are accepted.
The upstream is a single operator-approved HTTP URL with a canonical private or loopback numeric IPv4 address, explicit port and fixed unescaped path.
No DNS resolution, redirects, ambient proxies, caller-selected destinations, or forwarded caller headers other than `Content-Type` are permitted.
Private upstream HTTP requires an operator-trusted control network.
Requests, responses, headers, active exchanges and lifetime are bounded.
`Settings.reject(method, uri, headers, boundPort, expectedAuthorization)` is the transport-independent request policy, returning zero or the rejection HTTP status; the TLS handler uses it before creating any upstream request.

Example settings (no secrets):

```json
{
  "serverName": "attempt-services.yca.internal",
  "port": 8443,
  "keyStore": "/services/tls.p12",
  "keyStorePasswordEnv": "YCA_SERVICES_KEYSTORE_PASSWORD",
  "inferencePath": "/inference/12345678-1234-1234-1234-123456789abc/chat/completions",
  "inferenceTarget": "http://172.20.0.1:8080/v1/chat/completions",
  "lifetimeSeconds": 3600,
  "timeoutSeconds": 120,
  "maxInferenceBytes": 1048576,
  "maxResponseBytes": 16777216,
  "maxConcurrent": 4
}
```

Secrets are supplied separately: `YCA_SERVICES_PROVIDER_TOKEN` (43 URL-safe characters), optional `YCA_SERVICES_UPSTREAM_TOKEN`, and the configured keystore password environment variable (normally `YCA_SERVICES_KEYSTORE_PASSWORD`).
The gateway never logs environment contents or transport exception details.
The PKCS12 store must contain exactly one non-CA private-key certificate with the exact server-name SAN.
The TLS private key must remain outside every agent attempt mount; Docker mounts it read-only into the gateway only.
Attempt preparation is responsible for this separation and private settings ownership.
The shared `DockerExecutionBackend` already rejects settings or keystore real paths under agent-mounted paths; callers must retain that check.

## Concurrency and current limits

`maxConcurrent` is positive (1..64) and limits active requests for one gateway instance, including streaming response lifetime.
It is NOT an account-wide or cross-container cap.
The current Docker lifecycle creates one gateway process per attempt, so a process-wide named semaphore would not aggregate those attempts.
An account-wide cap requires a shared deployment gateway with trusted route registration/lifecycle or an external coordination mechanism; neither is introduced here.
Do not disable any existing provider account-wide limiter on the assumption this gateway replaces it.

## Image

The opt-in `image-services` profile in `bot-gateway` builds a shared services image with the neutral entrypoint and Jib classpath file.
Ordinary builds do not build images.
Properties are `bot-gateway.services.base-image` (default `eclipse-temurin:25-jre`), `bot-gateway.services.image` (default `yca-services:local`) and `bot-gateway.image.architecture` (default `amd64`).
Pin the base image to an operator-vetted digest for deployment.
Invoke the `jib:dockerBuild` or `jib:build` goal with `-Pimage-services -pl extensions/bot-parent/bot-gateway` after compiling the module; the profile has no automatic package binding.
Use this image for Slack's `executor.docker.services.image`; it does not contain the legacy GitHub gateway and must not replace a GitHub services image.

## Integration boundary

Slack preparation must write this neutral settings schema and pass only the provider/upstream/password secrets.
The parent-owned Slack sandbox fail-closed guard can be removed only after that preparation is wired and verified.
Public dependency CONNECT remains the separate existing `EgressGateway` with its existing deny-by-default policy.
