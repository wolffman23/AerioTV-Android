# Adaptarr Telemetry Preflight

**Status:** Telemetry dry-run design only. Consent wording approved by Jeremy. No telemetry is enabled by this document.

## Scope and invariants

This document defines prerequisites for a future **telemetry dry-run**. It does not authorize telemetry reporting from Automatic mode, durable telemetry storage, channel changes, or Dispatcharr mutations.

Automatic session quality is a separate, local playback-control feature. It may use one fresh local probe and one authenticated configuration read to make a session-only re-prime, but it does not call telemetry/report/recommendation endpoints and is not authorized by this telemetry document.

The shipped Recommend-only behavior remains the telemetry baseline:

- A user explicitly runs a bounded local speed test.
- AerioTV fetches authenticated `GET /v1/config` metadata and chooses a profile locally.
- Measurements, opaque network keys, and recommendation requests do not leave the device.
- Any future telemetry dry-run remains advisory-only and cannot change playback.

## Observed current contracts

### Android

- `SettingsViewModel.runAdaptarrSpeedTest()` coordinates the user-initiated probe and local recommendation.
- `AdaptiveProbeCoordinator` derives an opaque HMAC-SHA256 network key from coarse transport, metered, VPN, and Adaptarr-base-URL inputs. It never uses SSID, BSSID, IP address, or location. The HMAC secret is process-local, so a key rotates after process restart.
- `AdaptarrClient` has dormant `reportProbe`, `reportTelemetry`, `telemetrySummary`, and `recommendation` operations, but production Android call sites remain absent.
- Existing tests assert zero calls to all report/recommendation operations for local Recommend-only.
- `AdaptiveQualityMode.Auto` is a persisted, session-only mode. Its playback path is independently guarded by trusted-local-live eligibility, a fresh non-persisting probe, a configuration read, and a current-session authorization gate immediately before any re-prime.
- Persistent HTTP diagnostics are allowlisted to method/status-only records; Adaptarr diagnostics are fixed outcome markers without tokens, URLs, network keys, or measurements.

### Companion

The companion already provides strict, authenticated endpoints:

- `POST /v1/telemetry/report`
- `POST /v1/telemetry/summary`
- `POST /v1/recommendation`

Telemetry is currently in-memory only: one-hour TTL, at most 20 samples per opaque key, and at most 256 network-key buckets. A process restart clears it. Recommendations require at least three samples and use a conservative lower-quartile throughput estimate. Responses do not return raw samples or the opaque key.

The existing `TelemetryStore` enforces those retention and cardinality bounds, but it does not impose a time-based report-ingestion limit. Before telemetry dry-run implementation, add a small process-local limiter for telemetry reports: one accepted report per opaque key per 15 minutes and a conservative service-wide ceiling. This is a shared-bearer abuse guard, not a telemetry-retention mechanism.

The current bearer is a single shared AerioTV-to-Adaptarr credential. Existing `rotate_token` support rotates it immediately; the companion reloads configuration on every request.

## Recommended dry-run protocol

### Data allowed to leave the device after explicit opt-in

Only the existing strict telemetry DTO fields:

- `network_key`: 64-character opaque HMAC value;
- `bytes_transferred`;
- `duration_ms`;
- `latency_ms` when actually measured;
- `max_height` only for a later dry-run recommendation lookup.

### Data prohibited from leaving the device

- bearer token outside the Authorization header;
- raw SSID/BSSID/IP or location;
- playlist, stream, or endpoint URLs;
- channel IDs and output-profile IDs;
- Android/device/advertising identifiers;
- raw sample arrays;
- precise user activity or playback data.

Do not silently persist a per-install HMAC secret to improve sample continuity. Process-scoped keys intentionally limit cross-restart correlation; a future persistent-key proposal requires separate disclosure and approval.

## Required opt-in UX

Introduce a separate **Telemetry dry-run** toggle, default Off, subordinate to Recommend-only mode.

The disclosure must plainly say:

1. the opaque key and speed/latency values leave the device;
2. URLs, channel IDs, tokens, and raw network identifiers do not;
3. data is held only in companion memory for up to one hour, bounded to 20 samples per key;
4. three samples are required before a server recommendation can be meaningful;
5. results remain advisory-only and cannot change playback in this phase.

