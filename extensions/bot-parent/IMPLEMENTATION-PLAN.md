# Bot backend completion plan and implementation record

Status: implementation added 2026-11-16; local verification is recorded below and live acceptance remains separate.

## Scope and completion contract

- Docker, Nono and Kubernetes must all support Slack questions, model inference, ask_user, cancellation, bounded results and recovery.
- Backend limitations may constrain platforms, provisioning and supported provider protocols; they must not reduce Nono/Kubernetes to permanent startup rejection.
- Preserve existing GitHub configuration, task state, recovery identities and behavior when new shared-budget configuration is disabled.
- Plain Java and Fusion remain the implementation baseline; reuse the existing core RunCommand/AgentLoop rather than adding an adapter-specific loop.
- Provider credentials, Slack credentials, controller state and limiter state must remain outside agent authority.
- Implementation completion and live deployment acceptance are separate milestones.

## Baseline and gaps addressed

| Area | Baseline implementation | Work addressed in this implementation |
| --- | --- | --- |
| Shared execution | Docker, Nono and Kubernetes mechanics, feedback mounts/paths, bounded result reading and termination-aware lifecycle | Slack credential-isolated handoff for Nono/Kubernetes and backend-specific gateway lifecycle/recovery |
| Slack adapter | Admission, durable tasks/outbox, owner-scoped conversations, interactive feedback, worker and commands | Replace the Docker-only startup guard only after each additional handoff meets the same security/lifecycle contract |
| Provider gateway | Fixed provider-only TLS route, scoped attempt capability, bounded traffic and per-process concurrency | Backend-neutral preparation/listener deployment and shared request admission |
| Provider preparation | Selected-provider rewriting and secret separation | Remove Docker-specific settings/path/port assumptions from SlackProviderServices without changing legacy GitHub bindings |
| Shared host admission | Opt-in filesystem-backed attempt slots with durable uncertainty debt | This is not request concurrency and cannot substitute for the provider limiter |
| Provider concurrency | Per-gateway semaphore; GitHub has separate trusted forwarding paths | A shared-filesystem request limiter integrated at every participating trusted forwarding path |
| Tests | Fast protocol/domain/Fusion tests and broad reactor coverage | Retained real AgentLoop feedback test, plus new backend/limiter regressions; 500 ms is a performance target, not a test failure threshold |

## Shared architecture first

1. Introduce a backend-neutral trusted provider handoff: selected upstream policy, scoped capability, agent-visible endpoint, private gateway settings/secrets, provider-budget identity and durable runtime descriptor.
2. Keep backend transport/deployment details out of provider selection and credential rewriting.
3. Keep credentials out of agent configuration, agent environment, attempt PVC contents, profiles and diagnostic output; agent configuration receives only an attempt-scoped capability.
4. Persist gateway/runtime identities before resource creation and recover from durable descriptors, not only in-memory maps.
5. Own gateway lifetime through launcher quiescence, runtime termination and outstanding upstream-request settlement; unknown outcomes retain state/admission debt.
6. Preserve selected chat-completions-only behavior initially; do not silently enable Responses, Messages, arbitrary destinations, headers or alternate credential-bearing providers.

## Shared-filesystem provider request limiter

A mounted common filesystem is the selected implementation approach; no central limiter service is required for the initial supported topology.

### Trust and topology

- Share one operator-provisioned budget root among trusted forwarding processes for GitHub and Slack.
- Mount that root read/write only in controllers/gateways that acquire permits, never in agent containers, agent pods or Nono agent filesystem grants.
- Use a separate root/subtree and schema from attempt admission, queue state and attempt data.
- Initial supported topology is the same host/kernel with a local filesystem supporting the required locks and atomic/durable filesystem operations, including bind mounts and provisioned local Kubernetes volumes.
- For Kubernetes in this topology, constrain participating gateway pods to the budget-volume node; GitHub and Slack must genuinely reference the same files/inodes.
- Arbitrary NFS/RWX volumes, disconnected copies, multi-host lock recovery and independent clusters are not assumed safe merely because a path or PVC name matches.
- Multi-node/multi-host support needs explicit filesystem/locking qualification or a later coordinated service; unsupported requested topologies must fail closed.
- The guarantee covers participating managed inference traffic, not unrelated clients using the same upstream account.

### Identity and protocol

