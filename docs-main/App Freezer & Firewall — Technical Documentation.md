# App Freezer & Firewall — Technical Documentation

| | |
|---|---|
| **Version** | 1.0 |
| **Platform** | Android (min SDK 26, target SDK 34) |
| **Distribution** | Sideloaded APK (not Play Store / App Store) |
| **Author** | (your name) |
| **Last updated** | (date) |

## Table of Contents

**Architecture Doc**

- 1.1 [System Overview](#11-system-overview)
- 1.2 [Component Diagram](#12-component-diagram)
- 1.3 [Layer Responsibilities](#13-layer-responsibilities)
- 1.4 [External Dependencies](#14-external-dependencies)
- 1.5 [Runtime Boundaries](#15-runtime-boundaries)

**Design Doc**

- 2.1 [Design Goals & Constraints](#21-design-goals--constraints)
- 2.2 [Key Design Decisions (ADR)](#22-key-design-decisions-adr)
- 2.3 [Data Model](#23-data-model)
- 2.4 [UI Structure](#24-ui-structure)
- 2.5 [Permission Model](#25-permission-model)
- 2.6 [Security & Threat Model](#26-security--threat-model)

**Logic Doc**

- 3.1 [App Scan Flow](#31-app-scan-flow)
- 3.2 [Freeze / Unfreeze Flow](#32-freeze--unfreeze-flow)
- 3.3 [Internet Block Flow (VPN)](#33-internet-block-flow-vpn)
- 3.4 [State Machine](#34-state-machine)
- 3.5 [Error Handling Matrix](#35-error-handling-matrix)
- 3.6 [Edge Cases](#36-edge-cases)

**Appendices**

- [Appendix A — Glossary](#appendix-a--glossary)
- [Appendix B — Versioning & Compatibility](#appendix-b--versioning--compatibility)

---

# 1. Architecture Doc

## 1.1 System Overview

App Freezer & Firewall is an Android utility that gives the user three core capabilities over any installed app:

| # | Capability | Mechanism |
|---|------------|-----------|
| 1 | Scan all installed apps (count + info) | `PackageManager` with `QUERY_ALL_PACKAGES` |
| 2 | Freeze / Unfreeze an app persistently | `pm disable-user` / `pm enable` via Shizuku |
| 3 | Block internet for selected apps | Local `VpnService` tunnel with per-app allow/block |

The app is not distributed through a store. It is installed as a signed APK via `adb install` or a file manager, which relaxes Play Store policy constraints (notably `QUERY_ALL_PACKAGES` and the Shizuku dependency).

The design deliberately avoids root as a hard requirement. It uses **Shizuku** — a widely-adopted Binder-forwarding tool that lets a normal app invoke shell-level APIs after one-time user activation over ADB or root.

## 1.2 Component Diagram

```text
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                             │
│  (UI host, requests Shizuku + VPN permissions)                  │
└────────────┬───────────────────────────────────┬────────────────┘
             │ binds                             │ binds
             ▼                                   ▼
┌────────────────────────┐          ┌──────────────────────────────┐
│  AppControlViewModel   │          │       AppAdapter             │
│  (orchestration, state)│◄────────►│  (RecyclerView rows)         │
└─────┬───────┬───────┬──┘          └──────────────────────────────┘
      │       │       │
      │       │       └──────────────► AppDatabase (Room)
      │       │                        └── BlockedAppEntity
      │       │
      │       └──────────────────────► FirewallVpnService
      │                                 (VpnService, foreground)
      │
      └──────────────────────────────► AppScanner
                                       └── PackageManager
                                      ShizukuHelper
                                       └── Shizuku Binder
                                            └── pm / am shell
```

## 1.3 Layer Responsibilities

| Layer | Package | Responsibility |
|-------|---------|----------------|
| UI | `com.yourapp.freezer` (Activity, Adapter) | Render list, capture taps, launch permission intents |
| ViewModel | `ui.AppControlViewModel` | Single source of truth; orchestrates scanner, DB, Shizuku, VPN |
| Domain / Core | `core.AppScanner`, `core.ShizukuHelper`, `core.FirewallVpnService` | Platform integrations and privileged ops |
| Data | `data.*` | Room entities, DAOs, DB; in-memory DTOs (`AppInfo`) |
| Util | `util.*` | Small shared helpers (extensions) |

**Rules:**

1. UI never touches platform APIs directly (except permission launchers).
2. ViewModel never builds intents for external components — those live in `core` companion factories.
3. Core classes are stateless where possible; state lives in ViewModel + DB.

## 1.4 External Dependencies

| Dependency | Purpose | Risk |
|------------|---------|------|
| `androidx.*`, material | UI | Low |
| `androidx.room` | Persistence of blocked set | Low |
| `dev.rikka.shizuku:api` | Binder proxy to privileged shell | Requires Shizuku app installed + activated |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Async | Low |

No network, analytics, or crash SDKs are included — this app is intentionally offline-first.

## 1.5 Runtime Boundaries

| Boundary | Note |
|----------|------|
| App sandbox | Our app runs as a normal unprivileged app UID. It cannot disable or reroute traffic of other apps directly. |
| Shizuku boundary | Privileged ops cross a Binder boundary into Shizuku, which runs as shell (uid 2000) or root (uid 0). |
| VPN boundary | `VpnService` gives us a virtual interface that all allowed routes flow through. Other apps cannot see this interface. |
| Foreground service | Required for long-running VPN. Notification mandatory (Android 8+). |

---

# 2. Design Doc

## 2.1 Design Goals & Constraints

### Goals

- **Full app inventory** — count and metadata for every app on the device, including system apps.
- **Reversible freeze** — an app can be disabled and later re-enabled without data loss or user reinstall.
- **Granular internet kill-switch** — blocking is per-app, not per-device.
- **No root required** — usable on a stock device.
- **Offline and self-contained** — no telemetry, no remote config.
- **Survives reboot** — freeze state persists; VPN auto-restores.

### Constraints

- Android only (iOS does not permit consumer apps to control other apps).
- Requires Shizuku app installed & activated by the user (one-time setup).
- Only one VPN can be active per device.
- `QUERY_ALL_PACKAGES` only auto-granted because we sideload (not stored).
- `pm disable-user` may be refused on some vendor ROMs for protected packages.

## 2.2 Key Design Decisions (ADR)

### ADR-001: Use Shizuku instead of root

- **Context:** Freezing requires `DISABLE` / `ENABLE` on other packages, which is a signature/system-level permission. Historically this meant root.
- **Decision:** Integrate Shizuku. Users install Shizuku (also open-source) once, activate via ADB or root, then grant our app the Shizuku permission. Our app forwards shell commands to Shizuku's Binder.
- **Alternatives rejected:**
  - Root with `su` — too intrusive for many users; breaks banking apps.
  - Device Owner / DPM provisioning — requires factory reset or ADB provisioning, only viable for enterprise.

### ADR-002: Use `pm disable-user` CLI instead of `PackageManager.setApplicationEnabledSetting()`

- **Context:** `setApplicationEnabledSetting()` is the canonical API but is a hidden method; calling it requires either reflection on `IPackageManager` AIDL stubs or compiling against hidden SDK — fragile and version-dependent.
- **Decision:** Execute `pm disable-user --user 0 <pkg>` / `pm enable <pkg>` through Shizuku shell. Semantically equivalent for the user, resilient across Android versions, and simpler to maintain.

### ADR-003: VPN tunnel with allow-list bypass rather than `iptables` DROP

- **Context:** `iptables OUTPUT -m owner --uid-owner <uid> -j DROP` is the classic way to block an app. But `iptables` requires root or a very specific shell module.
- **Decision:** Build a `VpnService` that:
  1. Routes all traffic into the tunnel (`0.0.0.0/0`, `::/0`).
  2. Adds every allowed app via `addDisallowedApplication()` — these apps bypass the VPN and keep normal connectivity.
  3. Drops every packet drained from the tunnel.
- **Net effect:** blocked apps have no path to the internet (their packets enter the tunnel and are discarded); all other apps are unaffected.
- **Advantages:** No root, works on all modern Android, stable.
- **Trade-offs:** Occupies the VPN slot (one at a time); user must accept the VPN consent dialog once.

### ADR-004: Room for persistence

Blocked-app set is tiny (typically < 100 rows). Room is the standard, well-supported solution with compile-time SQL validation via KSP. Alternative (`SharedPreferences`) rejected because we want a clear schema and indexed lookups.

### ADR-005: ViewModel as single source of truth

All mutations (freeze, block, unblock) go through the ViewModel. The UI never calls `ShizukuHelper` or `FirewallVpnService` directly. This gives us one place to reason about concurrency, error surfacing, and re-scan policy.

## 2.3 Data Model

### Runtime model (in memory)

```text
AppInfo
├── packageName: String        (identity)
├── label: String              (from PackageManager)
├── icon: Drawable?            (from PackageManager)
├── uid: Int                   (Linux uid; used by VPN/i, future iptables)
├── versionName: String?       (informational)
├── isSystem: Boolean          (UI grouping; blocks uninstalling)
├── isFrozen: Boolean          (computed from PM enabled state)
└── isInternetBlocked: Boolean (from DB + blocked cache)
```

### Persistence (Room)

```text
Table: blocked_apps
┌────────────────┬──────────────┬─────────────────────────────┐
│ packageName PK │ TEXT NOT NULL │ one row = one blocked app  │
└────────────────┴──────────────┴─────────────────────────────┘
```

Frozen state is **not** stored — it is derived at scan time from `PackageManager.getApplicationEnabledSetting()`, so it always reflects reality even if another tool changes it.

### UiState (Compose-free ViewModel state)

```text
apps           : List<AppInfo>
totalApps      : Int
shizukuReady   : Boolean
busyPackages   : Set<String>    // in-flight freeze/block
error          : String?
firewallOn     : Boolean
```

## 2.4 UI Structure

Single activity, single screen:

```text
┌───────────────────────────────────────────────┐
│  App Freezer                                  │
│  Total apps: 247                              │
│  Shizuku: READY   [Grant Shizuku]             │
├───────────────────────────────────────────────┤
│  [icon]  Chrome          Frozen  [ ] Block[ ] │
│          com.android.chrome • uid 10123       │
├───────────────────────────────────────────────┤
│  [icon]  Instagram       Frozen  [ ] Block[ ] │
│          com.instagram.android • uid 10198    │
├───────────────────────────────────────────────┤
│  ...                                          │
└───────────────────────────────────────────────┘
```

Each row has:

- Icon, label, package name, uid
- **Freeze switch** → invokes `ShizukuHelper.freeze/unfreeze`
- **Block switch** → updates DB + VPN

Top bar shows total app count and Shizuku status with a **Grant** button.

## 2.5 Permission Model

| Permission | Why | How granted |
|------------|-----|-------------|
| `QUERY_ALL_PACKAGES` | Enumerate every app | Auto (sideload) |
| `moe.shizuku.manager.permission.API_V23` | Call Shizuku Binder | Runtime dialog from Shizuku manager |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep VPN alive | Manifest (auto) |
| `POST_NOTIFICATIONS` | VPN notification | Runtime (Android 13+) |
| VPN consent | `VpnService.prepare()` | One system dialog per install |
| `BIND_VPN_SERVICE` | Service restriction | Manifest (auto, system-only) |

## 2.6 Security & Threat Model

| Threat | Mitigation |
|--------|------------|
| Malicious app uses our Shizuku grant to hide itself | Our code only issues `pm enable/disable` and `am force-stop`; no arbitrary command injection from UI. Shell strings are built from validated `packageName` values (`[a-zA-Z0-9._]`). |
| Attacker crafts an intent to start our VPN with arbitrary blocklist | `FirewallVpnService` is `exported="false"`. Only our app can start/update it. |
| User accidentally freezes a critical system app (e.g., SystemUI) | UI should grey-out / warn on system packages (`isSystem == true`) — planned feature, flagged as a follow-up. |
| Shizuku dies mid-operation | `ShizukuHelper.exec` returns `Result.failure`; ViewModel surfaces error and refreshes. Freeze may or may not have applied — refresh reconciles. |
| VPN silently drops | Notification stays visible; user can toggle off from notification (planned) or reopen the app. |

---

# 3. Logic Doc

## 3.1 App Scan Flow

```text
[UI: onResume] → ViewModel.refresh()
    │
    ▼
[VM] blocked = dao.getAll().toSet()
    │
    ▼
[VM] apps = AppScanner.scanAll(blocked)
    │
    ▼
[Scanner] pm.getInstalledApplications(GET_META_DATA)
    │
    ├── filter out own package
    ├── map each to AppInfo:
    │     isFrozen = (getApplicationEnabledSetting ∈ {DISABLED_USER, DISABLED, DISABLED_UNTIL_USED})
    │               OR (!appInfo.enabled)
    │     isInternetBlocked = packageName ∈ blocked
    ├── sort by (isSystem, label)
    └── return List<AppInfo>
    │
    ▼
[VM] emit UiState(apps, totalApps, shizukuReady, ...)
    │
    ▼
[UI] adapter.submitList(apps)
```

- **Complexity:** O(n) over installed packages; n typically < 400, sub-100 ms on modern devices.
- **Thread:** runs on `Dispatchers.IO` inside `AppScanner`.

## 3.2 Freeze / Unfreeze Flow

```text
User taps Freeze switch
    │
    ▼
ViewModel.toggleFreeze(app)
    │
    ├─ if !ShizukuHelper.hasPermission() → surface error, abort
    │
    ├─ mark app.packageName as busy (UI disables switch)
    │
    ▼
ShizukuHelper.freeze(pkg)  or  unfreeze(pkg)
    │
    ▼
Shizuku.newProcess(["sh", "-c", "pm disable-user --user 0 <pkg>"])
    │
    ├─ stdout ok + exit 0  → Success
    └─ non-zero            → Failure(reason)
    │
    ▼
VM: clear busy, set error if failure
    │
    ▼
VM.refresh()   ← re-scan; isFrozen recomputed from PM
```

**Why re-scan instead of optimistic update?** A freeze can silently fail on vendor ROMs (e.g., some packages are protected). Always derive state from `PackageManager`.

**Optional pre-step:** `am force-stop <pkg>` before disabling, to kill any running instance whose notifications may already be queued. Exposed as `ShizukuHelper.forceStop` but not auto-invoked — user may want a clean "disable while running" for testing.

## 3.3 Internet Block Flow (VPN)

```text
User taps Block switch on an app
    │
    ▼
ViewModel.toggleInternetBlock(app, launchIntent)
    │
    ├─ if app.isInternetBlocked → dao.delete(pkg)      [unblock path]
    └─ else                     → dao.insert(pkg)      [block path]
    │
    ▼
newBlocked = dao.getAll().toSet()
    │
    ├─ if newBlocked is empty → FirewallVpnService.stop()  → done
    │
    ├─ if VpnService.prepare() != null:
    │     launch consent intent, remember newBlocked
    │     on result → applyVpnState(newBlocked)  ← onResume-style callback
    │
    └─ else → applyVpnState(newBlocked)
```

### `applyVpnState(blocked)`

```text
if blocked.isEmpty():
    FirewallVpnService.stop(ctx)
else if isVpnActive():
    FirewallVpnService.update(ctx, blocked)   ← rebuild tunnel
else:
    FirewallVpnService.start(ctx, blocked)
```

### `FirewallVpnService.onStartCommand`

```text
ACTION_START:
    blocked ← EXTRA_BLOCKED
    startForeground(NOTIF_ID, notification)
    buildTunnel()

ACTION_UPDATE:
    blocked ← EXTRA_BLOCKED
    teardown()
    buildTunnel()

ACTION_STOP:
    teardown()
    stopSelf()
```

### `buildTunnel()` — the crucial bit

```text
builder = VpnService.Builder()
    .setSession("App Freezer Firewall")
    .addAddress("10.111.222.1", 32)
    .addRoute("0.0.0.0", 0)
    .addAddress("fd00::1", 128)
    .addRoute("::", 0)
    .setBlocking(true)
    .setMtu(1500)

for each installed app:
    if app.packageName ∉ blocked:
        builder.addDisallowedApplication(app.packageName)   ← bypass VPN

builder.addDisallowedApplication(ourOwnPackage)             ← never block ourselves

vpnInterface = builder.establish()
if vpnInterface == null: log + abort

spawn drain coroutine:
    while active:
        read packet bytes from vpnInterface.fd
        DISCARD                      ← no forward = no internet for blocked apps
```

- **Result for a blocked app:** its sockets bind to the device's default network, but the routing table points all traffic to our TUN interface; packets arrive at `vpnInterface`; we read and drop them; the app's TCP retransmits until timeout → effectively offline.
- **Result for a non-blocked app:** `addDisallowedApplication` removes it from the tunnel's routing table for that uid — it keeps using Wi-Fi/cellular directly.

## 3.4 State Machine

Each app row exists in one of four states:

```text
            ┌─────────────┐
            │ NOT_FROZEN  │
            │  NOT_BLOCKED│
            └──┬───────┬──┘
      Freeze   │       │   Block
               ▼       ▼
   ┌─────────────┐   ┌─────────────┐
   │   FROZEN    │   │  NOT_FROZEN │
   │ NOT_BLOCKED │   │   BLOCKED   │
   └──────┬───┬──┘   └──────┬───┬──┘
 Unfreeze │   │ Freeze+Block│   │ Block off
          │   │  combined   │   │
          ▼   ▼             ▼   ▼
      (start) (combined) (combined) (start)
               │
               ▼
           ┌─────────────┐
           │   FROZEN    │
           │   BLOCKED   │
           └─────────────┘
```

**Combined state is valid and independent.** Freeze does not require blocking and vice versa. A frozen app cannot run, so blocking is redundant while frozen — but keeping both means unfreezing later restores the block automatically.

### VPN service state machine

```text
STOPPED ──start()──► RUNNING
   ▲                    │
   │                    ├─update()──► RUNNING (rebuilt)
   │                    │
   └────stop()──────────┘

   ▼ (system kill)
   RECONCILED BY START_STICKY → onStartCommand re-runs with last intent
```

## 3.5 Error Handling Matrix

| Situation | Detection | Handling |
|-----------|-----------|----------|
| Shizuku not installed | `Shizuku.pingBinder()` throws/returns false | UI shows "Install Shizuku" message; freeze switches disabled |
| Shizuku installed, permission not granted | `checkSelfPermission() != GRANTED` | Show "Grant Shizuku" button; VM refuses freeze ops |
| Shizuku dies mid-command | `newProcess(...)` throws | Return `Result.failure`; ViewModel surfaces error; refresh next tick |
| `pm disable-user` refused (protected pkg) | exit code ≠ 0 or stderr contains `Error` | Surface stderr; UI keeps app unfrozen after refresh |
| VPN consent denied | `ActivityResult.resultCode != RESULT_OK` | Leave DB unchanged or revert insert (optional); no tunnel started |
| Another VPN is already active | `VpnService.prepare()` returns null but tunnel fails to establish | `establish()` returns null; error surfaced; suggest disabling other VPN |
| Notification permission denied (Android 13+) | `POST_NOTIFICATIONS` not granted | Foreground service may not show; VPN still works but user has no indicator — nag user to grant |
| PackageManager returns stale enabled state | rare race | `refresh()` re-reads on resume |
| App uninstalled while blocked | `ACTION_PACKAGE_REMOVED` | (planned) remove from DB and tunnel |

## 3.6 Edge Cases

| Case | Behavior |
|------|----------|
| User freezes the app that's currently in the foreground (our own app is filtered out at scan) | N/A — own app never appears |
| User freezes a launcher / SystemUI | The UI will show system apps; a confirmation dialog for `isSystem == true` is planned — currently the switch is enabled (dev build) |
| User blocks all apps including their browser | All those apps lose internet; system services still work because Android system UIDs are not in the tunnel |
| Zero apps blocked | VPN service is stopped; notification disappears; device reverts to normal routing |
| Reboot with apps blocked | VPN does not auto-start (Android does not auto-start VPN services). Options: (a) a boot receiver that calls `FirewallVpnService.start` if the DB is non-empty — planned; (b) `VpnService` with `setAlwaysOn` configured by the user in system settings |
| Shizuku upgraded | Binder may go stale; `pingBinder()` returns false until device reboot; UI reflects |
| Freeze then uninstall via Settings | Allowed by system; app is gone; next scan drops it |
| Reinstall a previously-frozen app | `pm disable-user` state is per-install; a reinstall clears it — user must freeze again (correct behavior) |
| Multiple VPN apps installed | Only one active; ours refuses to start while another is up; UI should warn |

---

## Appendix A — Glossary

| Term | Meaning |
|------|---------|
| Shizuku | Third-party daemon that exposes shell/root APIs to normal apps via a stable Binder interface. |
| `pm disable-user` | Android shell command that persistently disables a package without uninstalling. |
| TUN interface | Virtual network interface created by `VpnService`; all routed packets arrive here for our app to read. |
| `addDisallowedApplication` | `VpnService.Builder` method that excludes an app from the tunnel — the app bypasses the VPN entirely. |
| `QUERY_ALL_PACKAGES` | Android 11+ permission required to enumerate all installed apps. |

## Appendix B — Versioning & Compatibility

| Android version | Notes |
|-----------------|-------|
| 8.0 (API 26) | Minimum supported; foreground service required |
| 10 (API 29) | Scoped storage irrelevant (we don't read external storage) |
| 11 (API 30) | `QUERY_ALL_PACKAGES` required; auto-granted on sideload |
| 13 (API 33) | `POST_NOTIFICATIONS` runtime permission required |
| 14 (API 34) | `FOREGROUND_SERVICE_SPECIAL_USE` type required for VPN |

*End of documentation.*
