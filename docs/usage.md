# Using incident-recovery

This guide covers running, configuring, testing, and driving the episode loop in incident-recovery from symptom arrival to final resolution. It describes how to operate the worker, the watcher, the HTTP API, and the developer CLI.

## 1. Run it

To run the system, you must have Docker installed. Besides Docker, you need a JVM 17 or newer to run Gradle itself (the build compiles with JDK 21, which the Gradle toolchain provisions through the foojay resolver) and Node.js 18 or newer for the episodes screen. Each long-running command requires its own terminal window. Note that incident-recovery connects to the Temporal server on `localhost:7233`, which is the same Temporal instance that narrator's worker and picasso share. Because of that shared instance, the Docker Compose file in incident-recovery does not start Temporal by default, preventing port collisions on port 7233.

Every command in this document is written for bash syntax. In PowerShell, commands such as `./gradlew ...` and `docker compose ...` work as written, including arguments passed with `--args="..."`. To set an environment variable in PowerShell, use `$env:NAME = "value"` instead of `export NAME=value`. Windows PowerShell 5.1 does not support the `&&` chaining operator; place each command on its own separate line or separate consecutive statements with a semicolon `;`. In every shell, quote a path inside `--args` with single quotes (`--args="open --export '<path>' ..."`); without them Gradle splits a path that contains a space. In Git Bash use `$(pwd -W)` rather than `$PWD` to build an absolute path, because the JVM misreads `/c/...`.

```bash
# 1. incident-recovery's Postgres (host port 15433). Temporal is the shared server on localhost:7233;
#    on a machine where nothing holds 7233 yet, start a private one instead:
#    docker compose --profile temporal up -d
docker compose up -d --wait

# 2. The episode worker (dev.picasso.recovery.host.WorkerKt). It refuses to start without RECOVERY_PICASSO.
export RECOVERY_PICASSO=mock
./gradlew :host:run

# 3. The control plane (Spring Boot on 127.0.0.1:18190).
./gradlew :api:run

# 4. The Episodes screen (Vite on port 5174, proxies /api -> 127.0.0.1:18190).
cd ui
npm install      # first time only
npm run dev
```

Open `http://localhost:5174` in your browser to view the episodes screen. The HTTP API starts even if the database and Temporal are not yet reachable, but any endpoint request that requires either component returns HTTP status 503. Until the worker or the watcher has started once with the database owner, the underlying episode tables do not exist in the database, and the episode list endpoint responds with 503 and the message "episode tables not found".

### Ports

| What | Host port | Started by |
|---|---|---|
| Postgres (database `incident_recovery`, user `incident_recovery`) | 15433 | `docker compose up -d` |
| Temporal | 7233 | shared with narrator and picasso; `docker compose --profile temporal up -d` only when nothing holds 7233 |
| HTTP API (`:api`) | 18190 | `./gradlew :api:run` |
| episodes screen (Vite dev server) | 5174 | `npm run dev` in `ui/` |

### Sharing Temporal