- Use explicit operator-owned provider/account budget IDs; aliases and different API keys for one account can map to the same ID.
- Do not infer account identity from a secret, model name or endpoint alone.
- Persistent schema fixes protocol version and slot count; conflicting participants fail closed rather than create independent budgets.
- Use stable slot lock files, OS locks and a same-JVM coordination registry; never unlink/replace live lock files or use an unlocked JSON counter.
- Acquire a request permit after authentication/route validation and before any upstream dispatch.
- Persist an uncertain reservation before dispatch; hold the permit through streaming response completion and close of the upstream exchange.
- Release a normal reservation only after known completion, or when it is proven that no dispatch occurred.
- A gateway crash, ambiguous dispatch, disconnect or timeout is not proof that upstream computation stopped; retain durable debt when termination is unknown.
- Do not reclaim debt solely because a process died, an OS lock became free or a TTL elapsed; document explicit reconciliation and use upstream termination evidence where available.
- Saturation uses bounded admission (initially immediate sanitized retryable rejection, with no unbounded queue or automatic replay of an ambiguous request).
- Existing per-gateway limits remain independent smaller bounds; never reserve a provider request permit across an entire task or an ask_user wait.

### Integration coverage

- Integrate the shared permit at ProviderServicesGateway's actual upstream exchange.
- Integrate GitHub RestrictedServicesGateway inference forwarding only, not context/Git traffic.
- Cover the existing relay forwarding route when selected; extract the limiter into a dependency-safe shared component rather than introducing a core-to-extension dependency.
- When a shared budget is enabled, reject any selected participating execution path that can still forward provider requests without it; do not advertise a global cap while direct/legacy routes bypass it.
- Keep disabled-budget behavior backward compatible and make strict enabled-budget validation explicit.
- Add private mount/provisioning configuration for Docker gateways, native controller services and Kubernetes gateway pods; validate identity, permissions, symlinks and conflicting slot counts.

## Functional Nono support

Existing native preparation, result contract, feedback directory and supervised registry recovery can be reused; the missing part is an agent-reachable credential-isolated provider transport under the restrictive profile.

1. Implement a provider-only native bridge with a narrowly granted pathname Unix socket, retaining the agent's network isolation.
2. Add the necessary scoped core provider transport integration: the current JDK HTTP path does not itself provide Unix-socket HTTP transport, so this is real new work, not a configuration switch.
3. Restrict that transport to the trusted prepared provider capability; no arbitrary socket path, destination forwarding, generic host RPC or CONNECT tunnel.
4. Make socket framing, request/response limits, deadlines, cancellation and response streaming explicit and tested; keep parser/transport scope minimal.
5. Bind the private socket before launching the agent, grant only the required socket path, and keep its parent private and protected against agent replacement.
6. Store upstream credentials and shared limiter access only in the trusted bridge/controller; the agent receives neither.
7. Include bridge identity and cleanup in durable attempt recovery; close only after launcher quiescence and runtime/request settlement, retaining uncertainty debt when necessary.
8. Preserve native feedback support and verify the actual RunCommand can use both feedback and inference through the native handoff.

Accepted initial limitations: Linux, a qualified Nono version with pathname mediation, dedicated non-root controller identity, trusted credential-free runtime, one selected chat-completions provider and no general outbound network. Unsupported platform/version/configuration fails before claiming work; supported configurations must actually execute rather than hit a blanket backend guard.

The socket/profile integration must be checked against the supported Nono release before implementing its profile fields; do not weaken to unrestricted TCP merely to make the backend start.

## Functional Kubernetes support

Existing single-use Job/Secret creation, restricted agent pod, attempt PVC subpaths, feedback mount and terminal-pod stop protocol can be reused.

1. Deploy a separate trusted provider gateway pod/service per attempt, rather than a credential-bearing sidecar sharing the agent pod's network authority.
2. Give only the gateway its upstream credential, TLS material and shared-budget volume; no upstream credential in the agent Secret/environment/PVC and no controller/queue/budget volume in the agent pod.
3. Rewrite the agent provider to the gateway's pinned TLS identity and scoped capability; readiness and identity verification precede agent launch.
4. Apply network isolation: agent to its authorized gateway only; gateway to the fixed upstream only; no general agent Internet, API-server or metadata access. Avoid broad DNS egress; provision only the resolution needed for the pinned gateway endpoint.
5. Treat an enforcing CNI and operator-provisioned isolated networking/storage as prerequisites; creating a policy object alone does not prove enforcement.
6. Extend durable runtime descriptors and recovery to gateway pod/service, private Secret and network policy identities without adopting pre-existing resources.
7. Stop agent creation, await launcher quiescence and terminal agent pods, settle/retain debt for provider requests, then remove gateway/service/policies/secrets in a safe order; ambiguity blocks retry and cleanup.
8. Reuse the existing feedback PVC path with bounded consistency checks and controller-side ownership validation.
9. For the initial local-filesystem limiter topology, use a separately provisioned trusted local budget volume and gateway node placement; do not mount the attempt PVC as the limiter or make agents participants.

