# WiggleBot Expansion Plan

Expanding the WiggleBot server + Android app with finance reporting, a running
coach, better tmux/Claude integration, and a path toward a fuller personal
assistant.

## Current state (summary)

- **Server**: Phoenix 1.7, no database, no scheduled jobs, no push — purely
  request-driven. Agent loop (`agent_session.ex`) calls llama.cpp with a small
  tool registry (weather, GO transit, nearby, navigate). Tmux channel polls
  `capture-pane` 1s and relays `send-keys`. No auth (Tailscale perimeter).
- **App**: Compose UI (chat, settings, tmux list/terminal), Health Connect
  reads, all reminders are **on-device** (AlarmManager 6pm → WorkManager →
  Health Connect check → local notification), every failure path is silent.
- **Consequence**: the server cannot notify the phone about anything, and the
  run reminder depends on a chain of Android alarm/Doze/permission/channel
  conditions with no visibility when a link breaks.

---

## Phase 0 — Foundations (push + persistence + cron)

Everything else builds on these three pieces.

### 0.1 Server → phone push
- [x] Add FCM to the Android app (google-services, `FirebaseMessagingService`)
- [x] App registers its FCM token with the server: `POST /api/devices {token}`
- [x] Server `Push` module sends **high-priority data messages** via FCM HTTP v1
      (service-account JSON via env var)
- [x] Data messages either carry a ready-to-show notification payload or a
      `wake: <worker>` directive (used by the run reminder, Phase 2)

*Why FCM and not self-hosted ntfy:* the core complaint is notifications that
never arrive. FCM high-priority data messages are the only delivery path
Android treats as exempt from Doze/app-standby without a battery-hungry
foreground service. ntfy/UnifiedPush stays a documented fallback if avoiding
Google services becomes a goal.

### 0.2 Server persistence
- [x] Add `ecto_sqlite3` (SQLite file on the homelab box — no Postgres needed)
- [x] Initial tables: `devices` (push tokens), `runs`, `events`,
      `coach_suggestions`, `finance_baselines`, `report_log`

### 0.3 Server scheduling
- [x] Add Quantum (cron-style scheduler, no DB dependency) to the supervision
      tree; jobs defined in config so schedules are tweakable per-env

---

## Phase 1 — Actual Budget integration