incident-recovery uses the Temporal server on `localhost:7233` in the default namespace, where narrator's worker uses the same server. koshei, the repository incident-recovery was split from, no longer contains the episode loop: it was removed from koshei's main branch in commit 335946a (koshei PR #11, 2026-10-05); in the bullets below, "koshei" means a koshei build from before 335946a.

- Both incident-recovery and koshei name the episode workflow type `EpisodeWorkflow` and its ids `ep:<key>`. `agent-off --all` lists every open workflow of type `EpisodeWorkflow` on the server, so it also sends `agentOff` to koshei's open episodes.
- `open` (and the watcher) use signalWithStart on `ep:<key>`: when a run with that id is still open, the symptom goes to that run, even if koshei started it. The default key is `<runId>:<id>` from the bundle, so opening the same export line in both repositories gives the same id. The watcher's ids also come from the bundle and take no prefix, so do not let a koshei watcher and a incident-recovery watcher read the same bundles on one server.
- With `RECOVERY_NARRATOR=mock` (the default), the worker registers mock narrator activities on `narrator-tq`. If narrator's live worker also polls that queue, the two compete and Temporal gives each diagnose activity to whichever poller takes it. Set `RECOVERY_NARRATOR=remote` whenever narrator's worker runs.
- Workflows started by incident-recovery run on its own queue `incident-recovery-episode-tq`, so koshei's worker does not run them; the overlap is in the ids, the workflow type and `narrator-tq`.

On a shared server, pass `--key` with a incident-recovery-specific prefix (the quickstart uses `--key recovery-demo-1`). For a clean separation, use the private Temporal (`docker compose --profile temporal up -d`) on a machine where nothing else holds 7233.

## 2. Configure

### Environment variables

The table lists all 16 environment variables that incident-recovery reads across its processes.

| Key | Purpose |
|---|---|
| `RECOVERY_PICASSO` | The approval endpoint: `off` (default), `mock` (the mock approval client) or `picasso` (the live approval endpoint). Any other value stops the process at start. The worker and the watcher both refuse `off` (and an unset value); only the worker's value picks the approval endpoint. Read by the worker and the watcher. Details in [§3](#3-run-the-episode-loop). |
| `RECOVERY_PICASSO_URL` | The approval endpoint's origin, required when `RECOVERY_PICASSO=picasso`. Use `http://127.0.0.1:<port>` or `http://localhost:<port>` only, with no path (the client adds `/approvals`), query, fragment or user info. `localhost` must resolve only to loopback addresses with an IPv4 loopback address first, otherwise the start is refused; prefer `http://127.0.0.1:<port>`. Read only by the worker. |
| `RECOVERY_PICASSO_AGENT_ID` | The approver id that a POLICY approval sends as AGENT. Required (not blank) when `RECOVERY_PICASSO=picasso`; there is no default. incident-recovery's own id is `recovery-1` (decided 2026-11-14, picasso handoff `handoff/recovery/approver-id.md`), not narrator's `narrator-1`. Switch to it only after the approval host has been restarted with a declaration file that names `recovery-1` and every operator id; in the other order every automatic approval is refused `NOT_DECLARED`. Read only by the worker. |
| `RECOVERY_PICASSO_TIMEOUT_MS` | The limit for one request to the approval endpoint, in ms. Must be a positive number; default 8000. Keep it at most `(dispatchMs − 3000) / 3`, where `dispatchMs` is the policy table's `dispatchMs` (30000 in policy table v1, so at most 9000). Read only by the worker with `RECOVERY_PICASSO=picasso`. |
| `RECOVERY_NARRATOR` | Who diagnoses: `mock` (default) or `remote`. Any other value stops the worker at start. With `mock` the worker registers the mock narrator activities on `narrator-tq`; with `remote`, narrator's own worker serves that queue. Run only one poller on the queue. Read by the worker. |
| `RECOVERY_EPISODE_POLICY` | The episode policy table file. Code default `policy/active.yaml`, relative to the working directory; when unset, `:host:run` and `:host:watcher` pass the absolute path of the repository's `policy/active.yaml`. A missing file makes every episode escalate `POLICY_MISSING`; the worker warns at start. Read by the worker and the watcher. |
| `RECOVERY_EPISODE_DB_USER` | DB user for the episode tables. Unset or blank: the `RECOVERY_DB_USER` / `RECOVERY_DB_PASS` login (or its defaults). The URL is always `RECOVERY_DB_URL`'s. In production: the runtime role `incident_recovery_rt` ([§3](#the-episode-tables-and-the-runtime-role)). Read by the worker and the watcher. |
| `RECOVERY_EPISODE_DB_PASS` | Password for `RECOVERY_EPISODE_DB_USER`. Set both or neither: one without the other is refused at start. Read by the worker and the watcher. |
| `RECOVERY_WATCH_EXPORTS` | picasso bundle directories, separated by the platform path separator (`;` on Windows, `:` elsewhere). Required, with no empty entries. Read only by the watcher. |
| `RECOVERY_WATCH_CARRY` | The directory holding picasso's `job-responses.jsonl` (ResultExport schema 1). Optional (not blank when set); when unset, no job responses are ingested. Read only by the watcher. |
| `RECOVERY_WATCH_INTERVAL_MS` | Poll interval in ms, 100 to 60000; default 2000. Read only by the watcher. |
| `RECOVERY_DB_URL` | Postgres JDBC URL. Default `jdbc:postgresql://localhost:15433/incident_recovery` (the bundled Compose Postgres). Read by the worker, the watcher and the HTTP API. |
| `RECOVERY_DB_USER` | DB user. Default `incident_recovery`. Read by the worker, the watcher and the HTTP API. |
| `RECOVERY_DB_PASS` | DB password. Default `incident_recovery`. Read by the worker, the watcher and the HTTP API. |
| `RECOVERY_BIND_ADDRESS` | The address the HTTP API listens on. Default `127.0.0.1` (loopback only). Read by the HTTP API. |
| `RECOVERY_WORKER_NAME` | The name the worker prints in its log lines. Default `worker-1`. Read by the worker. |

> **Security note.** The HTTP API has no authentication because it was built as a proof of concept. For that reason, it binds to loopback (`127.0.0.1`) by default, and setting `RECOVERY_BIND_ADDRESS` opens the service to other network interfaces. The approval endpoint interface is restricted to loopback connections as well.

The Gradle daemon retains the environment variables of the shell session in which it was first launched. Consequently, any variable exported in a terminal after the daemon is already running does not propagate to a forked task JVM on its own. To resolve this, the Gradle tasks `:host:run`, `:host:watcher`, and `:host:cli` explicitly copy every variable prefixed with `RECOVERY_*` from the invoking shell into the forked JVM. `:host:run` and `:host:watcher` also set `RECOVERY_EPISODE_POLICY` to the absolute path of `policy/active.yaml` whenever that variable is unset, because their working directory is set to `host/`, where a relative path would fail to find the policy table file. The task `:api:run` similarly copies every `RECOVERY_*` environment variable from the shell into its own forked JVM.

## 3. Run the episode loop

The episode loop processes an operational fault through its entire lifecycle from initial symptom to resolution. Symptoms arrive from picasso bundles, narrator produces an automated diagnosis, a human operator issues an approval decision, incident-recovery dispatches the approved remedy to picasso's approval endpoint, and incoming job responses from picasso close out the episode. The full architecture and design are written in Korean in [`docs/design/2026-09-27-episode-outer-loop-design.md`](design/2026-09-27-episode-outer-loop-design.md). Every environment variable referenced in this workflow appears in the configuration table in §2.

1. [Worker modes](#worker-modes): pick the modes.
2. [The policy table](#the-policy-table): check the policy table.
3. [Start it](#start-it): start the processes.
4. [Feed it](#feed-it): feed it symptoms, with the watcher or the developer CLI.
5. [Decide](#decide-the-http-api-and-the-episodes-screen): decide in the episodes screen or through the HTTP API.
6. [The episode tables and the runtime role](#the-episode-tables-and-the-runtime-role): for production, run the worker and the watcher as the runtime role.

### Worker modes

The variable `RECOVERY_PICASSO` selects the approval client implementation.

| Value | What runs |
|---|---|
| `off` (default) | Nothing: the worker refuses to start, and so does the watcher |
| `mock` | The episode worker on `incident-recovery-episode-tq` with the test mock approval client (`MockApprovalClient`, in the worker's memory). Remedies reach no robot; the worker prints a warning at start |
| `picasso` | The episode worker with the live approval client, `HttpApprovalClient`: `POST /approvals` on `RECOVERY_PICASSO_URL` (loopback only), approvals sent as PERSON, or as AGENT under `RECOVERY_PICASSO_AGENT_ID` |

The variable `RECOVERY_NARRATOR` designates which component handles the diagnose activity. The default setting `mock` runs an in-process stand-in that selects the first offered remedy candidate other than ESCALATE and supplies a grounded rationale with citations. Setting this variable to `remote` leaves the Temporal task queue `narrator-tq` to narrator's independent worker process under diagnosis contract 0.6 using the activity `diagnose`.

When running with `RECOVERY_PICASSO=picasso`, picasso looks up a declaration for every approver identifier, regardless of whether that identifier designates a PERSON or an AGENT. If an approver identifier has not been declared in picasso beforehand, picasso rejects it with `NOT_DECLARED`. Since picasso's approval schema 5 (picasso main `fde49d1`), a recovery proposal for a unit that a robot refused after the order was accepted is refused with `HUMAN_APPROVAL_REQUIRED` for every approval sent to `POST /approvals`, a PERSON's included, and retrying gives the same answer. The episode goes to a person (ESCALATED, `DISPATCH_REFUSED`, detail `HUMAN_APPROVAL_REQUIRED`). incident-recovery has no path to picasso's recovery entrance, so the operator closes that hold on the execution host, with an operator decision or a cancel there.

Each process reads only its own operating environment. The worker process and the watcher process must both have `RECOVERY_PICASSO` set to a value other than `off`; only the worker's value picks the approval client. The HTTP API process does not inspect this setting. If any process encounters an unrecognized configuration value at startup, it halts execution immediately.

### The policy table

The file `policy/active.yaml` defines policy table v1 under policy version identifier `2026-10-03.2`. The worker and the watcher evaluate this file by default unless the environment variable `RECOVERY_EPISODE_POLICY` points to a different path. The runtime engine re-reads this file from disk before every decision evaluation, so modifications take effect immediately without requiring a process restart. If the policy table is unreadable or rejected during validation, the episode keeps the last valid policy table and suspends auto-approvals.

- `agentLayerEnabled: true`: enables automated agent processing; switching this setting to `false` directs every episode to hand over to a human operator, and running `agent-off --all` forces open episodes to evaluate and apply the change immediately.
- `autoApprove.APPROVE_REMEDY.allowed: false`: enforces that every remedy requires manual human approval.
- `deadlines`: configures explicit timeout boundaries for every waiting state, including `approvalMs` set to 5 minutes, `evidenceMs` set to 10 minutes, and `episodeMs` set to 1 hour.
- `diagnosis`: configures diagnose activity execution to make two attempts of 540 s within a 1,200 s overall timeout, sending a heartbeat signal every 30 s under narrator contract 0.6.
- `correlation`: contains a single rule declaring that a robot's remedy-search line and incident line on the same job order belong to one shared episode.
- `actionCatalog: []`: specifies that incident-recovery's own remedies (`SAGA_ACTION`) remain out of scope.

Directly editing this file on disk is the only supported policy management mechanism, as integrating Git versioning with database pointers and dedicated `policy validate` and `activate` tooling is out of scope (design §19).

### Start it

Ensure Postgres is running as shown in §1, export `RECOVERY_PICASSO=mock` in the shell for the worker, and start the worker, the HTTP API, and the episodes screen development server, giving each process its own terminal.

```bash
docker compose up -d --wait                         # as in §1
export RECOVERY_PICASSO=mock                        # in the worker's shell (and the watcher's)
./gradlew :host:run                                 # episode worker (+ Mock narrator)
./gradlew :api:run                                  # control plane: /api/episodes…
cd ui && npm run dev                                # the Episodes screen
```

The same steps in PowerShell:

```powershell
docker compose up -d --wait                         # as in §1
$env:RECOVERY_PICASSO = "mock"                      # in the worker's shell (and the watcher's)
./gradlew :host:run                                 # episode worker (+ Mock narrator)
./gradlew :api:run                                  # control plane: /api/episodes…
cd ui; npm run dev                                  # the Episodes screen
```

When the worker starts, it logs the database user account it uses to access the episode tables, followed by a log line identifying the episode queue `incident-recovery-episode-tq`, the active narrator mode, and the loaded policy table path. When running with `RECOVERY_PICASSO=mock`, it prints an explicit warning stating that dispatched remedies do not reach actual robots. If the target policy table cannot be located on disk, the worker issues a warning that every incoming episode will immediately escalate under the reason `POLICY_MISSING`. If the worker's login may create in the schema (the database owner can), it automatically creates any missing episode tables. Once the worker has initialized, open the episodes screen in your browser.

### Feed it

Episodes are not opened until external symptoms are delivered to the system. You can deliver symptoms by running the watcher process continuously or by injecting individual symptoms by hand with the developer CLI.

The watcher runs as an independent daemon started with `./gradlew :host:watcher`. It polls bundle directories generated by picasso and, whenever `RECOVERY_WATCH_CARRY` is set, polls picasso's `job-responses.jsonl` file. The watcher waits to process any bundle until that bundle's `manifest.json` file has appeared. It tracks its polling progress in the database table `episode_watch_cursor`, and writes records that could not be ingested into `episode_watch_log`. The web UI provides no screen for viewing `episode_watch_log`; you must query the database table directly. The watcher refuses to run if `RECOVERY_PICASSO` is `off` or unset. Because the watcher's execution directory is `host/`, always configure directory paths as absolute paths. If a polling pass encounters a failure, the watcher logs the error and retries on the next polling interval.

```bash
export RECOVERY_PICASSO=mock
export RECOVERY_WATCH_EXPORTS=/abs/path/to/picasso/export
export RECOVERY_WATCH_CARRY=/abs/path/to/picasso/carry     # optional
./gradlew :host:watcher
```

The same in PowerShell; on Windows, separate several bundle directories with `;`:

```powershell
$env:RECOVERY_PICASSO = "mock"
$env:RECOVERY_WATCH_EXPORTS = "C:\abs\path\to\picasso\export"
$env:RECOVERY_WATCH_CARRY = "C:\abs\path\to\picasso\carry"     # optional
./gradlew :host:watcher
```

The developer CLI, executed through `./gradlew :host:cli`, opens an individual episode by reading a single picasso export line in the same manner as the watcher. It also provides commands to turn off automated agent handling for a single episode or across all currently open episodes. The CLI communicates exclusively with Temporal and makes no direct database connections. If you provide invalid or malformed arguments, the CLI displays a usage error and terminates without sending signals to Temporal.

```bash
# open: signalWithStart ep:<key> with one line; the default key is <runId>:<id>
./gradlew :host:cli --args="open --export '/abs/path/to/export' --search search-1 --key recovery-demo-1"
./gradlew :host:cli --args="open --export '/abs/path/to/export' --incident incident-1 --key recovery-demo-2"

# agent-off: one episode, or every open one; the episode goes to a person (AGENT_LAYER_OFF)
./gradlew :host:cli --args="agent-off ep:recovery-demo-2"
./gradlew :host:cli --args="agent-off --all"
```

The commands run unchanged in PowerShell; give a Windows absolute path there, still inside single quotes. The examples pass `--key` with a incident-recovery-specific prefix; see ["Sharing Temporal"](#sharing-temporal) in §1. The quickstart uses the committed schema version `"6"` sample bundle at `runtime/src/test/resources/picasso/schema-6/run-1/`, containing four remedy-search entries and nine incident records. The older schema version `"5"` sample at `runtime/src/test/resources/picasso/run-1/` and the schema version `"7"` sample at `runtime/src/test/resources/picasso/schema-7/run-1/` (ten incident records; picasso main `fde49d1`) remain in the repository and are used by the tests. The schema version `"8"` samples at `runtime/src/test/resources/picasso/schema-8/` (`run-1`, `run-2`, and the restart scene `run-5a` · `run-5b`; picasso `fd31c6f`) are used by the tests too. The schema version `"9"` samples at `runtime/src/test/resources/picasso/schema-9/` (`run-1`, `run-2`, `run-3`, `run-5a`, and `run-5b`; picasso `f1dfd94`) are used by the tests too. A database set up before schema version `"8"` support must have `runtime/src/main/resources/episode-schema.sql` applied by the owner account and `scripts/db-roles.sql` run again, because the watcher now keeps which episode an `incidentKey` went to in the table `episode_incident_key`. When referencing these sample files, supply an absolute filesystem path, because `:host:cli` runs with `host/` as its working directory.

### Decide: the HTTP API and the episodes screen

The `:api` process hosts the HTTP API on port 18190, binding to loopback by default. It reads persisted state directly from the episode database tables and transmits operator decisions to running Temporal workflows using Workflow Updates. The core decides; a refusal comes back as a value.

| Route | What |
|---|---|
| `GET /api/episodes?limit=` | episode ids (`limit` 1..500, default 50) |
| `GET /api/episodes/notices?after=&limit=` | the operator notice feed, in id order (`limit` 1..500, default 100) |
| `GET /api/episodes/{workflowId}/{originalRunId}` | audit records, operator notices, and while the run is live its live view and operator card |
| `POST /api/episodes/{workflowId}/{originalRunId}/decide` | approve or reject an approval request |
| `POST /api/episodes/{workflowId}/{originalRunId}/confirm` | confirm a precondition, an outcome or an unobserved condition |
| `POST /api/episodes/{workflowId}/{originalRunId}/takeover` | an operator takes the episode over |
| `POST /api/episodes/{workflowId}/{originalRunId}/close` | close an escalated episode |

All `POST` endpoints require the operator's identity in an `X-Recovery-Operator` HTTP request header, with a maximum length of 128 characters. Request payloads must be valid JSON; the endpoints reject unexpected or duplicate JSON fields, and payloads exceeding 64 KiB receive an HTTP 413 response. The HTTP API implements no authentication mechanism (see the security note in §2). The web interface in `ui/` presents the episode list view, the detail panel (showing the operator card, the four decision controls, attempt history, candidates, and audit records), and the live operator notice feed. The interface stores the active operator name locally in the browser under `recovery.operator`.

A refusal by the core is still a 200 with the refusal in `reply`; only the statuses in the table mean the request did not reach the episode or its answer is unknown.

| Status | When | Body |
|---|---|---|
| 200 | The request reached the episode and it answered, a refusal included | `{"reply": "ACCEPTED"}`, or a refusal such as `{"reply": "REFUSED_STALE"}` |
| 400 | The operator header is missing or over 128 characters; the body is not one strict JSON object (an unknown or repeated field, trailing content, a number or a string where `true`/`false` belongs); a required field is missing; the Update's validator refuses the request's form; `limit` is outside 1..500 | `{"error": "…"}` |
| 404 | No such episode workflow; a `GET` of an episode id with nothing live and nothing recorded | `{"error": "…"}` |
| 409 | The episode id is not the workflow's current run; nothing is sent | `{"error": "EPISODE_MOVED", "currentInstanceId": "…"}` |
| 409 | The run has ended | `{"error": "EPISODE_ENDED"}` |
| 413 | The body is over 64 KiB | `{"error": "…"}` |
| 503 | Temporal is unreachable, no worker answers the check before a decision, the database is unreachable, or the episode tables do not exist yet | `{"error": "…"}` |
| 504 | The Update got no answer within 30 s; it may still be applied. Read the episode again before deciding again | `{"error": "EPISODE_TIMEOUT", "outcome": "UNKNOWN", "instanceId": "…"}` |

`takeover` takes no body; unknown or repeated fields are refused.

| Route | Body (one JSON object) |
|---|---|
| `decide` | `proposalId` and `sawCandidatesVersion` (required); `approve` (`true` or `false`, required); `reason` (optional, for a rejection: `WRONG_TARGET`, `WRONG_ACTION`, `PRECONDITION_NOT_MET`, `NOT_NOW`, `OTHER`); `note` (optional) |
| `confirm` | `kind`: `PRECONDITION` or `OUTCOME` (with `candidateId` and `proposalId`), or `UNKNOWN` (with `subject`, an object, and `what`: `OBSERVATION_ABSENT`, `LINK_BROKEN`, `LATE_EVENTS`, `PROGRESS_UNOBSERVED`, `PROGRESS_STALLED` or `OUTCOME`); `holds` (`true` or `false`, required); `note` (optional). There is no proposition field: the HTTP API takes the proposition from its own card |
| `close` | `outcome` (required, free text) |
| `takeover` | none |

`proposalId` and `candidatesVersion` come from the `view` in `GET /api/episodes/{workflowId}/{originalRunId}`; the body field is `sawCandidatesVersion`.

```bash
curl -s -X POST "http://127.0.0.1:18190/api/episodes/ep:recovery-demo-1/<run>/decide" \
  -H "X-Recovery-Operator: alice" -H "Content-Type: application/json" \
  -d '{"proposalId": "<view.proposalId>", "sawCandidatesVersion": "<view.candidatesVersion>", "approve": true}'
```

When operating against picasso with `RECOVERY_PICASSO=picasso`, precondition revalidation always returns UNKNOWN. Because of this, every dispatch operation pauses and waits for an operator to inspect and confirm the precondition displayed on the card (`confirm` on the precondition) before the dispatch proceeds.

### The episode tables and the runtime role

When started using the database owner credentials, either the worker or the watcher automatically initializes the five core database tables defined in `runtime/src/main/resources/episode-schema.sql`: `episode_event`, `episode_notice`, `episode_dispatch`, `episode_watch_cursor`, and `episode_watch_log`. The HTTP API accesses these database tables using the database owner supplied in `RECOVERY_DB_*`. Configuring a separate, restricted read-only account for the HTTP API is currently out of scope. In production environments, the worker and the watcher should not connect as the database owner, but should instead run under the restricted runtime role `incident_recovery_rt` defined in `scripts/db-roles.sql`. That setup script restricts permissions by granting only `SELECT` and `INSERT` privileges on the five episode tables and `USAGE` and `SELECT` on their two sequences, and it aborts execution with an error if the role possesses `CREATE` privileges in the schema. When their login may not create in the schema (as `incident_recovery_rt` may not), the worker and the watcher only verify that the tables exist and do not attempt to run schema creation statements. Consequently, the schema must be applied beforehand: either execute `episode-schema.sql` directly using the database owner or start the worker once without `RECOVERY_EPISODE_DB_USER` configured. The database owner runs the role setup script once:

```bash
# psql on the host (against the bundled Compose Postgres, add: -h localhost -p 15433)
psql -v ON_ERROR_STOP=1 -v rt_password='…' -U incident_recovery -d incident_recovery -f scripts/db-roles.sql

# or through Compose, with no psql on the host
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -v rt_password='…' -U incident_recovery -d incident_recovery < scripts/db-roles.sql
```

```powershell
# the Compose route in PowerShell (no < redirection there)
Get-Content scripts/db-roles.sql -Raw | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -v "rt_password=…" -U incident_recovery -d incident_recovery
```

The script itself sets the role's password from the psql variable `rt_password`, so pass a throwaway value there. Then replace it: open an interactive psql as the database owner (`docker compose exec postgres psql -U incident_recovery -d incident_recovery`, or psql on the host) and run `\password incident_recovery_rt`, which prompts for the password and keeps it out of shell history and process lists. Finally, configure `RECOVERY_EPISODE_DB_USER` and `RECOVERY_EPISODE_DB_PASS` in the shell environments of both the worker and the watcher.

## 4. Test it

```bash
./gradlew test          # 705 tests: core 323, runtime 311 (1 skipped), host 29, api 42

cd ui
npm install             # first time only
npm test                # Vitest: 41 tests
npm run test:e2e        # Playwright: a warmup step, then 20 tests (stop npm run dev first)
```

Executing `./gradlew test` runs the suite of 705 tests, completing with 0 failures and 1 test skipped. The single skipped test is the generator of the committed workflow replay histories; it executes only when explicitly requested by passing `-Drecovery.writeReplayHistories=true`. The runtime test suite verifies workflow deterministic replay against nine committed workflow histories located in `runtime/src/test/resources/replay/2026-10-04/`. All database tests use Testcontainers with a `postgres:16` image, which requires a functioning local Docker daemon. The frontend end-to-end test suite executes against the Vite development server on port 5174 and intercepts `/api/episodes` within the browser context, allowing UI tests to run without starting a backend process. There are currently no end-to-end tests that execute against a live backend stack.

Stop `npm run dev` before `npm run test:e2e`: Playwright starts its own Vite dev server on port 5174 and refuses to reuse one that is already running. The tests run in the installed Google Chrome (Playwright's `chrome` channel), so Google Chrome must be installed.

The generator runs only when asked, and only for a deliberate workflow change guarded by `Workflow.getVersion`; it refuses a set directory that already exists. In Windows PowerShell 5.1, quote each `-D` argument (`"-Drecovery.writeReplayHistories=true"`), or PowerShell splits it; bash needs no quotes.

```bash
./gradlew :runtime:test --tests "*CommittedReplayTest*" -Drecovery.writeReplayHistories=true -Drecovery.replaySet=<name> --rerun
```

```powershell
./gradlew :runtime:test --tests "*CommittedReplayTest*" "-Drecovery.writeReplayHistories=true" "-Drecovery.replaySet=<name>" --rerun
```

## 5. Limits & license

incident-recovery is distributed under the terms of the PolyForm Noncommercial License 1.0.0 ([`LICENSE.md`](../LICENSE.md)). The project is source-available and permitted exclusively for noncommercial applications; commercial use is reserved. The project is not licensed under an OSI-approved open-source license.

- The implementation is a proof of concept intended for single-machine deployment.
- There is no deployment host available yet (a dedicated deployment repository is planned; picasso's reference host serves as the testing peer), and the approval endpoint binds only to loopback interfaces, requiring incident-recovery to reside on the same host machine as picasso.
- When running with the live approval endpoint, precondition revalidation always yields UNKNOWN, requiring an operator to verify and confirm every precondition manually.
- The precise definition of the DONE state—specifically whether it indicates that the individual remedy has completed or that the entire underlying job order has resolved—remains an open design decision (design §19 F).
- The HTTP API contains no authentication layer and queries the episode tables using the privileged database owner.

## See also

- [`README.md`](../README.md) — what incident-recovery is, where it came from, the quickstart.
- [`docs/design/2026-09-27-episode-outer-loop-design.md`](design/2026-09-27-episode-outer-loop-design.md) — the design, in Korean; implementation log in §16, open decisions in §19.
- [`docs/plans/`](plans/) — the implementation plans, in Korean.
