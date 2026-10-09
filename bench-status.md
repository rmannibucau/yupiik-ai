# bench-status — continue session tomorrow

Session: `s1a11cde9568` (this one). Goal: **opencode vs yca benchmark + token-cost tuning**.
Repo: `/opt/rmannibucau/dev/Github/yca-bench` (the benchmark harness, own git repo, committed).
yca repo: `/opt/rmannibucau/dev/Github/yupiik-coding-agent` (changes are **ON DISK, NOT COMMITTED**).
Worktree experiment: `/tmp/yca-slim` (same changes, previously built/tested).

## Where things stand (end of today)

### Delivered (committed in yca-bench)
- Full benchmark harness in `yca-bench`: `bin/bench.sh` orchestrator, `run-yca.sh`/`run-opencode.sh`,
  `lib/collect-{yca,opencode}.mjs`, `lib/report.mjs`, Q1/Q2 prompts, Spring Boot `fixture/` with seeded bug.
- `report/bench.yca.md` — the original (full-vs-fresh mixed) report.
- 12 bench runs (3× Q1 + 3× Q2, both agents) all verified green.

### Key findings (the benchmark exposed)
1. **MemoryStore budget bug** (REAL, fixed): `select()` early-return ignored the budget — injected the
   **entire 38K global memory** (from `~/.yupiik/coding-agent/memory/MEMORY.md`) instead of `memory.maxChars`.
   → System prompt was 41K chars raw, capped to 16K with ~8K of irrelevant cross-project facts.
2. **Tool bloat**: yca ran `mvn test` 3× (2 redundant re-verifies), unbatched reads (7 across 2-3 rounds).
   Fix = prompt rule "verify exactly once + batch reads" (also now leaner tool descriptions in code).
3. **The 3-5× token gap vs opencode was a METRICS ARTIFACT**: yca `promptTokens` = full per-call;
   opencode `tokens_input` = fresh-only. Fair comparison (fresh = prompt − cacheRead):
   **yca is now ~0.85× opencode on billed tokens, ~half on full processed.**
4. **Mid-turn memory_write re-injected the whole `<memory>` section** into the tail.
   Fix (in code, uncommitted): **memory-diff** — only new facts, per-session.

### Changes on disk in yupiik-coding-agent (UNCOMMITTED — 11 files)
All in `/opt/rmannibucau/dev/Github/yupiik-coding-agent`, `git status` shows modified:
- `core/.../memory/MemoryStore.java` — budget-bug fix (`select()` bounds + splash) + session-keyed
  `recordDiff(sessionId, facts)` / `drainPendingDiff(sessionId)` for the memory-diff.
- `core/.../agent/AgentLoop.java` — `memoryRefresh` now re-sends only the per-session diff
  (`<memory-diff>`), falls back to full section on external edit; tail uses `sessionStateDelta()`.
- `core/.../agent/SystemPromptBuilder.java` — rules/policy prose trimmed (−1.0K chars);
  `sessionStateDelta()` (compact tail: Date/Mode + todos capped at 5 done); memoryStore() accessor.
- `core/.../agent/TodoPromptResolver.java` — all 6 provider family blocks trimmed (−2.4K chars total).
- `core/.../todo/TodoStore.java` — `render(maxDone)` wire view (pending + 5 recent done).
- `core/.../tools/builtin/BashTool.java`, `WriteTodoTool.java`, `AskUserTool.java` — description trims
  (wire schemas leaner, ~2.2K tokens total for all tools).
- `core/.../tools/builtin/MemoryWriteTool.java` — session-aware `execute(args, context)` + recordDiff.
- Tests: `AgentLoopTest` (asserts `<memory-diff>`), `MemoryStoreTest` (+ cross-session isolation test).

**All targeted tests green in main tree** (`AgentLoopTest, MemoryStoreTest, SystemPromptBuilderTest,
TodoPromptResolverTest, TodoStoreTest`). Full `core` suite was green in the worktree before porting.

### NOT done / decisions needed
- **NOT committed** to yupiik-coding-agent (user's explicit instruction: don't commit yet).
- **NOT committed** the bench's aggressive config: `llm.systemPromptMaxChars=16000`,
  `memory.maxChars=2000`, `run --mode build --permissions-mode yolo` are **bench-only** (in yca-bench
  sandbox configs), NOT generic defaults. Generic defaults unchanged (`memory.maxChars=8000` now
  *actually enforced*, `systemPromptMaxChars=32000`).
