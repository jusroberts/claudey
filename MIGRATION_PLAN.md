# Android → Phoenix Migration Plan

Moving the agentic loop from the Android app to the Phoenix server.
The Android app becomes a thin WebSocket client that handles UI and device-side tool execution.

## Architecture

```
Android  ──WS──▶  Caddy (11435)  ──▶  Phoenix (46058)  ──HTTP──▶  llama.cpp (11434)
```

## Phase 1: Server validation

- [ ] Verify Phoenix server starts cleanly (`mix phx.server`)
- [ ] Verify LlamaClient can reach llama.cpp (`LlamaClient.ping/0`)
- [ ] Send a real chat message through LlamaClient and get a response

## Phase 2: Android — add Phoenix channel client

- [ ] Add `JavaPhoenixClient` to `app/build.gradle.kts`
- [ ] Sync and verify it resolves

## Phase 3: Android — replace AgentService

- [ ] Create `service/PhoenixChannelService.kt`
  - [ ] Connect to `agent:<client_id>` on Phoenix
  - [ ] Send `user_message` events
  - [ ] Receive and emit `thinking`, `assistant_message`, `error` events
  - [ ] Receive `tool_request` → dispatch via `ToolDispatcher` → send `tool_result`
  - [ ] Handle reconnection
- [ ] Update `MainViewModel` to use `PhoenixChannelService` instead of `AgentService`
- [ ] Update `SettingsScreen` connection test to ping Phoenix (`/health`) instead of llama

## Phase 4: Android — cleanup

- [ ] Delete `llama/LlamaClient.kt`
- [ ] Delete `llama/LlamaModels.kt`
- [ ] Delete `tools/AgentTools.kt`
- [ ] Delete `service/AgentService.kt`
- [ ] Remove unused imports throughout

## Phase 5: End-to-end test

- [ ] Start Phoenix server
- [ ] Build and install Android app
- [ ] Send a message from the app
- [ ] Verify response arrives via Phoenix
- [ ] Test a device-side tool call (e.g. "play something on Spotify")
- [ ] Test a server-side tool call (e.g. "what's the weather")
