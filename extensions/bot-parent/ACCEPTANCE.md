# Bot backend acceptance

This is an operator checklist, not a completed acceptance report or authorization to build, deploy, contact a provider, post to Slack/GitHub or mutate a server.
Source/static validation does not certify a live backend, image, CPU architecture, CNI or shared filesystem.
Keep unchecked items pending until evidence is recorded for the exact reviewed artifacts and deployment.
Local reactor and offline-check results are recorded in `IMPLEMENTATION-PLAN.md`; they do not complete any live item below.

## Offline checks (repository root, no Maven)

These existing checkers read local source and samples; they do not start bots, containers or network clients.
Use Java 25 or later for the Java source launcher.
The existing XML-profile regression checker requires Python 3; it is not an image build or a new script supplied by this checklist.
Do not mask failures with `|| true`, rewrite expectations to obtain green output, or treat static XML as image smoke evidence.

```sh
java documentation/src/test/ValidateBotSamples.java
sh -n extensions/bot-parent/slack-bot/src/main/docker/controller.sh.example
python3 -B documentation/src/test/validate_slack_image_profile.py
```

- [ ] Record each command, exit status and exact checkout/artifact identity, including uncommitted integration changes.
- [ ] Verify the four `image-all` roles remain controller, agent, provider-services and dependency-egress; retain the single four-image command in the deployment page.
- [ ] Review any failed checker before proceeding; local links/sample wiring do not validate rendered HTML, configuration binding or actual image contents.
- [ ] Obtain relevant Java unit/integration results from implementation owners separately; the commands above do not run them.

Authoritative deployment/configuration details are self-contained in:

- `documentation/src/main/minisite/content/slack-bot/configuration.adoc`
- `documentation/src/main/minisite/content/slack-bot/deployment.adoc`
- `documentation/src/main/minisite/content/bots/cohosting.adoc`

## Assembled artifact review (operator opt-in)

Use only an already assembled, reviewed local JAR in a disposable environment with no credentials or enabled controller configuration.
The explicit `--help` flag is intentional; do not substitute a bare controller invocation as a help probe.
This command does not assemble the JAR and has not been executed by this checklist.

```sh
(
  set -eu
  : "${YCA_ARTIFACT_REVIEW:?Set only after approving local artifact inspection}"
  test "$YCA_ARTIFACT_REVIEW" = yes
  : "${SLACK_BOT_JAR:?Set the reviewed existing local Slack bot JAR path}"
  test -f "$SLACK_BOT_JAR"
  java -jar "$SLACK_BOT_JAR" --help
)
```

- [ ] Confirm `slack bot run`, `--parallelism`, headless logging options and exact environment/list binding from the assembled CLI; do not assume source annotations prove successful assembly.
- [ ] Verify controller/agent identities, public CA trust, credential-free layers and tool/architecture compatibility.
- [ ] Verify actual gateway images contain Java and `/app/jib-classpath-file` with application classes AND dependencies.
- [ ] Verify Docker gateway startup/check and Kubernetes gateway command/readiness use `java -cp @/app/jib-classpath-file <main>` successfully in the approved sandbox.
- [ ] If parent packaging/runtime layout is not integrated, record a parent blocker; `/app/classes` alone is not a dependency classpath workaround.

No Maven, remote image build/push/pull, updater, SSH, service activation or cluster mutation is part of the offline checks.
Image builds and deployment commands remain operator actions requiring separate authorization.

## Live acceptance gate (not executed)

Live work requires a separately approved disposable host/namespace, test Slack workspace/channel/requester, bounded test provider and cost limit, approved artifact digests, secrets provisioning and a recovery owner.
Never use production tokens/state or copy secrets into command arguments, logs or a checkout.
Review and install a dedicated launcher that implements the chosen backend prerequisites before using this optional foreground entry point.
The launcher below is an operator-owned executable path, not a generated updater or a command string to evaluate.
Do not point it at production or enable automatic service restart for initial acceptance.

```sh
(
  set -eu
  : "${YCA_LIVE_ACCEPTANCE:?Explicit approval required for live disposable acceptance}"
  test "$YCA_LIVE_ACCEPTANCE" = approved-disposable-only
  : "${YCA_ACCEPTANCE_LAUNCHER:?Set an operator-reviewed disposable backend launcher}"
  test -x "$YCA_ACCEPTANCE_LAUNCHER"
  "$YCA_ACCEPTANCE_LAUNCHER" slack bot run --parallelism 1
)
```