Accepted initial limitations: provisioned attempt PVC plus separate private gateway/budget storage, static controller Kubernetes credentials, no in-pod service-account token for the agent, enforced network policy, public CA trust in the agent image, qualified same-host/local-volume budget topology and one selected chat-completions provider. General multi-cluster/distributed budget support is outside this iteration, not Kubernetes execution itself.

## Ordered implementation and evidence

1. Backend-neutral provider handoff/configuration and durable gateway descriptor, preserving legacy GitHub compatibility.
2. Filesystem request limiter, protocol/security tests and trusted forwarder integration for both bots, including all enabled routes.
3. Functional Nono bridge/provider transport and lifecycle tests; remove only Nono's blanket rejection after the supported path works.
4. Functional Kubernetes gateway orchestration/network policy/private storage and recovery tests; remove only Kubernetes' blanket rejection after the supported path works.
5. Retain an actual RunCommand/AgentLoop/ask_user integration regression with fake model transport and real tool dispatch, including answer propagation, cancellation and independent permission denial.
6. Update backend capability/configuration/deployment documentation and samples, including exact limitations and managed-traffic concurrency guarantees.
   Slack's POM must provide one opt-in profile and one Maven lifecycle command that build all controller, agent and gateway images, matching GitHub's workflow; ordinary builds must not create images.
7. Run focused tests, formatting, clean packaging and default-parallel core/shared/GitHub/Slack reactor tests; observe new test durations including fixture lifecycle against the 500 ms performance target, without artificial warm-up or excluded work.

Required regressions include shared permits across distinct JVMs; identical-account aliases across bots; streaming lifetime; saturation; cancellation/disconnect/timeout/crash debt; conflicting slot counts; unsafe files/mounts; same-JVM locking; disabled-budget compatibility; missing gateway/route/network prerequisites; secret exclusion; gateway readiness failure; launch/cancel races; recovery after partial resource creation; stale feedback; valid structured results and nonzero agent exit.

## Release acceptance, separately authorized

Prepare commands/checklists and local mocked tests now.
Actual image builds and live Nono, Docker/TLS, Kubernetes/CNI, Slack and architecture acceptance remain explicitly unverified until run.
No SSH, file transfer, server mutation, deployment or activation is authorized by this plan refinement.
Do not mark those checks complete based on mocked tests or a successful package build.

## Local implementation verification (2026-11-16)

Application settings and credential lookups in the bot production modules, provider budget, native transport and HTTP producer use Fusion `Configuration`.
There are no direct `System.getenv` calls or obsolete `fromEnvironment` factories in those production paths.
GitHub authentication/context clients retain lazy credential lookup, and both bots contribute configured credentials to shared secret redaction.

The Kubernetes gateway now permits never-started recovery only after deletion acknowledgement and terminal `Failed` status, together with explicit startup-error waiting state, `started=false`, zero restarts and no container identity or previous state.
Missing status, a nonterminal phase or ambiguous launch history does not authorize finalizer removal.
This remains conditional on the qualified healthy control plane/kubelet, ownership and finalizer prerequisites; mocked status is not live qualification.
Gateway lifetime includes bounded startup/shutdown headroom beyond the agent execution window.
Native prelaunch cleanup distinguishes positive in-process no-launch evidence from uncertain durable state, and bridge worker completion is signalled only after tracking removal.

Executed successfully, without image profiles or test-parallelism overrides:

```sh
mvn -q -pl core -am clean package
mvn -q -pl tui,extensions/bot-parent/slack-bot,extensions/bot-parent/github-bot -am clean package
```

The full clean reactor command passed with the default concurrent test-class configuration (8 JUnit workers, maximum pool size 16) after the typed native-provider configuration and removal of 500 ms performance gates.
The successful run included formatting checks, compilation, tests and packaging; earlier revisions also passed twice consecutively before the latest changes.
The final Surefire reports for the implementation modules and TUI recorded:

| Module | Tests reported | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| core | 2043 | 1 | 0 | 0 |
| tui | 768 | 0 | 0 | 0 |
| bot-gateway | 15 | 0 | 0 | 0 |
| bot-runtime | 246 | 1 | 0 | 0 |
| github-bot | 811 | 8 | 0 | 0 |
| slack-bot | 77 | 0 | 0 | 0 |
| Total | 3960 | 10 | 0 | 0 |