Turning the toggle Off must synchronously cancel undispatched/in-flight reporting work, prevent later report/summary/recommendation calls, and retain no client-side raw telemetry. There is no delete endpoint in this phase: any already-reported server samples remain only in memory and expire within one hour.

## Android state-machine requirements

Only a fresh, manual Recommend-only probe with opt-in enabled may enter:

`Idle -> Sampling -> Reporting -> Settled`

Cancel and discard the result on:

- opt-out;
- mode change away from Recommend-only;
- quality-cap change;
- real network-identity change;
- a superseding speed-test operation;
- leaving/resetting the settings operation.

There must be at most one reporting/recommendation cycle in flight. Never resubmit an old-network result under a new opaque key. Cancellation must propagate; it must not be converted to a failure decision.

## Hard gates

### Local Recommend-only -> telemetry dry-run

All conditions are required:

1. Rotate the exposed/shared Adaptarr bearer and verify the new token live.
2. Use the existing single shared bearer for the dry-run; per-device credential issuance is explicitly out of scope.
3. Ship explicit, default-Off consent copy and have it reviewed.
4. Preserve Automatic mode's strict separation from telemetry: no telemetry/report/recommendation call may be made by its playback path.
5. Keep `dry_run: true` literal server behavior unchanged.
6. Prove telemetry dry-run code cannot call playback, URL rewrite, output-profile, channel, or Dispatcharr mutation paths.
7. Confirm server-side request-abuse/rate limits for telemetry endpoints.

### Telemetry dry-run -> Automatic

Telemetry dry-run does not authorize or configure Automatic mode. Any future telemetry-informed Automatic proposal requires separate privacy and playback-safety review after the user-defined dry-run observation period.

## Required tests and verification

### Android

- Default-off/opt-out produces exact zero calls to probe-report, telemetry-report, summary, and recommendation endpoints.
- Opt-out, mode/cap change, network change, and superseding test cancel reporting without a stale persisted decision.
- No report is emitted after a real network identity changes.
- Sanitized HTTP/diagnostic tests prove telemetry payload fields cannot reach logs.
- Static verification preserves zero telemetry/report/recommendation call paths into playback URL/output-profile mutation helpers.
- Automatic-mode tests verify its session-only playback path performs no telemetry/report/recommendation call.

### Companion

- Strict DTOs reject unexpected fields such as `url` or `ssid`.
- TTL, per-key cap, and LRU network-key cap are tested at boundaries.
- Responses never contain raw samples or opaque keys.
- Old bearer fails after rotation on subsequent requests.
- Telemetry endpoint rate-limit/abuse behavior is verified.

### Manual sign-off before enabling dry-run

- Packet-capture one opted-in cycle and inspect for absence of URLs, SSID/BSSID, IPs, tokens, and channel IDs.
- Verify token rotation in the live environment.
- Confirm no playback state changes occur during telemetry/report/recommendation operations.

## Proposed telemetry consent copy

### Toggle label

**Share anonymous speed measurements for dry-run recommendations**

### Disclosure

> When enabled, AerioTV may send a measurement only after you explicitly run a speed test while **Recommend only** is selected. The companion receives an opaque temporary network key plus the measured transfer size and duration, and latency only when it is actually available.
>
> AerioTV does **not** send your Wi-Fi name, IP address, location, device ID, channels, playback history, stream URLs, output-profile IDs, or bearer token. The companion keeps these bounded samples only in memory for up to one hour (at most 20 per opaque key). Three recent samples are needed before its dry-run recommendation can be meaningful.
>
> Recommendations remain advisory. They cannot change playback, tune a channel, alter an output profile, or enable Automatic mode. Turn this setting off at any time to stop new reports; existing in-memory samples expire within one hour.

## Outstanding decisions

1. The existing single shared bearer is accepted for the telemetry dry-run; per-device credentials are not planned for this phase.
2. Opt-out stops new reports immediately; in-memory samples expire within one hour and no delete endpoint is planned for this phase.
3. What observation period and incident criteria would be required before considering an Automatic-mode proposal?