This foreground command can contact Slack/provider services and create sandbox resources only if the operator deliberately supplies the approval and prepared launcher.
It is not an automatic test, installation or permission to run other commands.

### Common behavior and isolation

- [ ] Exact workspace/channel/requester admission; empty lists deny all; bot/subtype events, missing/wrong mentions, oversized inputs and unapproved shared-channel audiences are rejected.
- [ ] One allowed natural-language mention produces one bounded answer in the exact thread; unmentioned replies do not enqueue work.
- [ ] Requester-scoped context, pending questions, expiry, cancellation and replay protection hold across users and restarts; `ask_user` retains worker/host slots while waiting.
- [ ] Inspect sandbox environment/mount/configuration without publishing secrets: no Slack/GitHub keys, permanent provider key, TLS leaf key, controller state, host home, Docker socket or budget storage is agent-visible.
- [ ] Only the selected explicitly modeled COMPLETIONS provider is staged; its key becomes an attempt capability; direct-credential and legacy relay handoffs fail closed.
- [ ] Negatively test real network/file access, not model refusals; history/repositories/files/URLs are not implicitly authorized.
- [ ] Wrong capability, route, Host/TLS name, unsupported API, redirect and expired attempt cannot reach an alternative destination.
- [ ] Exercise ACK redelivery, queue saturation, result identity/bounds, question timeout, throttling, disk pressure, failed cleanup and ambiguous publication without blind model rerun/repost.
- [ ] Confirm runtime AND gateway termination before freeing host admission or deleting handoff/private state; retain uncertainty for manual reconciliation.

### Docker

- [ ] Local trusted daemon, identical host/controller attempt paths, separate isolated agent and trusted control bridges, no host networking or published gateway port.
- [ ] `slack.executor.docker.services` is configured; agent joins only its isolated bridge and cannot reach the numerical upstream/control bridge directly.
- [ ] Upstream is fixed canonical numerical private IPv4 HTTP with explicit port, not a public HTTPS API or private DNS name; the unencrypted hop is separately trusted/confined.
- [ ] Public CA only in the agent image; TLS keystore/key/password and upstream key remain gateway/controller-only.
- [ ] For a shared provider budget, the parent launcher forwards ROOT/ID/SLOTS and mounts the trusted source root into the controller at the host path; the gateway bind at `/services/budget` sees the same ledger.
- [ ] Explicitly opt into the wrapper's conditional request-budget mount/forwarding; the environment sample leaves all three settings commented out and provisions nothing.

### Qualified Linux Nono

- [ ] `slack.executor.backend=NONO`; dedicated non-root credential-free read-only `runtimeRoot`, `agentExecutable` inside it, explicit Nono executable and disjoint private attempt/state roots.
- [ ] Exactly `nono 0.81.0`, Linux pathname AF_UNIX mediation and supervised sessions are qualified on the actual kernel; unsupported variants fail closed.
- [ ] `slack.providerServices.upstream` is fixed HTTPS matching the selected provider; normal TLS validation and no redirects are enforced.
- [ ] `slack.providerServices.nativeBridge.socketRoot` is private 0700, controller-owned, symlink-free, short enough for the full 100-byte per-attempt pathname, and outside runtime/attempt/state/budget grants.
- [ ] `slack.providerServices.nativeBridge.qualified=true` and `outerIsolationQualified=true` are set only after qualifying a stronger outer boundary hiding unrelated host IPC endpoints; neither assertion is evidence by itself.
- [ ] Exact pathname bridge works while TCP, abstract sockets and unrelated pathname sockets are denied; provider protocol cannot select an upstream destination.
- [ ] Record the Nono 0.81.0 multithreaded sockaddr TOCTOU limitation; do not claim race-free host IPC isolation or hostile-workload safety without stronger outer isolation.
- [ ] Crash recovery retains durable session/intent/socket identity; bridge removal follows launcher quiescence and confirmed agent exit.

### Qualified Kubernetes