Thus 3950 tests passed and 10 were skipped, not accepted as live checks.
The two Docker gateway budget-forwarding cases took 0.174 and 0.162 seconds in the final run.
The generated Fusion configuration documentation includes `yca.native.provider.socket`.
These are local observations, not portable runtime guarantees.

Earlier default-concurrent runs exposed Linux `ETXTBSY` on freshly written fake executables and a Worker recovery termination-confirmation failure; the previous single-worker pass was not sufficient acceptance.
RepositoryCacheTest and WorkerTest now execute a packaged read-only launcher that reads generated shell bodies as input, avoiding direct execution of a potentially writable inode.
A regression holds the body open for writing during launch and verifies arguments, environment and exit status; another verifies behavior replacement at the same fixture path.
The historical Worker's nested launch exception was not retained, so its precise original cause remains unconfirmed; its safety assertions pass in the focused run and both full concurrent builds.
AnsiShellFrontendTest already held stream resource locks, but ordinary unrelated JUL writers do not acquire them.
It now uses narrowly scoped JUnit isolation for its JVM-global stream capture, like RunCommandTest, without disabling suite concurrency or the frontend's internal concurrency tests.
An intermediate concurrent build also exceeded the former 500 ms limit in a test launching two child JVMs; the independent process-lock and crash-debt scenarios have separate tests, each retaining a real child JVM.
Subsequent concurrent failures exposed unused HTTP/TLS initialization in native and Kubernetes test fixtures, and a feedback eligibility test coupled to loopback HTTP scheduling.
Native tests now use a no-network delegate while retaining real Unix-socket streaming; disconnect and deadline cases each have their own fixture.
Kubernetes in-process fixtures supply their client directly rather than wrapping an unnecessarily initialized JDK client.
The feedback eligibility regression uses real request publishers/response subscribers over an in-process transport; existing real HTTP transport tests remain intact.
Recovery failure fixtures persist all resources before deliberately losing the Pod-create response, then separately verify non-ready and interrupted recovery retain resources without destructive calls.
The TUI test caller supplies explicit typed native-provider configuration to HttpClientProducer.
The core socket property is `yca.native.provider.socket`, bound by a dedicated Fusion root configuration and automatically available through the `YCA_NATIVE_PROVIDER_SOCKET` environment alias.
After further fake-Docker timing failures, the 500 ms assertion wrappers and matching annotation performance gates were removed at the user's request; behavior assertions, operational deadlines and separate hang safeguards remain.
The 500 ms duration is an observation target, not a portable pass/fail contract.
The first full build after these changes exposed a cancellation-test cleanup race: it interrupted the worker after the launcher had already acknowledged cancellation, potentially disrupting the final state write and causing additional stop calls.
The fixture now interrupts only if the launcher has not observed cancellation; the original stop-order, host-slot retention and final-state assertions remain intact.
No production checks or safety assertions were weakened, no retries or sleeps were added, and the default parallelism remains unchanged.

The focused configuration/provider/backend regression run also passed (234 tests, no failures/errors/skips).
Offline checks passed: image-profile validator (5), controller-wrapper tests (3), updater tests (40), Java sample/link validator (24 links/anchors), wrapper shell syntax and `git diff --check`.
The effective `image-all` lifecycle was inspected earlier; none of its four image executions was run.

Actual images, architecture/tool compatibility, live Slack/GitHub/provider behavior, Nono outer isolation, Kubernetes CNI/node/metadata isolation and deployed shared-filesystem semantics remain unverified.
Use `ACCEPTANCE.md` for the separately authorized operational gate.
No image build, remote operation, deployment, activation or commit was performed.

### Authorized rebuild follow-up (2026-11-16)

After the user requested `./generated/rebuild.sh`, the new host-admission and native-provider configuration roots were annotated with `@JsonModel` and the documentation schema regression was extended.
The schema generator now keeps independent roots such as `bot.admission` outside `yca`, while retaining `yca.native.provider.socket` under `yca`.
The focused schema regression passed, then the requested script completed with exit status 0: parallel reactor install/tests, minisite generation, GraalVM native compilation and the script's configured SCP transfer all succeeded.
The full reactor reports recorded 4447 tests, 4436 passed and 11 skipped, with no failures or errors; the documentation module's 8 tests passed.
This explicitly authorized native build/transfer supersedes the earlier no-remote-operation record for this follow-up only.
It does not establish live Docker/Nono/Kubernetes/Slack acceptance, service activation or remotely verified artifact identity; no commit was created.
