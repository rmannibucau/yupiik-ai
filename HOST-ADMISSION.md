# Host admission API

Shared API (bot-runtime): `io.yupiik.ia.agent.bot.runtime.admission`.

- `new HostAdmission(HostAdmissionConfiguration configuration)`.
- `Optional<HostAdmission.Lease> tryAcquire() throws IOException`: nonblocking; empty means busy/full. Disabled mode returns a no-op lease.
- `Lease implements AutoCloseable`: `confirmTermination()` records that no runtime remains; `markUncertain()` revokes that confirmation before launch becomes possible. `close()` releases the OS lock, but removes the durable reservation only after confirmation. A newly acquired enabled lease is uncertain by default.
- `validateDisjoint(Path... controllerAndAttemptRoots)` rejects overlap with state/attempt roots. Call before admission, including any default attempt location.

Trusted configuration is a separate `@RootConfiguration("bot.admission")` record:

- `root`: blank by default (disabled); otherwise an absolute, controller-only directory, shared by all participating bots on one host.
- `slots`: default 1, positive, identical for every participant. A persistent version/count schema is checked under a stable lock. Never delete or replace schema or slot lock files, even while idle.
- `trustedGroup`: blank by default requires current OS user ownership and private permissions. Explicit group name enables a pre-provisioned setgid trusted-controller group root for cross-user bots. All members must be trusted controllers, never agents. No world access or symlink components are permitted. This is not an untrusted shared-directory protocol.

Slack lifecycle contract (parent integrates RunCommand): validate root disjointness; acquire immediately before claiming/preparing work; if empty, do not claim. Retain the lease through runtime termination. Confirm only after positive termination (or proof launch was never possible), and mark uncertain before every possible launch. Always close; uncertain close intentionally retains durable debt. Do not use plain try-with-resources without confirming the lifecycle outcome.

GitHub Worker uses the same contract; existing per-worker/task leases remain independent.

Reservation files survive process death. A free slot lock with a reservation means unknown termination and blocks admission for the entire budget, including in new processes/instances. No timeout, PID heuristic, or automatic reclaim is used. Operators must stop participating controllers, positively terminate/reconcile all affected runtimes, and only then remove the relevant `slot-N.reservation` files. Never remove `slot-N.lock`, `schema.lock`, or `schema`. Reservation files contain controller PID diagnostics, not runtime credentials. A crash before launch can conservatively require this same manual procedure.

Use a local filesystem with reliable process file locks and durable writes, not NFS or agent-mounted storage. The root and its ancestors must remain under trusted operator control for the lifetime of all participants. This feature is opt-in and only constrains participating controllers; it is not a general host resource isolation mechanism.