- [ ] Static private controller client credentials and verified API TLS; kubeconfig `exec`/`auth-provider` plugins are rejected.
- [ ] `slack.providerServices.upstream` is numerical private IPv4 HTTP with explicit port, NOT the HTTPS upstream supported by Nono.
- [ ] Current `slack.providerServices.kubernetes` fields reviewed: `image`, `tlsSecret`, `serverName`, `nodeName`, `budgetClaim`, `serviceAccount`, `port`, `qualified`.
- [ ] Separate trusted gateway Pod/Service/private Secret, never an agent sidecar; agent receives only TLS endpoint/host alias/capability.
- [ ] `tlsSecret` contains `gateway.p12` for `serverName`; only public CA trust is in the agent image; upstream/TLS credentials remain gateway-only.
- [ ] Explicit controller `YCA_PROVIDER_BUDGET_ROOT`, `YCA_PROVIDER_BUDGET_ID`, `YCA_PROVIDER_BUDGET_SLOTS`; separate qualified local `budgetClaim`, distinct from the agent attempt `claim`, mounted only in trusted gateway at `/budget`.
- [ ] Named node/storage qualification proves same-kernel local coherent locking/durable writes and UID 10001 ownership, root 0700/files 0600, no fsGroup rewriting; no cross-node/distributed ledger claim.
- [ ] Enforcing CNI ingress AND egress before first packet including Service DNAT, no additive allow policies; independently enforced node/metadata/host-service isolation.
- [ ] Agent cannot reach provider upstream directly, cluster API, node services, metadata, DNS, other attempts or private budget/Secrets.
- [ ] No service-account token mounts in either agent or gateway; namespace/RBAC/admission prevent untrusted resource/label/policy/Secret changes or credential injection.
- [ ] Kubernetes >= 1.27 and healthy control plane/kubelet preserve terminal-confirmation finalizers; no force deletion to bypass unknown termination.
- [ ] Set `qualified=true` only after evidence; verify agent terminal confirmation precedes gateway shutdown and uncertain creation retains its private anchor/resources.
- [ ] Verify accepted-but-never-started gateway recovery after image/configuration failure: deletion acknowledgement plus terminal Failed and explicit never-started status are required; missing status or ambiguous launch history retains the finalizer and supporting resources.
- [ ] Exercise delayed gateway readiness and agent scheduling near the execution deadline; confirm the configured gateway lifetime headroom covers the permitted execution and cleanup windows.

### Shared provider request ledger and host admission

- [ ] Distinguish host-attempt `bot.admission` from per-request ROOT/ID/SLOTS and provider-side rate/token/spend controls.
- [ ] For shared request participants: same stable `<ID>.budget` inode, same UID/owner, same kernel/local filesystem, private 0700 root/0600 single-link regular files, coherent OS locks and durable force operations; no symlinks or agent mount.
- [ ] Keep controller identities separate; unlike group-shared host admission, request-ledger sharing is not enabled by widening group permissions. Use reviewed same-UID trusted forwarding or independent budgets plus an external cap.
- [ ] ROOT/ID/SLOTS are all present and identical in meaning, or fully absent where optional; Kubernetes rejects disabled/partial budgets. Different storage with the same strings is not a shared cap.
- [ ] Overlap requests from independently launched approved participants: held streams consume slots until upstream EOF, not headers; saturation rejects without a queue or bypass.
- [ ] Prove crash-before-dispatch and uncertain post-dispatch cancellation/disconnect/deadline retain durable debt; corrupt/partial ledger and slot-count mismatch fail closed.
- [ ] Stop all participants and independently reconcile outstanding upstream work before operator ledger repair; no TTL, PID guess, automatic reclaim, deletion/replacement of active ledger or supplied reset command.
- [ ] Saturate host admission independently across bots; unknown runtime termination retains separate durable `slot-N.reservation` debt. Do not remove stable schema/lock files.
- [ ] Explicitly list unmanaged/direct provider traffic outside the ledger; do not claim global provider concurrency, request rate, tokens or spend are capped by cooperative local admission.

## Evidence record

For each executed item, record date, operator, exact command, exit status, reviewed source/artifact digests, architecture, kernel/backend version, filesystem/mount identity, CNI/policy evidence and redacted observations.
Record failures and unverified prerequisites as blockers, not passes.
Live Slack/GitHub behavior, both CPU architectures, actual image startup, shared-filesystem multi-process acceptance and real cluster enforcement remain unverified until such records exist.
No command in this file authorizes a commit, deployment or server operation.