Actual has **no official REST API**; the supported path is the
`@actual-app/api` Node library. Recommended: run the community
[`actual-http-api`](https://github.com/jhonderson/actual-http-api) bridge
(official Docker image, tracks Actual's calendar versioning) as a **podman
sidecar** next to the existing Actual container, and call it from Phoenix.

- [x] Podman: add `actual-http-api` container (pin to the same 26.x line as
      the Actual server; env: `ACTUAL_SERVER_URL`, `ACTUAL_SERVER_PASSWORD`,
      sync ID, generated `API_KEY`)
- [x] Phoenix `Finance.ActualClient` (Req/Finch): accounts+balances,
      transactions by date range, budget months. Amounts are integer cents.
- [x] **Agent tools** (server-side, registered in `Tools.Registry`):
  - [x] `get_net_worth` — sum of all on/off-budget account balances
  - [x] `get_account_balance` — fuzzy-match account name ("credit card")
  - [x] `get_spending_summary` — category/payee totals for a period
- [x] **Anomaly report job** (Quantum, e.g. weekly Sunday evening + optional
      daily quick-check):
  - [x] Pull recent transactions, aggregate per category and payee
  - [x] Compare against trailing 3–6 month baseline stored in
        `finance_baselines` (median + MAD; flag |z| above threshold, new
        payees over a floor amount, duplicate-charge heuristic)
  - [x] Compose report — feed the raw flags through the LLM for a readable
        2–3 sentence summary, with the numeric table as expandable detail
  - [x] Push to phone via Phase 0 FCM; log to `report_log` so reports are
        also queryable in chat ("what did last week's report say?")
- [ ] If fixed endpoints prove too coarse for anomaly queries, add a ~50-line
      Node script using `@actual-app/api` + ActualQL (grouped aggregates) run
      as a second tiny container — noted as a fallback, not the default

---

## Phase 2 — Fix run reminders

Two stages: make the current on-device path debuggable, then move the trigger
server-side where it can't be eaten by Doze.

### 2.1 Stop the silent failures (small, do first)
- [x] `RunReminderWorker`: record outcome (fired / skipped-because-ran /
      skipped-by-probability / error) with timestamp in DataStore
- [x] Settings → diagnostics: show last outcome per alarm, channel-enabled
      check (`areNotificationsEnabled` + per-channel), exact-alarm permission
      state, battery-exemption state — all with fix-it buttons
- [x] Fallback to `setAndAllowWhileIdle()` when `canScheduleExactAlarms()` is
      false instead of silently not firing
- [x] Revisit `isLikelyRunDay()` gate: probability threshold 0.35 suppresses
      the nudge on irregular schedules — make the gate opt-in or lower it,
      and surface "suppressed by predictor" in diagnostics

### 2.2 Server-driven trigger (after Phase 0)
- [x] Quantum job at 18:00 sends FCM data message `wake: run_reminder`
- [x] App's FCM service enqueues `RunReminderWorker` (expedited) — Health
      Connect check still happens on-device where the data lives
- [x] Keep the local 6pm alarm as belt-and-braces; dedupe by notification ID
- [x] Once Phase 3 syncs runs to the server, the server can skip the wake
      entirely when today's run is already synced

---

## Phase 3 — Running coach

### 3.1 Run data into the server
Two sources, both useful:
- [x] **Garmin API (primary, server-side)**: OAuth setup, pull/webhook
      activities with HR series, elevation, pace, training effect into `runs`.
      No phone dependency; richest data.
- [x] **Health Connect (secondary, client-side)**: periodic WorkManager job
      uploads exercise sessions to `POST /api/runs/sync` — covers
      non-Garmin-recorded activity and acts as the "did I run today" signal.
      Reuse patterns from `health-activity-widget`'s `HealthConnectRepository`.
- [x] Dedupe by start-time window + duration

### 3.2 Events & plans
- [x] `events` table: name, date, distance, goal. CRUD via
      `POST/GET /api/events` plus agent tools (`add_event`, `list_events`) so
      "I signed up for a half marathon on Oct 4" works in chat
- [x] Coach engine (server): weekly Quantum job (e.g. Sunday) builds context —
      last 6–8 weeks of load (distance, time-in-HR-zones, elevation), recency,
      upcoming events with countdown — and asks the LLM for a structured
      7-day plan (strict JSON schema: day, type, target distance/pace/effort,
      rationale). Store in `coach_suggestions`.
  - Open question: local llama.cpp may be weak for coherent periodization;
    consider routing this one call to the Claude API
- [x] Agent tools: `get_week_plan`, `explain_suggestion`, "replan my week"

### 3.3 Coach UI (app)
- [x] New `COACH` route: week calendar, each day showing suggested workout vs
      what actually happened (✓ matched / partial / missed), event countdown
      banner
- [x] Day detail: suggestion rationale + actual run stats (pace, HR, elevation)
- [ ] Optional later: monthly view, post-run summary notification ("nice 10k,
      Thursday's tempo still on")

---

## Phase 4 — Tmux: create sessions + Claude permission UX

### 4.1 Create sessions from the client
- [x] `POST /api/tmux/sessions {name, cwd, run_claude: bool}` →
      `tmux new-session -d -s name -c cwd` (+ optional `send-keys 'claude' Enter`)
- [x] **Path safety**: `Path.expand/1` + verify the real path
      (symlink-resolved) is under the server user's `$HOME`; reject otherwise.
      Validate session name (`[a-zA-Z0-9_-]+`).
- [x] `GET /api/fs/dirs?path=` — list subdirectories, same home-only
      constraint, for the picker
- [x] App: "＋" on TmuxSessionsScreen → dialog with directory browser
      (breadcrumb + folder list), name field (default from dir), "launch
      Claude" toggle; on success navigate straight into the session

### 4.2 Claude prompt UX
- [x] Fix input fidelity first: use `tmux send-keys -l` for literal text and
      send `Enter` as a named key (current code can't cleanly send
      Enter/arrows, which is why answering prompts is painful)
- [x] Server-side prompt detection in `TmuxChannel`'s poll: recognize Claude
      Code's numbered permission menus / y-n prompts in the pane tail and push
      a structured `prompt_detected` event `{question, options: [{label, key}]}`
- [x] App: render detected options as tappable buttons above the input; tap
      sends the option key + Enter in one shot
- [x] Quick-key row on the terminal screen regardless: Enter, Esc, ↑ ↓, Tab,
      Ctrl-C — fixes the general "type a number then enter then send" friction
- [ ] Later option: a dedicated "Claude session" mode that drives
      `claude -p`/Agent SDK headlessly with proper structured permission
      events instead of screen-scraping — bigger lift, tmux scraping stays the
      pragmatic v1

---

## Phase 5 — Personal assistant direction (exploratory)

Don't build a framework up front; let it fall out of Phases 1–4, which already
turn wigglebot into an assistant with real capabilities (finance, coaching,
homelab control). The connective tissue:

- [x] **Unified daily digest**: one morning push assembled server-side
      (weather/run brief + commute + finance flags + today's coach suggestion)
      replacing N separate notifications
- [x] **Memory**: small SQLite-backed notes table + `remember`/`recall` agent
      tools, injected into the system prompt
- [ ] **Proactive agent runs**: scheduled agent invocations ("anything I
      should know this morning?") that decide whether a push is warranted —
      reuses the same agent loop, sessions just initiated by cron instead of
      the user
- [ ] **Model routing**: keep llama.cpp for quick tool dispatch; route
      planning/summarization-heavy calls (coach plans, digests) to a stronger
      model behind a config flag
- [ ] Revisit scope after the above ships — calendar/email integration etc.
      can be decided then

---

## Suggested build order

| Order | Work | Why first |
|-------|------|-----------|
| 1 | Phase 2.1 reminder diagnostics | Smallest, fixes an active bug, no deps |
| 2 | Phase 0 foundations | Unblocks everything else |
| 3 | Phase 1 Actual Budget | Highest value-per-effort once push exists |
| 4 | Phase 4 tmux/Claude UX | Self-contained, daily quality-of-life |
| 5 | Phase 2.2 server-driven reminder | Closes the reminder reliability loop |
| 6 | Phase 3 coach | Largest; benefits from all foundations |
| 7 | Phase 5 assistant | Shaped by everything above |

## Open decisions

1. **Push transport**: FCM (recommended for reliability) vs self-hosted ntfy
   (no Google dependency, needs foreground connection on the phone).
2. **Coach LLM**: local llama.cpp vs Claude API for weekly plan generation.
3. **Garmin scope**: pull-only vs webhook registration (webhooks need the
   endpoint reachable by Garmin — i.e., not Tailscale-only; pull avoids that).
4. **Auth**: still Tailscale-perimeter-only; fine for now, but the fs/tmux
   creation endpoints make a shared bearer token cheap insurance.
