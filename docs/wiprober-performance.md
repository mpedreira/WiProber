# WiProber performance export (`wiprober-performance.json`)

Specification of the file produced by the [mpedreira/WiProber](https://github.com/mpedreira/WiProber) fork so that an external application (e.g. an analysis web app) can join it with the Ekahau `.esx`.

Status: **format 2** (one row per active run, with run and phase statuses). Format 1 was produced by releases `v3.0.0-throughput.1` to `.4` (see [History](#7-history)).

## 1. What is exported

| Project state | *Export* produces |
|---|---|
| No Stop-and-Go point has performance results and no Continuous track has a roaming log | `Project.esx`, exactly as the original app |
| Otherwise | `Project.zip` |

ZIP contents (always exactly these two entries, at the root):

```
Project.zip
├── Project.esx                  ← the same .esx a plain export produces; Ekahau opens it as usual
└── wiprober-performance.json    ← the measurements (this document)
```

Nothing from WiProber is written inside the `.esx`, and Ekahau's native `throughputSessions`/`pingSessions` fields are not filled (their schema is not public).

## 2. JSON structure

```json
{
  "format": "wiprober-performance",
  "version": 1,
  "generatedAt": "2026-11-26T10:41:03.512Z",
  "esxFile": "Project.esx",
  "activeRuns":      [ { …row… }, … ],
  "measurements":    [ { …row… }, … ],
  "networkTests":    [ { …row… }, … ],
  "scriptSteps":     [ { …row… }, … ],
  "roamingSessions": [ { …row… }, … ],
  "roamingEvents":   [ { …row… }, … ]
}
```

| Field | Type | Description |
|---|---|---|
| `format` | string | Always `"wiprober-performance"` |
| `version` | int | `2`. Changes on a semantic change; new fields are added without bumping it |
| `generatedAt` | ISO-8601 UTC string | Export time |
| `esxFile` | string | Name of the `.esx` next to the JSON in the ZIP |
| `activeRuns` | array | One row per **active run** (every run, also cancelled, failed, interrupted or without Wi-Fi) with its phases — section 2b |
| `measurements` | array | One row per **active run × throughput target** (iPerf3 / Internet) — section 3 |
| `networkTests` | array | One row per **point × imported network test** — section 3b |
| `scriptSteps` | array | One row per **point × imported HTTP script step** — section 3c |
| `roamingSessions` | array | One row per **Continuous track with a roaming probe** — section 3d |
| `roamingEvents` | array | One row per **roam or outage** found in those tracks — section 3d |

Every array is always present (empty when there is no data).

## 2b. Points, scans and runs (`activeRuns`)

A **point** (`pointId`) is a physical position on a floor; each passive Wi-Fi **scan** at it has a `scanId` (one ESX survey), and each set of **active tests** is a **run** (`runId`). The point is saved as soon as the passive scan finishes; the run is recorded with every phase `pending` before anything is measured and is saved progressively, so finished phases survive a later failure, a cancel ("Cancel tests"), a rotation or a process death. Points and results saved by format-1 releases are migrated repeatably (ids derived from floor, time and position).

| Field | Type | Description |
|---|---|---|
| position fields | | As in section 3 (`surveyId` of the run's scan in this ESX, `floorPlanId`, `floorName`, `pointId`, `scanId`, `runId`, `x`, `y`, `measuredAt`) |
| `startedAt`, `endedAt` | ISO-8601 UTC string | Run start and end |
| `status` | string | `completed`, `failed`, `cancelled` (user), `interrupted` (app closed/killed mid-run; never restarted automatically) |
| `statusReason` | string \| null | Error code of the end, e.g. `cancelled`, `timeout` (whole-point deadline), `interrupted` |
| `profile` | string \| null | Configuration used (`device-settings`, `legacy` for migrated results) |
| `phases` | array | Every planned phase: `key` (`target:<targetId>:latency\|download\|upload`, `test:<name>`, `script:<name>:<step>`), `label`, `status` (`pending`, `completed`, `failed`, `skipped`, `cancelled`, `interrupted`), `errorCode`, `endedAt` |

Every phase has a monotonic deadline (latency 15 s, transfers duration + 10 s, network tests by type, script steps 20 s) and the point has one more (all phases plus 15 s). On a deadline or cancel the run's sockets, HTTP connections, worker threads and ping processes are closed, so a blocked transfer ends at once.

## 3. Throughput rows (`measurements`)

General rule for every row type: **anything not measured or not available is `null`, never `0`**. Every field is always present (`null` when there is no value). When a test failed, the reason is in `error`.

### Position and link with the `.esx`

Shared by `measurements`, `networkTests` and `scriptSteps`.

| Field | Type | Description |
|---|---|---|
| `surveyId` | UUID string \| null | ID of the run's scan `survey-<surveyId>.json` **in the `.esx` of the same ZIP** |
| `pointId` | string | Physical point; the same across scans, runs and exports |
| `scanId` | string \| null | Passive scan the run followed |
| `runId` | string | Active run |
| `runStatus` | string | Status of the run (section 2b) |
| `floorPlanId` | UUID string | Floor (`floorPlans.json`). Stable across exports |
| `floorName` | string | Floor name |
| `x`, `y` | number | Position in **original floor-plan image pixels**, origin top-left. Same value as the survey's `surveys[0].routePoints[0][0].location` |
| `measuredAt` | ISO-8601 UTC string | Start of that test |

### Test

| Field | Type | Description |
|---|---|---|
| `target` | string | Exported alias: `internet`, or the label set on the phone for the iPerf3 server (default `corporate`) |
| `targetId` | string \| null | Stable identifier: `iperf3-private` or `internet-http` |
| `category` | string \| null | `private-iperf` or `internet` (network tests and scripts are `internal-service`) |
| `resolvedAddress` | string \| null | Address the target resolved to **on the measured Wi-Fi**; the pre-check and every transfer of the target use it. IPv4 first, at most two addresses tried |
| `downloadEndpoint`, `uploadEndpoint` | string \| null | Host (and port for iPerf3) of each direction; they may differ for HTTP |
| `method` | string | `iperf3-tcp` (iPerf3 server) or `http` (Internet speed test) |
| `durationSeconds` | int | Seconds per direction (default 5) |
| `downMbps` | number \| null | Download in Mbit/s (10⁶ bit/s), 1 decimal |
| `upMbps` | number \| null | Upload in Mbit/s, 1 decimal |
| `latencyMs` | number \| null | Latency = **minimum** of 10 TCP handshakes with the server, ms |
| `latencyAvgMs` | number \| null | Mean of those 10 samples, ms |
| `jitterMs` | number \| null | Mean difference between consecutive samples, ms |
| `latencySamples` | int \| null | Successful handshakes |
| `latencyFailures` | int \| null | Failed handshakes among the 10; the valid ones are kept (`latencyErrorCode` = `partial_loss`) |
| `latencyMethod` | string \| null | `tcp-connect`: TCP handshake round trip, not ICMP |
| `latencyStatus`, `downloadStatus`, `uploadStatus` | string \| null | Phase status: `completed`, `failed`, `skipped` |
| `latencyErrorCode`, `downloadErrorCode`, `uploadErrorCode` | string \| null | Cause per phase: `resolution_failed`, `connect_failed`, `connection_refused`, `timeout`, `iperf_busy`, `iperf_protocol`, `http_status`, `empty_body` (server answered with no body: never reported as 0), `response_too_large`, `skipped_unreachable`, `not_configured`, `not_on_wifi`, `wifi_lost`… |
| `downBytes`, `downSeconds`, `upBytes`, `upSeconds` | number \| null | Bytes counted and real duration of each transfer |
| `error` | string \| null | What failed; `null` when everything was measured |

### Measured network and associated AP

Every test of a run uses one Wi-Fi network (`networkId`): names resolve with its resolver and all sockets and HTTP connections are bound to it, so nothing goes over mobile data, and a local Wi-Fi without Internet is measured while mobile data is the default network. If that network disappears, the remaining phases end with `wifi_lost` instead of using another network. SSID/BSSID are read from **that** network (Android 12+: per-network callback; Android 11: only when it is the only Wi-Fi network). When Android does not allow attributing them, they are `null` and `associationSource` is `unknown`: that means "unknown", not "not connected".

| Field | Type | Description |
|---|---|---|
| `networkId` | string \| null | Android handle of the measured Wi-Fi network |
| `networkSelection` | string \| null | Why it was chosen among several Wi-Fi networks: `only`, `default`, `validated`, `lowest-handle` |
| `associationSource` | string \| null | `network-capabilities`, `wifi-manager` or `unknown` |
| `ssid` | string \| null | SSID at the start |
| `ssidEnd` | string \| null | SSID at the end |
| `associationChange` | string \| null | `same` (both ends observed with the same SSID and BSSID — says nothing about what happened in between), `bssid_changed` (AP change within the SSID), `ssid_changed`, `unknown` |
| `bssidStart` | string \| null | BSSID (lower case) at the start |
| `bssidEnd` | string \| null | BSSID at the end. **Different from `bssidStart` ⇒ the phone roamed during the test** |
| `rssi` | int \| null | Signal of the connected AP, dBm |
| `linkMbps` | int \| null | **Transmit** link rate, Mbit/s |
| `rxLinkMbps` | int \| null | **Receive** link rate, Mbit/s |
| `frequencyMhz` | int \| null | Channel frequency, MHz |
| `channel` | int \| null | Channel number (2.4 / 5 / 6 GHz bands) |
| `channelWidthMhz` | int \| null | 20, 40, 80, 160 or 320 (from the point's scan) |
| `wifiStandard` | string \| null | E.g. `"802.11ax (Wi-Fi 6/6E)"`, `"802.11ac (Wi-Fi 5)"` |
| `bssStationCount` | int \| null | Associated clients according to the beacon's *BSS Load* element (only APs that advertise it) |
| `bssChannelUtilizationPct` | int \| null | Channel utilisation 0–100 % according to *BSS Load* |

### Device

| Field | Type | Description |
|---|---|---|
| `deviceModel` | string \| null | Phone manufacturer and model (RSSI differs by several dB between models) |
| `androidVersion` | string \| null | Android version |

### `error` values

| Text | Meaning |
|---|---|
| `not connected to a Wi-Fi network` | No Wi-Fi: nothing was sent; every value is `null` |
| `iPerf3 server not configured` / `download URL not configured` | Target enabled without a server/URL |
| `latency: <code> (<detail>); download skipped: server unreachable` | Could not even connect to the pre-checked endpoint: its transfers were skipped. An upload to a different host is still attempted and evaluated on its own |
| `download: <reason>` / `upload: <reason>` | That direction failed (e.g. `iperf3 server is busy`, `HTTP 403 from speed.cloudflare.com`) |
| `upload: not configured` | Internet test without an upload URL |

Several reasons are separated by `; `.

## 3b. Network tests (`networkTests`)

Configured in the imported file (`tests` section, see the [example](wiprober-config.example.json)); they run at each point after the throughput tests. Each row also carries the position fields of section 3 (`surveyId`, `floorPlanId`, `floorName`, `x`, `y`, `measuredAt`).

| Field | Type | Description |
|---|---|---|
| `name` | string | Test name in the configuration |
| `type` | string | `dns`, `ping`, `tcp` or `http` |
| `target` | string \| null | Queried host (`dns`/`ping`), `host:port` (`tcp`) or `host/path` without query (`http`) |
| `method` | string \| null | `udp` (query sent straight to the Wi-Fi's DNS server on a socket bound to it) or `system` (that network's resolver, may answer from cache); `icmp` (system ping on the identified Wi-Fi interface to an address resolved on it) or `tcp` (TCP probe bound to the Wi-Fi when ICMP cannot be guaranteed); `tcp`; `http`/`https` |
| `ok` | bool | The test passed: DNS answer, no loss, open port, expected status and text |
| `alias`, `category` | string | Exported alias (`alias` in the configuration, default the name) and `internal-service` |
| `networkId`, `associationSource` | string \| null | Measured Wi-Fi network and where SSID/BSSID came from (see section 3) |
| `ssid`, `bssid`, `rssi` | | Connected AP when the test started |
| `bssidEnd`, `associationChange` | string \| null | AP at the end and the change between both ends |
| `errorCode` | string \| null | Structured cause: `resolution_failed`, `connect_failed`, `connection_refused`, `timeout`, `no_reply`, `partial_loss`, `port_not_configured`, `no_gateway`, `no_dns_server`, `dns_error`, `http_status`, `expectation_failed`, `wifi_lost`, `not_on_wifi`… |
| `dnsServer` | string \| null | DNS server queried (`dns`) |
| `resolvedAddress` | string \| null | First IPv4 address returned (`dns`) |
| `dnsMs` | number \| null | DNS query time (`dns`, `http`) |
| `connectMs` | number \| null | TCP handshake (`http`; the minimum for `tcp`) |
| `tlsMs` | number \| null | TLS handshake (`https`) |
| `ttfbMs` | number \| null | Time to the first response byte (`http`) |
| `downloadMs` | number \| null | Body download (`http`) |
| `totalMs` | number \| null | Whole request (`http`) |
| `status` | int \| null | HTTP status |
| `bytes` | int \| null | HTTP body size |
| `sent`, `received` | int \| null | Probes sent / answered (`ping`, `tcp`) |
| `lossPct` | number \| null | Loss 0–100 % |
| `rttMinMs`, `rttAvgMs`, `rttMaxMs` | number \| null | Round trip min/avg/max |
| `jitterMs` | number \| null | Mean difference between consecutive answers |
| `rttKind` | string \| null | `icmp-echo` or `tcp-connect`: ICMP and TCP round trips are different metrics and must not be mixed |
| `error` | string \| null | Why it failed: `no reply`, `2 of 10 lost`, `NXDOMAIN`, `SERVFAIL`, `status 500, expected 200`, `no default gateway`… |

`${gateway}` and `${dns}` in the configuration are replaced with the default gateway and first DNS server the Wi-Fi network assigned.

## 3c. HTTP script steps (`scriptSteps`)

Scripts from the `scripts` section (or imported from a `.har`): chained requests with variables, values extracted from responses (e.g. a token) and cookies kept between steps. Only GET/HEAD unless the step has `"allowWrite": true`. Every step opens a new connection, so DNS/TCP/TLS are always measured. Each row carries the position fields.

| Field | Type | Description |
|---|---|---|
| `script`, `stepIndex`, `step` | | Script, step number (from 1) and name |
| `method`, `host`, `path` | | Request (without query string) |
| `status` | int \| null | HTTP status |
| `ok` | bool | Status, expected text and extractions all correct |
| `ssid`, `bssidStart`, `bssidEnd`, `rssi` | | Connected AP when the script started/ended |
| `dnsMs`, `connectMs`, `tlsMs`, `ttfbMs`, `downloadMs`, `totalMs` | number \| null | Timing breakdown of the step, ms |
| `bytes` | int \| null | Body size |
| `errorCode` | string \| null | Structured cause |
| `error` | string \| null | E.g. `status 401`, `response does not contain "A-01"`, `cannot extract token from $.data.token`, `unknown variable token` |

## 3d. Continuous-mode roaming (`roamingSessions`, `roamingEvents`)

When the imported configuration enables `roaming`, during every Continuous track the app tries a TCP connect to the target (`host:port`, e.g. `${gateway}:443`) over the Wi-Fi `Network` every `intervalMs` (200 ms by default) and reads the association of that same network. Each attempt records its monotonic start and end, its result, and the SSID/BSSID/RSSI seen.

**Time base.** Waypoints, scans and probe attempts share one monotonic clock relative to the track start (`timeBase: "monotonic-relative"`); `startedAt` is the UTC anchor, and every `…At` field is that anchor plus the relative time. Tracks recorded by fork release 4 have `timeBase: "wall-clock"`.

**Results of an attempt.** `connected` (port accessible), `refused` (the target answered, so it is reachable, but the port is **not** accessible), `timeout`, `unreachable`, `resolution_failed` (the name did not resolve on Wi-Fi), `invalid_target` (e.g. `${gateway}` without a gateway: configuration, not connectivity), `no_wifi`. Placeholders are evaluated only when present, independently (`${dns}` is not needed by `${gateway}:443`), and the resolution is redone when the network, gateway or DNS server changes. Only `timeout`/`unreachable` count as observed loss of connectivity.

**Events.**

| `type` | Meaning |
|---|---|
| `bssid_change` | Another AP of the same SSID |
| `ssid_change` | The phone joined another SSID |
| `association_lost` | No Wi-Fi association for at least one attempt |
| `reachability_loss` | ≥3 consecutive `timeout`/`unreachable` attempts without an association change |
| `roam` / `outage` | Format 1 (fork release 4) only |

An outage is never inferred from scheduler delays, pauses, gaps without samples or isolated timeouts. Duration is bounded by observations: `outageLowerBoundMs` spans the failed attempts actually seen; `outageUpperBoundMs` is the time between the last reachable attempt before and the first one after (the resolution of the estimate). A change with `outageObserved: false` had no failed attempt, which is **not** proof of a seamless roam: check `outageUpperBoundMs` and `samplingGap`. Events that had not recovered when the track ended are kept with `open: true`.

**Position.** An event gets `x`/`y` only when the track has at least two waypoints and the event time falls between the first and the last; it is interpolated in time along the marked route and is approximate (`positionApproximate: true`). Otherwise `x`/`y` are `null`. The `surveyId` is the track's survey in the `.esx` (the session ID, stable across exports). Stopping a track closes its probe: a new track never receives attempts of the previous one.

`roamingSessions`:

| Field | Type | Description |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Track and floor |
| `startedAt`, `endedAt` | ISO-8601 UTC string | Track start and end |
| `timeBase` | string | `monotonic-relative` or `wall-clock` (format 1) |
| `schema` | int | 2, or 1 for logs of fork release 4 |
| `target` | string | Probe target as configured (`${gateway}:443`, `host:port`) |
| `intervalMs`, `requestedIntervalMs` | int | Requested interval between attempts |
| `observedMedianIntervalMs`, `observedMaxIntervalMs` | int \| null | Cadence actually achieved |
| `samplingGaps` | int \| null | Intervals longer than 3 × the requested interval |
| `probes`, `lost` | int | Attempts / attempts that did not reach the target |
| `lossPct` | number \| null | Loss 0–100 % |
| `avgRttMs` | number \| null | Mean connect time of the `connected` attempts |
| `connected`, `refused`, `timeouts`, `unreachable`, `resolutionFailures`, `invalidTarget`, `noWifi` | int \| null | Attempts by result |
| `roams`, `outages` | int | Association changes / other events |
| `bssid_change`, `ssid_change`, `association_lost`, `reachability_loss` | int | Events of each type |

`roamingEvents`:

| Field | Type | Description |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Track and floor |
| `x`, `y` | number \| null | Approximate position on the route (floor-plan pixels), see above |
| `positionApproximate` | bool \| null | `true` when `x`/`y` are present |
| `at` | ISO-8601 UTC string | First attempt that showed the event |
| `type` | string | See the table above |
| `fromSsid`, `toSsid`, `fromBssid`, `toBssid` | string \| null | Association before and after |
| `fromRssi`, `toRssi` | int \| null | Signal before and after, dBm |
| `lastReachableAt`, `firstReachableAt` | ISO-8601 \| null | Last reachable attempt before, first one after |
| `failedAttempts` | int \| null | Failed attempts in the event window |
| `outageObserved` | bool \| null | At least one failed attempt was observed |
| `outageLowerBoundMs`, `outageUpperBoundMs` | int \| null | Bounds of the outage (see above); `null` when not supported by observations |
| `open` | bool \| null | Not recovered when the track ended |
| `samplingGap` | bool \| null | The window contains a gap without samples |
| `scanOverlap` | bool \| null | A Wi-Fi scan interval overlapped the event window (temporal coincidence, not a cause) |
| `outageMs`, `lostProbes`, `duringScan` | | Format 1 fields, kept for logs of fork release 4 |

## 4. Joining with the `.esx`

- **Within the same ZIP**: by `surveyId` → `survey-<surveyId>.json`. The point's full Wi-Fi scan (every AP) is in that survey (`wifiTracks[0]`) and its binary `track-<binaryFileId>.bin`.
- **Across exports** (history, comparisons): by `floorPlanId` + `x` + `y` (suggested tolerance 0.5 px). Stop-and-Go points get a **new `surveyId` on every export**; `floorPlanId`, coordinates and Continuous track IDs do not change.
- **To metres**: `metres = pixels × metersPerUnit` (from `floorPlans.json`). Plan size: `width`/`height`; image: `image-<imageId>`.
- Points **imported from another `.esx`** have no rows. **Continuous** tracks only appear in `roamingSessions`/`roamingEvents`.

Data your app can derive from the `.esx` with no WiProber change: APs on the same channel above −82 dBm (co-channel), APs above −67 dBm (redundant coverage), difference between the best and second AP, and the *BSS Load* of any AP (beacon elements are in `informationElements` of `accessPointMeasurements.json`).

## 5. How values are measured

- After each Stop-and-Go scan, if anything is enabled, the tests run at the same point one after another (never in parallel, so results are not skewed). A dialog shows the progress.
- **All test traffic is bound to the Wi-Fi network**; it never goes over mobile data.
- **iPerf3**: built-in iperf3 protocol client, TCP, 1 stream. Download = reverse mode measured by the phone; upload = what the server reports as received.
- **Internet**: HTTP with 4 parallel connections; Cloudflare by default (`speed.cloudflare.com/__down` and `/__up`), configurable (e.g. LibreSpeed).
- **Latency**: 10 TCP handshakes to the server port (iPerf3) or to 443/80 of the download URL. If the first one fails, the rest of that target is skipped.
- **No noise or SNR**: Android does not expose the noise floor.
- Configuration (servers, URLs, target label, tests, scripts) lives on the phone only, never in the code.

## 6. Not exported

- The **survey guide** (next suggested point, skipped cells, coverage gradient): app display only.
- Scans without performance tests: only in the `.esx`.

## 7. History

| Release | Changes to this file |
|---|---|
| `v3.0.0-throughput.1` | Format 1: ZIP with `.esx` + JSON; position, `surveyId`, `target`, `method`, `downMbps`, `upMbps`, `latencyMs`, `ssid`, `bssidStart`/`bssidEnd`, `rssi`, `linkMbps`, `error` |
| `v3.0.0-throughput.2` | Added `rxLinkMbps`, `frequencyMhz`, `channel`, `channelWidthMhz`, `wifiStandard`, `bssStationCount`, `bssChannelUtilizationPct`, `latencyAvgMs`, `jitterMs`, `latencySamples`, `deviceModel`, `androidVersion`. Latency from 10 samples |
| `v3.0.0-throughput.3` | No export changes (survey guide in the app) |
| `v3.0.0-throughput.4` | Added `networkTests` and `scriptSteps` (imported configuration) and `roamingSessions`/`roamingEvents` (Continuous-mode roaming) |
| next (format 2) | Rows per active run with `pointId`/`scanId`/`runId`, `activeRuns` with run and phase statuses, per-phase status and error codes, bytes and real duration, `latencyFailures`/`latencyMethod`, `rttKind`; roaming schema 2 (typed attempt results, bounded outages, association-change types, monotonic time base, grounded approximate positions); measured-network and association fields (`networkId`, `associationSource`, `ssidEnd`, `associationChange`, `resolvedAddress`, endpoints, `targetId`, `category`, aliases) |