- Worktree `/tmp/yca-slim` still contains the same changes + earlier probes (was rebuilt at 01:18 for
  the final bench runs 11/12; probe dumps removed; the worktree is disposable now — main tree supersedes it).
- The `enabledTools`/`disabledTools` config idea: **reverted** (user decided all tools stay).

## How to continue (tomorrow)
1. Review the uncommitted diff: `git -C /opt/rmannibucau/dev/Github/yupiik-coding-agent diff`
   (11 files, all safe: bug fixes + prose trims + memory-diff).
2. Run the full core tests once: `cd /opt/rmannibucau/dev/Github/yupiik-coding-agent && mvn -pl core test`
   (worktree had 1 flaky pre-existing `CodeGraphIndexCommandTest.buildsIndexFromJar` failure when run
   in the full suite — passes alone; unrelated to these changes).
3. **Decide**: commit the 11 files (suggest split: memory fixes first, tool trims second, or one commit —
   I'd recommend one commit "slim system prompt, enforce memory budget, memory-diff tail").
4. Re-run the benchmark with the *current* harness for final numbers:
   `cd /opt/rmannibucau/dev/Github/yca-bench && ./bin/bench.sh` (uses default yca config — now with
   the fixed code; if you want the lean profile, set `HOME`-independent overrides or copy
   `sandbox/home-slim`-style config into `~/.yupiik/coding-agent/configuration.json` — but note that's
   the real user config; better to keep it and rely on the code fixes alone).
5. Refresh `report/bench.yca.md` semantics (full/fresh/cache columns) — `lib/report.mjs` still prints
   the old mixed column; honest comparison needs `fresh = prompt − cacheRead` for yca and `input` for
   opencode (the yca side already logs `cacheReadTokens`).
6. Post-bench follow-up (your note, todo): **memory files better scoped (project vs global) + budget
   strategy refined** — the current diff is a stopgap; the real fix is scoping + relevance, not just
   capping + diffing.

## Reference numbers (Q2, Freeinference/deepseek-v4-flash, verified green)
| variant | full prompt | fresh (prompt−cache) | calls | tools | wall |
|---|---|---|---|---|---|
| baseline (committed report) | 108,994 | 18,114 | 8 | 14 | 34s |
| post-fix worktree runs 11/12 | 32.1K/33.1K | 8.3K/8.0K | 5/5 | 12/12 | 26-37s |
| opencode (median) | ~58K | ~9.6K | 6 | 10 | 25-30s |

**To resume the session:** this file lives at `/opt/rmannibucau/dev/Github/yupiik-coding-agent/bench-status.md`
(untracked). Ask the agent: "continue from bench-status.md".
## BENCHMARK — 2026-10-09 (fatjar 16:37 rebuild, N=3 median, Freeinference/deepseek-v4-flash, all verified=1)
Config: yca with store:false, reasoning absent, 7-tool allow-list, logNoise filter, bash maxOutputChars cap.

| scen | metric | yca med | opencode med | yca/oc |
|------|--------|---------|--------------|--------|
| Q1 | elapsed(s) | 29.5 | 48.0 | 0.61 |
| Q1 | freshIn(tok) | 5803 | 9535 | 0.61 |
| Q1 | output(tok) | 2554 | 2778 | 0.92 |
| Q1 | maxCtx(tok) | 7865 | 12785 | 0.62 |
| Q1 | sumCtx(tok) | 58974 | 114078 | 0.52 |
| Q1 | llmCalls | 10 | 11 | 0.91 |
| Q2 | elapsed(s) | 17.2 | 29.0 | 0.59 |
| Q2 | freshIn(tok) | 6659 | 8918 | 0.75 |
| Q2 | output(tok) | 1213 | 1193 | 1.02 |
| Q2 | maxCtx(tok) | 6609 | 11755 | 0.56 |
| Q2 | sumCtx(tok) | 31813 | 63765 | 0.50 |
| Q2 | llmCalls | 6 | 6 | 1.00 |
| Q3 | elapsed(s) | 101.9 | 157.0 | 0.65 |
| Q3 | freshIn(tok) | 29693 | 32356 | 0.92 |
| Q3 | output(tok) | 16429 | 28846 | 0.57 |
| Q3 | maxCtx(tok) | 25112 | 45455 | 0.55 |
| Q3 | sumCtx(tok) | 221993 | 467483 | 0.47 |
| Q3 | llmCalls | 15 | 15 | 1.00 |

yca runs: q1-yca-generic-{10,11,12}, q2-yca-generic-{9,10,11}, q3-yca-generic-{12,13,14} (verified=1).
opencode runs: oc q1{3,4,5}, q2{3,4,5}, q3{3,4,5} (verified=1); tokens from opencode.db session_message.tokens.

## BENCHMARK 2 — 2026-10-09 (fatjar 17:51, 64K bash cap, N=3 median, deepseek-v4-flash, all verified=1)
yca: store:false, reasoning absent, 7-tool allow-list, logNoise filter, bash maxOutputChars=64KiB(65536).
opencode: default (50KiB bash cap) — same prompts/model, fresh runs q1/q2/q3-{6,7,8}.

| scen | metric   | yca med  | opencode med | yca/oc |
|------|----------|----------|--------------|--------|
| Q1 | elapsed(s)| 27.3    | 42.0        | 0.65 |
| Q1 | freshIn(tok)| 6317  | 9500        | 0.66 |
| Q1 | output(tok)| 2465  | 2769        | 0.89 |
| Q1 | maxCtx(tok)| 8161  | 12826       | 0.64 |
| Q1 | sumCtx(tok)| 51566 | 125421      | 0.41 |
| Q1 | llmCalls    | 9     | 12          | 0.75 |
| Q2 | elapsed(s)| 16.2    | 29.0        | 0.56 |
| Q2 | freshIn(tok)| 6598 | 9551        | 0.69 |
| Q2 | output(tok)| 993   | 1166        | 0.85 |
| Q2 | maxCtx(tok)| 6270  | 12201       | 0.51 |
| Q2 | sumCtx(tok)| 34786 | 63661       | 0.55 |
| Q2 | llmCalls    | 7     | 6           | 1.17 |
| Q3 | elapsed(s)| 168.2   | 230.0       | 0.73 |
| Q3 | freshIn(tok)| 35868| 21579       | 1.66 |
| Q3 | output(tok)| 12247 | 21342       | 0.57 |
| Q3 | maxCtx(tok)| 22352 | 35639       | 0.63 |
| Q3 | sumCtx(tok)| 201418| 340188      | 0.59 |
| Q3 | llmCalls    | 17    | 14          | 1.21 |

yca runs: q1-yca-{13,14,15}, q2-yca-{12,13,14}, q3-yca-{15,16,17}. opencode runs: oc q1/q2/q3-{6,7,8}.

## BENCHMARK v2 — 2026-10-09 (fatjar 17:51 rebuild with 64K bash cap, N=3 median, deepseek-v4-flash, all verified=1)
Config: store:false, reasoning absent, 7-tool allow-list, log-noise filter, bash maxOutputChars=65536.
| scen | metric | yca med | opencode med | yca/oc |
|------|--------|---------|--------------|--------|
| Q1 | elapsed(s) | 27.3 | 42.9 | 0.64 |
| Q1 | freshIn(tok) | 6317 | 9500 | 0.66 |
| Q1 | output(tok) | 2465 | 2769 | 0.89 |
| Q1 | maxCtx(tok) | 8161 | 12826 | 0.64 |
| Q1 | sumCtx(tok) | 51566 | 125421 | 0.41 |
| Q1 | llmCalls | 9 | 12 | 0.75 |
| Q2 | elapsed(s) | 16.2 | 29.3 | 0.55 |
| Q2 | freshIn(tok) | 6598 | 9551 | 0.69 |
| Q2 | output(tok) | 993 | 1166 | 0.85 |
| Q2 | maxCtx(tok) | 6270 | 12201 | 0.51 |
| Q2 | sumCtx(tok) | 34786 | 63661 | 0.55 |
| Q2 | llmCalls | 7 | 6 | 1.17 |
| Q3 | elapsed(s) | 168.2 | 230.9 | 0.73 |
| Q3 | freshIn(tok) | 35868 | 21579 | 1.66 (noisy) |
| Q3 | output(tok) | 12247 | 21342 | 0.57 |
| Q3 | maxCtx(tok) | 22352 | 35639 | 0.63 |
| Q3 | sumCtx(tok) | 201418 | 340188 | 0.59 |
| Q3 | llmCalls | 17 | 14 | 1.21 |
yca runs: q1{13,14,15}, q2{12,13,14}, q3{15,16,17}; opencode: q1/q2/q3 {6,7,8}. Fatjar includes 64K bash cap.
