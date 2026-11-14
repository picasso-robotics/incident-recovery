# incident-recovery

[![License: PolyForm NC](https://img.shields.io/badge/license-PolyForm_Noncommercial_1.0.0-blue.svg)](LICENSE.md)
![Kotlin](https://img.shields.io/badge/Kotlin-JDK_21-7F52FF?logo=kotlin&logoColor=white)
![Engine: Temporal](https://img.shields.io/badge/engine-Temporal-2088FF)
![Status: proof of concept](https://img.shields.io/badge/status-proof--of--concept-orange)

[한국어 README](README.md)

incident-recovery is a deterministic state machine that follows one fault on a robot cell (one "episode") from its first symptom to its resolution. It sits on top of two sibling projects: picasso, the robot execution middleware, and narrator, which diagnoses. It is a pure, deterministic core (`:core`, no Temporal, Spring or JDBC dependency, only Jackson's JSON tree) inside a Temporal shell (`:runtime`): the workflow drives the core's transition function and decides nothing itself. It is a proof of concept on one machine.

## Where it came from

The code was split out of the koshei repository on 2026-10-05, from koshei commit `6e66dbe`. It had come into koshei through koshei PR #4 (the design) and PR #5 (the implementation). koshchei starts a new history; the commit history before the split stays in koshei. The two were split because they are different kinds of system: koshei keeps its own role as an engine-neutral saga platform for OT/IT integration, while koshchei keeps only the episode loop.

With the split, packages, environment variables, the database, the DB role and the task queue were renamed to koshchei (for example `KOSHEI_PICASSO` became `KOSHCHEI_PICASSO`, and the queue `koshei-episode-tq` became `koshchei-episode-tq`). Outside contracts kept their names: narrator's queue `narrator-tq` and activity `diagnose`, picasso's approval endpoint schema 4 and ResultExport schema 1. Behaviour did not change, with one exception: the worker now refuses to start when `KOSHCHEI_PICASSO` is unset or `off`, because the episode worker is the only thing it runs.

Ports and the database changed with the split: Postgres 15432 → 15433 (database `koshchei` instead of `koshei`), the HTTP API 18090 → 18190, and the UI dev server 5173 → 5174. Episode audit records written before the split stay in koshei's database; koshchei starts with empty tables.

On 2026-11-10, this repository moved to the GitHub organization picasso-robotics and was renamed from `koshchei` to `incident-recovery`. On 2026-11-11, names in the code were changed to match the repository name: package `koshchei.*` became `dev.picasso.recovery.*`, environment variables `KOSHCHEI_*` became `RECOVERY_*` (for example, `KOSHCHEI_PICASSO` became `RECOVERY_PICASSO`), task queue `koshchei-episode-tq` became `incident-recovery-episode-tq`, database and user `koshchei` became `incident_recovery`, DB role `koshchei_rt` became `incident_recovery_rt`, and header `X-Koshchei-Operator` became `X-Recovery-Operator`. External contract names remain unchanged: narrator queue `narrator-tq` and activity `diagnose`, picasso approval endpoint schema 4, and ResultExport schema 1, with behavior and ports also remaining unchanged. The database before this rename (`koshchei`) is not migrated to the newly named database, which starts with empty tables, and the paragraphs above (the renaming story when splitting from koshei on 2026-10-05) keep their old names as a record of that point in time.

## How it works

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/flow.en.dark.svg">
  <img alt="The watcher reads incident lines and remedy search records from the picasso bundle and passes them to the episode workflow as symptoms. The episode workflow exchanges diagnosis requests and responses with narrator through the diagnose activity on narrator-tq, and the operator on the episodes screen sends approvals and confirmations. The workflow sends POST /approvals to the picasso approval endpoint, whose JobResponse (ResultExport schema 1) returns through the watcher to the workflow as a job response." src="docs/diagrams/flow.en.svg">
</picture>

- **Symptoms in.** A watcher process (`:host:watcher`) reads picasso's exported incident lines and remedy-search lines and signals them into episodes. Policy table v1 has one correlation rule: a robot's search line and incident line on the same job order become one episode.
- **Diagnosis.** narrator answers on the Temporal queue `narrator-tq` (diagnosis contract 0.6). It does not describe a remedy; it points at one of the candidates incident-recovery computed. Only a recommendation inside those candidates with at least one verified citation becomes an approval request; anything else (no grounds, uncited, an ESCALATE recommendation, a contract violation) goes to an operator.
- **Approval and dispatch.** An operator approves the remedy; policy table v1 keeps auto-approval off. The core revalidates the precondition, records the intent, and only then dispatches to picasso's approval endpoint (`POST /approvals`, `RECOVERY_PICASSO=picasso`) or to the mock approval client (`RECOVERY_PICASSO=mock`).
- **Resolution.** picasso's job responses come back through the watcher. The episode is DONE only when every approved unit completed, nothing has in-doubt status and the robot's connection state is ONLINE. Otherwise it stays UNKNOWN or goes to an operator.
- **Interfaces.** The HTTP API `/api/episodes…` (`:api`, port 18190), the episodes screen in `ui/` (Vite dev server on port 5174), and a developer CLI (`:host:cli` with `open` and `agent-off <workflowId>|--all`).

## A concrete bundle and episode example

A picasso bundle is a directory exported by picasso containing remedy search records and incident lines. It writes `incidents.jsonl` and `remedy-searches.jsonl` first, then `manifest.json` last, publishing each file by rename.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/bundle.en.dark.svg">
  <img alt="Two paths from a bundle to an episode. The run-1/ bundle written by picasso contains manifest.json, remedy-searches.jsonl, and incidents.jsonl; the manifest holds runId and counts of 9 incident lines and 4 remedy search lines. Search record search-1 has robot hum-02, job order PATROL-1, and outcome FOUND; incident-1 has the same robot and job order. On the upper path, :host:cli open --search search-1 sends only search-1 and opens ep:recovery-demo-1. On the lower path, watcher :host:watcher correlates search-1 and incident-1 by matching robotId + jobOrderId and opens one episode containing both. Outside the bundle, job-responses.jsonl arrives later and reaches the watcher along a dashed arrow. Solid lines represent symptom delivery, the dashed line represents later job responses, and the left zone encloses the bundle's three files. The manifest travels verbatim into the episode and diagnosis request; uninterpreted fields are also preserved verbatim and displayed as facts." src="docs/diagrams/bundle.en.svg">
</picture>

The committed sample is at `runtime/src/test/resources/picasso/schema-6/run-1/`. incident-recovery reads LedgerExport schema versions `"5"`, `"6"`, `"7"` and `"8"` and only the first number of lines specified by `counts` in each file, and refuses any other schema version (the watcher records the bundle as broken and the CLI stops with an error). In schema version `"8"` the watcher treats incident lines with the same `incidentKey` as one incident — a hold that a picasso restart reopened joins the episode that is open for it, and opens no new episode once that episode has ended; a reopened line with no key is opened as its own episode and recorded in the watch log as link unknown (`LINK_UNKNOWN`). The diagnosis snapshot and narrator also accept both schema versions `"5"` and `"6"`, so an episode opened from a schema version `"6"` bundle is diagnosed like a schema version `"5"` episode. The JSON below is trimmed to fields read from the sample.

The first line of `remedy-searches.jsonl`:

```json
{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1","outcome":"FOUND","steps":[{"skillType":"pick_place"}]}
```

`search-1` records finding a remedy with one `pick_place` step at `00:00:01`. The other searches are `search-2` with `NONE`, `search-3` with `WITHHELD`, and `search-4` with `SOURCE_MISSING`; `WITHHELD` goes to an operator before diagnosis.

The first line of `incidents.jsonl`:

```json
{"incidentId":"incident-1","jobOrderId":"PATROL-1","executionId":"exec-2","robotId":"hum-02","unitId":"remedy-1-pick_place","at":"2026-09-06T00:00:02Z","unresolved":false,"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"verification":"NOT_REQUESTED","resolution":null,"digest":"a4a40278620c1cdc3904f3d4138a39a57fa62b3b63bc99f8b08291f72a24ee5a"}
```

`incident-1` occurred on unit `remedy-1-pick_place` at `00:00:02`; its original `failureClass` is `PAYLOAD_LOST`. Its episode event's `eventId` is `incident:<runId>:<digest>`. Separate job responses contain one JobResponse per line under ResultExport schema 1, and the watcher finds the receiving episode by `jobOrderId`. `run-1` has no job responses.

An episode follows diagnosis, approval, and outcome confirmation for its input symptoms. The quickstart below uses `RECOVERY_PICASSO=mock`, the default `RECOVERY_NARRATOR=mock`, and policy table v1.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/episode.en.dark.svg">
  <img alt="The quickstart episode's phases and decision paths. The main path waits 5 seconds for correlation in CORRELATING, then enters DIAGNOSING; a diagnosis call to narrator-tq produces the mock narrator's recommendation APPROVE_REMEDY:hum-02:PATROL-1:pick_place. AWAITING_APPROVAL sends an APPROVAL_NEEDED notice and waits 5 minutes. Operator approval leads to REVALIDATING, where the precondition is checked again with mock answer TRUE, then DISPATCH_PENDING writes the dispatch intent record before DISPATCHED receives APPROVED · mock-exec-1 from the mock approval client. AWAITING_EVIDENCE waits 10 minutes for completion evidence; the quickstart has no watcher. Operator confirmation of DONE leads to terminal phase RESOLVED, while NOT DONE returns to DIAGNOSING. Rejection of approval also returns to DIAGNOSING. Expiry of the 5-minute approval wait or 10-minute evidence wait leads to ESCALATED for operator handoff; operator closure or 24 hours then leads to terminal phase CLOSED. Expiry of the whole-episode deadline of 1 hour also leads to ESCALATED, and operator takeover from any phase other than ESCALATED leads to ESCALATED. Ordinary solid arrows represent automatic progress, red arrows represent operator decisions, and dashed arrows represent time expiry; RESOLVED and CLOSED are marked as terminal phases." src="docs/diagrams/episode.en.svg">
</picture>

The `open` option `--key recovery-demo-1` determines the workflow id, and the CLI sends an episode event with `eventId` `search:<runId>:search-1`. `<runId>` means the sample manifest's `runId`. The candidates for `search-1` are exactly `["APPROVE_REMEDY:hum-02:PATROL-1:pick_place","ESCALATE"]`; the mock narrator recommends the first candidate other than `ESCALATE`. This is its trimmed answer:

```json
{"contractVersion":"0.6","episodeId":"ep:recovery-demo-1/<runId>","attempt":1,"outcome":"RECOMMENDED","candidateId":"APPROVE_REMEDY:hum-02:PATROL-1:pick_place","citations":[{"title":"mock-sop","section":"1","verified":true}]}
```

Diagnosis validation accepts it as a proposal because the offered candidate id and candidates version match and it has a verified citation. Auto-approval is off, so operator approval is required. The mock client answers `TRUE` for the precondition while the proposal has not been consumed; the mock approval client's response is for unit `remedy-1-pick_place`.

The screen lists the workflow id and current phase; the detail view's operator card shows the original `search-1` line, the proposal, and the rationale and citation `mock-sop · 1`. The figure's approval and rejection actions correspond to the `Approve` / `Reject` buttons. Approval and evidence expiry have reasons `APPROVAL_EXPIRED` and `EVIDENCE_EXPIRED`, respectively; automatic closure after 24 hours has reason `UNATTENDED`.

## What it runs with

- picasso: incident-recovery sends approvals to picasso's approval endpoint (`POST /approvals`, schemas 4 and 5) and reads picasso's job responses (JobResponse lines, ResultExport schema 1). The endpoint is loopback-only.
- narrator: the `diagnose` activity on the Temporal queue `narrator-tq`, diagnosis contract 0.6. With `RECOVERY_NARRATOR=mock` (the default) the worker serves that queue itself with mock narrator activities; with `remote` narrator's own worker does.
- Temporal: incident-recovery uses the Temporal server on `localhost:7233`, the same server narrator's worker and picasso use. Its own Docker Compose file starts only Postgres (database `incident_recovery` on host port 15433); a private Temporal starts only with `docker compose --profile temporal up -d`, for a machine where nothing holds 7233 yet.
- Sharing Temporal: koshei, the repository incident-recovery was split from, no longer contains the episode loop: it was removed from koshei's main branch in commit 335946a (koshei PR #11, 2026-10-05). The sharing caution still applies to koshei builds from before 335946a and to the episodes they started that are still open on a shared Temporal server (under policy table v1 an escalated episode stays open for 24 hours). On the same Temporal server incident-recovery and koshei use the same workflow type `EpisodeWorkflow`, the same workflow ids `ep:<key>`, and narrator's queue `narrator-tq`. So `agent-off --all` also reaches koshei's open episodes, and an `open` whose id is still running in koshei signals koshei's run. On a shared server, pass `--key` with a incident-recovery-specific prefix, set `RECOVERY_NARRATOR=remote` whenever narrator's worker runs, or use the private Temporal (`docker compose --profile temporal up -d`) on a machine where nothing holds 7233. Details are in [`docs/usage.md`](docs/usage.md#sharing-temporal) §1.

## Modules

| Module | What it holds |
|---|---|
| `:core` | The pure core: episode state machine, transition function, policy table, candidates, diagnosis request and diagnosis validation. Depends only on Jackson's JSON tree. |
| `:runtime` | The Temporal shell: `EpisodeWorkflow`, its activities, the episode tables (`EpisodeStore`), the watcher's logic, the mock narrator activities, the mock approval client and the live (`HttpApprovalClient`) approval client, `Db`. |
| `:host` | The processes: the episode worker (`:host:run`), the watcher (`:host:watcher`), the developer CLI (`:host:cli`). |
| `:api` | The Spring Boot HTTP API, `/api/episodes…` on port 18190 (`:api:run`). |
| `ui/` | The episodes screen, Vite + React, dev server on port 5174. Not a Gradle module. |

Dependencies run one way: `core` ← `runtime` ← `host`, and `runtime` ← `api`. No module depends on any koshei module; the two small pieces that came from koshei's shared modules (the raw-JSON data converter and the `Db` connection settings) were copied into `:runtime`. `ui/` is a React app outside the Gradle build.

## Quickstart with the mock approval client

The commands start Postgres, the episode worker with the mock approval client, the HTTP API and the episodes screen, and open one episode from the committed sample bundle.

```bash
docker compose up -d --wait           # incident-recovery's Postgres on host port 15433
# Temporal: the shared server on localhost:7233; if nothing runs there: docker compose --profile temporal up -d

# terminal 1: the episode worker on incident-recovery-episode-tq, with the Mock narrator on narrator-tq
export RECOVERY_PICASSO=mock
./gradlew :host:run

# terminal 2: the control plane on 127.0.0.1:18190 (/api/episodes…)
./gradlew :api:run

# terminal 3: the Episodes screen on http://localhost:5174
cd ui && npm install && npm run dev
```

Paths inside `--args` must be single-quoted (`'…'`); Gradle splits `--args` on spaces but honours quotes, and a checkout path may contain a space. In Git Bash, `$PWD` is a `/c/...` path, which the JVM reads as a different path; use `$(pwd -W)`, which gives `C:/...`. The command passes `--key recovery-demo-1` so the workflow id `ep:recovery-demo-1` cannot meet an episode opened by koshei from the same sample bundle on a shared Temporal server.

```bash
# terminal 4: open one episode from the committed sample export
# Linux / macOS
./gradlew :host:cli --args="open --export '$PWD/runtime/src/test/resources/picasso/schema-6/run-1' --search search-1 --key recovery-demo-1"

# Git Bash on Windows
./gradlew :host:cli --args="open --export '$(pwd -W)/runtime/src/test/resources/picasso/schema-6/run-1' --search search-1 --key recovery-demo-1"
```

```powershell
# terminal 4, PowerShell
./gradlew :host:cli --args="open --export '$PWD\runtime\src\test\resources\picasso\schema-6\run-1' --search search-1 --key recovery-demo-1"
```

Set the same `RECOVERY_PICASSO` in every shell that starts the worker or the watcher: each process reads only its own environment. With `RECOVERY_PICASSO=mock`, remedies reach no robot; the worker prints a warning at start. The `:host:cli` task runs with `host/` as its working directory, so the bundle path must be absolute. After episode opening, go to http://localhost:5174; under policy table v1 the episode waits for an operator's approval before dispatching to the mock approval client, and because no watcher runs in this quickstart, no job response arrives: an operator confirms the outcome or the evidence deadline (10 minutes in policy table v1) hands the episode to an operator. The full guide, including the watcher and the live approval endpoint, is `docs/usage.md`.

## Tests

```bash
./gradlew test          # 705 tests: core 323, runtime 311 (1 skipped), host 29, api 42

cd ui
npm install             # first time only
npm test                # Vitest: 41 tests
npm run test:e2e        # Playwright: a warmup step, then 20 tests (stop npm run dev first; needs Google Chrome)
```

`./gradlew test` runs 705 tests with 0 failures and 1 skipped, where the skipped one is the generator of the committed replay histories that runs only when asked. The runtime tests replay nine committed workflow histories (`runtime/src/test/resources/replay/2026-10-04/`), recorded before the split, against the renamed code, while database tests use Testcontainers (`postgres:16`) and require Docker to be running. The UI end-to-end tests stub `/api/episodes` in the page; there is no end-to-end test against a running backend.

## Scope and limitations

The following points define the limits and live verification history of incident-recovery as a single-machine proof of concept.

- Two live runs occurred in koshei before the split: on 2026-10-03, one live diagnosis through narrator and khala on `narrator-tq` verified 13 of 13 citations and narrator recommended ESCALATE, so the episode went to an operator; on 2026-10-04, one live run against picasso's reference host: an operator approved a remedy, picasso answered APPROVED, and the linked JobResponse moved the episode to UNKNOWN_OUTCOME because it carried `operatorRequired`.
- There is no deployment host yet. It will live in a separate deployment repository; until then the test peer is picasso's reference host.
- With the live approval endpoint, revalidation is always UNKNOWN (there is no live ledger read), so an operator confirms each precondition before a dispatch.
- What DONE should mean (the remedy done, or the whole job order done) is an open decision (design §19 F). Today a job-order-level `operatorRequired` keeps an episode in UNKNOWN_OUTCOME even when every approved unit completed.
- The approval endpoint is loopback-only, so incident-recovery has to run on the same machine as picasso's host.
- Out of scope for now: policy table management (git + DB pointer + CLI), `SAGA_ACTION` remedies and child sagas, the full audit record fields and quality-history links, a screen for the watcher log, and a read-only database role for the HTTP API.
- The HTTP API has no authentication. It listens on `127.0.0.1` unless `RECOVERY_BIND_ADDRESS` says otherwise.

## Docs

- [`docs/usage.md`](docs/usage.md) — how to run, configure and operate it: environment variables, worker modes, the policy table file, the watcher, the HTTP API, the database role.
- [`docs/design/2026-09-27-episode-outer-loop-design.md`](docs/design/2026-09-27-episode-outer-loop-design.md) — the design, in Korean: state machine, transition table, watcher, audit records, implementation log (§16) and open decisions (§19). It was written while the code lived in koshei, so its body says koshei.
- [`docs/plans/`](docs/plans/) — the implementation plans, in Korean, from plan B1 to plan D-lite, and the plan for this split. The plans other than the split plan were written while the code lived in koshei, so they use koshei's module, package and path names.

## License

incident-recovery is licensed under the PolyForm Noncommercial License 1.0.0; see [`LICENSE.md`](LICENSE.md). Noncommercial use is permitted; commercial use is reserved. It is a source-available license, not an OSI open-source license.

Copyright © 2026 LivingLikeKrillin (livinglikekrillin@gmail.com).
