# WiProber performance export (`wiprober-performance.json`)

Specification of the file produced by the [mpedreira/WiProber](https://github.com/mpedreira/WiProber) fork so that an external application (e.g. an analysis web app) can join it with the Ekahau `.esx`.

Status: **format 1**, implemented since release `v3.0.0-throughput.1` and extended without breaking changes up to `v3.0.0-throughput.4` (see [History](#7-history)).

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
| `version` | int | `1`. Only changes on a breaking change; new fields are added without bumping it |
| `generatedAt` | ISO-8601 UTC string | Export time |
| `esxFile` | string | Name of the `.esx` next to the JSON in the ZIP |
| `measurements` | array | One row per **Stop-and-Go point × throughput target** (iPerf3 / Internet) — section 3 |
| `networkTests` | array | One row per **point × imported network test** — section 3b |
| `scriptSteps` | array | One row per **point × imported HTTP script step** — section 3c |
| `roamingSessions` | array | One row per **Continuous track with a roaming probe** — section 3d |
| `roamingEvents` | array | One row per **roam or outage** found in those tracks — section 3d |

Every array is always present (empty when there is no data).

## 3. Throughput rows (`measurements`)

General rule for every row type: **anything not measured or not available is `null`, never `0`**. Every field is always present (`null` when there is no value). When a test failed, the reason is in `error`.

### Position and link with the `.esx`

Shared by `measurements`, `networkTests` and `scriptSteps`.

| Field | Type | Description |
|---|---|---|
| `surveyId` | UUID string | ID of the point's `survey-<surveyId>.json` **in the `.esx` of the same ZIP** |
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
| `latencySamples` | int \| null | Samples taken (10) |
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
| `latency: <reason>; download/upload skipped: server unreachable` | Could not even connect: transfers were skipped |
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
| `error` | string \| null | E.g. `status 401`, `response does not contain "A-01"`, `cannot extract token from $.data.token`, `unknown variable token` |

## 3d. Continuous-mode roaming (`roamingSessions`, `roamingEvents`)

When the imported configuration enables `roaming`, during every Continuous track the app reads the associated AP every `intervalMs` (200 ms by default) and tries a TCP connect to the target (`host:port`, e.g. `${gateway}:443`) over Wi-Fi; a refused connection also proves connectivity. Events are placed **on the route marked on the map** by interpolating in time between the taps, like the route's scans in the `.esx`. The `surveyId` is the track's survey in the `.esx` (the session ID, stable across exports).

`roamingSessions`:

| Field | Type | Description |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Track and floor |
| `startedAt`, `endedAt` | ISO-8601 UTC string | Track start and end |
| `target` | string | Probe target (`gateway:443`, `host:port`) |
| `intervalMs` | int | Interval between probes |
| `probes`, `lost` | int | Probes sent / failed |
| `lossPct` | number \| null | Loss 0–100 % |
| `avgRttMs` | number \| null | Mean connect time of the successful probes |
| `roams`, `outages` | int | Events of each type |

`roamingEvents`:

| Field | Type | Description |
|---|---|---|
| `surveyId`, `floorPlanId`, `floorName` | | Track and floor |
| `x`, `y` | number \| null | Position on the route (floor-plan pixels) |
| `at` | ISO-8601 UTC string | Event time |
| `type` | string | `roam` (BSSID changed) or `outage` (≥3 consecutive failed probes without changing AP) |
| `fromBssid`, `toBssid` | string \| null | Previous and new AP (equal for an `outage`) |
| `fromRssi`, `toRssi` | int \| null | Signal before and after, dBm |
| `outageMs` | int \| null | Time without connectivity beyond one interval (0 = seamless roam); `null` if it had not recovered when the track ended |
| `lostProbes` | int | Failed probes in the event |
| `duringScan` | bool | A Wi-Fi scan ran during the outage: the phone was off channel, so part of it may be due to the scan |

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
