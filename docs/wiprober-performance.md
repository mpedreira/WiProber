# nimWifi performance export (`wiprober-performance.json`)

Specification of the file produced by the [mpedreira/nimwifi](https://github.com/mpedreira/nimwifi) fork so that an external application (e.g. an analysis web app) can join it with the Ekahau `.esx`.

Status: **format 2** (one row per active run, with run and phase statuses). Format 1 was produced by releases `v3.0.0-throughput.1` to `.4` (see [History](#7-history)).

## 1. What is exported

| Project state | *Export* produces |
|---|---|
| *Export* → **ESX only** | `Project.esx`, exactly as the original app |
| *Export* → **ESX + report and results** (offered always, also for passive or Continuous-only surveys without active tests) | `Project.zip` |

ZIP contents (always exactly these entries, at the root):

```
Project.zip
├── Project.esx                  ← the same .esx a plain export produces; Ekahau opens it as usual
├── wiprober-performance.json    ← the measurements (this document)
└── wiprober-report.html         ← readable report per floor and SSID (section 8); not meant for parsing
```

For an imported project whose only local additions are active runs or planned points, `Project.esx` is the imported archive copied byte for byte (runs and planned points never touch the ESX).

Nothing from nimWifi is written inside the `.esx`, and Ekahau's native `throughputSessions`/`pingSessions` fields are not filled (their schema is not public).

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
  "roamingEvents":   [ { …row… }, … ],
  "requirements":    { … },            // only with requirements configured
  "verdicts":        [ { …row… }, … ], // only with requirements configured
  "apNames":         [ { …row… }, … ], // only with an AP table
  "apInventory":     [ { …row… }, … ]  // every radio of the floors' local scans
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

Every array is always present (empty when there is no data), except the additive `requirements`, `verdicts` and `apNames`, which only appear when the configuration has them (section 3e).

## 3e. Verdict and AP names (optional, additive)

`requirements`: the thresholds applied (see `docs/wiprober-config.md`), plus `rssiThresholdDbm` and `signalProfile`.

`verdicts`: one row per floor and SSID of the report:

| Field | Description |
|---|---|
| `floorName`, `floorPlanId`, `ssid` | Where (join with the `.esx` by `floorPlanId`) |
| `state` | `pass`, `fail` or `missing_data` |
| `pointsPassing`, `pointsFailing`, `pointsMissingData` | Points by verdict |
| `pointsWithData`, `percentPassing` | Denominator (points with valid data for every requirement) and passing % over it; `null` without data |
| `outagesOnFloor` | Reachability/association losses of the SSID on the floor (also without a roaming requirement) |
| `floorChecks[]` | Checks of the SSID on the floor: p95 and roaming |
| `points[]` | `pointId`, `label`, `state`, `checks[]` |

Each check: `requirement` (e.g. `rssi`, `iperf3:corporate:download`, `service:ERP:time`, `outage-during-measurement`, `roaming:outage`), `label`, `state`, `value` (`null` when not measured validly — never 0), `threshold`, `unit`, `runId` (the `activeRuns` row the value comes from) and `reason`.

`apInventory` (4.0.0-rc.5): one row per radio heard in the floors' local scans — `floorName`, `floorPlanId`, `bssid`, `ssids`, `frequencyMhz`, `band` (`2.4`, `5`, `6`), `channel`, `bestRssi`, and what is **inferred** about its AP: `inferredVendor` with `inferredVendorSource` (`wps`: the AP's WPS manufacturer; `oui`: the IEEE assignment of the BSSID's OUI; `vendor-ie`: the OUI of a vendor element, ignoring WMM/WPS, Wi-Fi Alliance and chipsets), `inferredModel` (WPS model, mostly consumer gear), `advertisedName` (name the AP advertises: Cisco element 133, Aruba element) and `locallyAdministered` (such a BSSID has no vendor OUI). Every BSSID is its own row: nothing is grouped. Scans saved before rc.5 only keep the first 512 bytes of their elements, so vendor elements are often missing there.

`apNames`: the user's BSSID → AP table (`bssid`, `name`, `location`, `floor`). With a table, every mapped BSSID field of the arrays above gets its name next to it: `bssid` → `apName`, `bssidStart` → `apNameStart`, `bssidEnd` → `apNameEnd`, `fromBssid` → `fromApName`, `toBssid` → `toApName`. Unmapped BSSIDs get no name; existing fields never change. The `.esx` is not touched.

## 2b. Points, scans and runs (`activeRuns`)

A **point** (`pointId`) is a physical position on a floor; each passive Wi-Fi **scan** at it has a `scanId` (one ESX survey), and each set of **active tests** is a **run** (`runId`). The point is saved as soon as the passive scan finishes; the run is recorded with every phase `pending` before anything is measured and is saved progressively, so finished phases survive a later failure, a cancel ("Cancel tests"), a rotation or a process death. Points and results saved by format-1 releases are migrated repeatably (ids derived from floor, time and position).

**Measuring again at a point.** Tapping an existing point (local, imported from an ESX, or planned from a template) offers *Measure coverage* (new passive scan), *Measure coverage + performance* (the scan and then the tests; same `pointId`, exactly the same coordinates) or *Active tests only* (a run with `scanId: null`). Earlier scans and runs are kept; Undo removes only the latest local scan or run. Rows of a run without a scan of its own take the position of the point; `surveyId` is then `null` (or that of another scan of the same point). Join across exports by `pointId`/`runId`; within one ZIP, `surveyId` links to the ESX.

| Field | Type | Description |
|---|---|---|
| position fields | | As in section 3 (`surveyId` of the run's scan in this ESX, `floorPlanId`, `floorName`, `pointId`, `scanId`, `runId`, `x`, `y`, `measuredAt`) |
| `startedAt`, `endedAt` | ISO-8601 UTC string | Run start and end |
| `status` | string | `completed`, `failed`, `skipped` (*Skip point*), `cancelled` (user), `interrupted` (app closed/killed mid-run; never restarted automatically) |
| `statusReason` | string \| null | Error code of the end, e.g. `cancelled`, `skipped_by_user` (*Skip point*: `status` is `skipped`), `timeout` (whole-point deadline), `interrupted` |
| `profile` | string \| null | Configuration used (`device-settings`, `legacy` for migrated results) |
| `phases` | array | Every planned phase: `key` (`target:<targetId>:latency\|download\|upload`, `test:<name>`, `script:<name>:<step>`), `label`, `status` (`pending`, `completed`, `failed`, `skipped`, `cancelled`, `interrupted`), `errorCode`, `endedAt` |

Each phase is saved together with its value as soon as it finishes (latency, download, upload, every network test and every script step), so a later failure, cancel, skip or process death keeps the finished values: the unfinished phases of the result get the run's final status and reason (`uploadStatus: "cancelled"`, `uploadErrorCode: "cancelled"`; a script cut after its first step keeps that step and gets `errorCode`). `phaseSummary` counts the planned phases by final status, separately from the run `status`. Runs saved by earlier versions are read unchanged.

Every phase has a monotonic deadline (latency 15 s, transfers duration + 10 s, network tests by type, every script step 20 s) and the point has a finite budget (all phases plus 15 s, `budgetMillis`, shown before the tests start). A value returned after its phase deadline is discarded as `timeout`. A phase that no longer fits in the remaining budget is not started: `skipped` with `budget_exhausted`. Name lookups wait at most 5 s and at most two can be pending. Error codes keep the causes apart: `timeout`, `cancelled` (user), `skipped_by_user`, `wifi_lost`, `budget_exhausted`, `network_changed` (4.0.0-rc.2: the Wi-Fi approved before the run was gone or replaced; the run is `skipped` and nothing was sent). `activeRuns[].networkId` (rc.2) is the handle of the Wi-Fi network the run was bound to; `observedSsid`/`networkVerification` describe that network, read just before the first traffic. On a deadline or cancel the run's sockets, HTTP connections, worker threads and ping processes are closed, so a blocked transfer ends at once.

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
| `latencyErrorCode`, `downloadErrorCode`, `uploadErrorCode` | string \| null | Cause per phase: `resolution_failed`, `connect_failed`, `connection_refused`, `timeout`, `iperf_busy`, `iperf_protocol`, `http_status`, `empty_body` (server answered with no body: never reported as 0), `response_too_large`, `skipped_unreachable`, `not_configured`, `not_on_wifi`, `wifi_lost`, `rate_limited` (HTTP 429), `proxy_auth_required` (HTTP 407)… |
| `internetServerId`, `internetServerLabel` | string \| null | RC4: the Internet destination selected (`cloudflare-auto`, a LibreSpeed id or `custom`) and its label |
| `serverLocation` | string \| null | Where the provider served from, when it says (Cloudflare data centre, e.g. `MAD`) |
| `downPartialMbps`, `upPartialMbps` | number \| null | Rate of the bytes moved before an HTTP 429: diagnosis only, never in medians or verdicts |
| `downloadRetryAt`, `uploadRetryAt` | ISO-8601 \| null | When the rate-limited server may be tried again |
| `downloadProxyConfig`, `uploadProxyConfig` | string \| null | RC4: proxy Android has configured for that URL: `none`, `manual`, `pac`, `unknown` |
| `downloadRoute`, `uploadRoute` | string \| null | Route of the measuring connection: `proxy`, `direct`, `undetermined` |
| `downloadHttpStatus`, `uploadHttpStatus` | integer \| null | Last HTTP status seen in that direction (e.g. 200, 407, 429) |
| `latencyRoute` | string \| null | `direct`: `latencyMs` is a direct TCP handshake to the origin, also when HTTP goes through a proxy |

`networkTests` (HTTP) and `scriptSteps` rows add `proxyConfig` and `route` (always `direct` once connected: that client opens its own socket and does not use the proxy).
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

### Series (mejora 9)

`alias`/`target` is only the visible name. Results are combined and compared by **series**: `seriesDownload`, `seriesUpload`, `seriesLatency` (throughput rows), `seriesId` (network tests and script runs). A series fixes the kind of test, direction, endpoint (`scheme://host:port`), method and, for scripts, the definition (`definitionHash`): the same alias pointing at another host, port or method is another series, and ICMP and TCP pings never mix. Results saved before series existed get a `legacy:` identity and `seriesComparable: false`. Join across exports by `pointId` + series, not by alias.

### Endpoints per direction (mejora 14)

Each direction is identified by `downloadEndpointId` / `uploadEndpointId` = `scheme://host:effective-port` (the same host on another port is another endpoint). When the upload endpoint differs it gets its **own pre-check** and is measured even if the download pre-check failed; `downloadPrecheckAddress` / `uploadPrecheckAddress` are the IPs the pre-checks connected to (`uploadPrecheckErrorCode` when it failed). The IP the HTTP transport itself used is not observable without changing the destination or breaking TLS, so it is not reported. Times and statuses are per direction.

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

**Redirect policy (HTTP tests and script steps).** Without `expectStatus` only 2xx succeeds; a 3xx fails with `redirect` and `redirectLocation` (host and path, no query) shows where it went, e.g. a login page. It counts as success only with `"allowRedirect": true` or when `expectStatus` is that status. Earlier versions accepted any 3xx.

## 3c. HTTP scripts (`scriptRuns`, `scriptSteps`)

Scripts from the `scripts` section (or imported from a `.har`): chained requests with variables, `${secret.NAME}` credentials, values extracted from responses (e.g. a token) and cookies kept between steps. Only GET/HEAD unless the step has `"allowWrite": true`. Each row carries the position fields. See [wiprober-config.md](wiprober-config.md) for how credentials are kept out of definitions, the screen and this file.

**Timing semantics.** Every step opens a new connection (`Connection: close`), so each phase is measured every time, on the Wi-Fi network:

| Field | Measures |
|---|---|
| `dnsMs` | Name resolution on the Wi-Fi network (an IP literal resolves in ~0 ms) |
| `connectMs` | TCP handshake to the first resolved address |
| `tlsMs` | TLS handshake and host-name verification (https only) |
| `ttfbMs` | From the end of sending the request to the first response byte |
| `downloadMs` | From the first byte to the end of the body (status line and headers included) |
| `totalMs` | Whole step, including variable resolution and checks |

A phase that was not reached is `null`. Redirects are not followed: a 3xx is the step's status. A step reads at most 50 MB (`response_too_large`), 100 response headers and keeps at most 30 cookies per host; the step deadline (20 s) bounds its time.

`scriptRuns` — one row per script per active run, **also** when it was skipped (no Wi-Fi) or stopped before its first step:

| Field | Type | Description |
|---|---|---|
| `script`, `scriptAlias` | string | Stable name and display alias |
| `status` | string | `completed`, `failed` or `skipped` |
| `stepsTotal`, `stepsRun`, `stepsOk`, `stepsFailed` | int | Steps defined / run / correct / failed |
| `totalMs` | number \| null | Sum of the steps' `totalMs` |
| `ssid`, `bssidStart`, `bssidEnd`, `rssi` | | Connected AP when the script started/ended |
| `errorCode` | string \| null | Why it was skipped (`not_on_wifi`, `wifi_lost`), else the first failed step's code |
| `error` | string \| null | Sanitized reason |

`scriptSteps` — one row per step that ran:

| Field | Type | Description |
|---|---|---|
| `script`, `scriptAlias`, `stepIndex`, `step` | | Script, step number (from 1) and name |
| `method`, `host` | | Request method and resolved host (credential values redacted) |
| `path` | string | Path **from the definition**: no query string, no extracted or credential values, token-like segments shown as `…` |
| `status` | int \| null | HTTP status |
| `ok` | bool | Status, expected text and extractions all correct |
| `ssid`, `bssidStart`, `bssidEnd`, `rssi` | | Connected AP when the script started/ended |
| `dnsMs`, `connectMs`, `tlsMs`, `ttfbMs`, `downloadMs`, `totalMs` | number \| null | Timing breakdown, see above |
| `bytes` | int \| null | Body size |
| `errorCode` | string \| null | Structured cause: `http_status`, `expectation_failed`, `extract_failed`, `unknown_variable`, `missing_credential`, `invalid_target`, `cleartext_not_permitted`, `tls_failed`, `hostname_mismatch`, `response_too_large`, `timeout`, `resolution_failed`, `connect_failed`, `write_not_allowed`, … |
| `error` | string \| null | E.g. `status 401`, `expected text not found`, `cannot extract token from $.data.token`. Never contains credential, cookie or extracted values, query strings or the expected text |

## 3c-bis. Continuous routes (`continuousSessions`)

One row per local Continuous route (with or without roaming probe): `surveyId`, `floorPlanId`, `floorName`, `startedAt`, `endedAt`, `timeBase`, `waypoints`, `scans`, `segments`, `recoveredAfterInterruption`, `interruptions` (`from`/`to` UTC and `fromMs`/`toMs` relative to `startedAt`), `roamingProbe`.

RF capture fields: `rfScanAttempts`, `rfScansSucceeded`, `rfScansFailed`, `rfScansCancelled` and `rfGaps` (`from`, `to`, `durationMs`): spans without a successful scan (longer than 3 × the median interval with ≥ 4 intervals, and at least 5 s), including interruptions. A gap means *not observed*, never *no signal*.

The capture (scanner, roaming probe, foreground service with wake and Wi-Fi locks) belongs to the survey session, not to the screen: rotating, locking the screen or switching apps neither stops nor duplicates it, and the notification returns to the open project. If the service cannot start (permission, background start) the survey shows it.

An open route is checkpointed in `drafts/<sessionId>.json` next to the floor survey: once when it starts and then at most every **5 s** while it changes (4.0.0-rc.2; before, a 1.5 s debounce could be postponed for ever by a probe sending samples every 200 ms). New samples only change what the next checkpoint writes, never when it runs, so a killed app loses at most the last 5 s of the route (plus the duration of one write). Drafts are written off the main thread; a route saved or discarded is never brought back by a late draft write. If the app is killed or the phone restarts, reopening the project offers it as an **interrupted route**: *Save* (as it is), *Continue* (a new stretch from its last turn, with its own monotonic clock anchored in UTC) or *Discard*. Stretches are joined in order, never overlapping even if the clock restarted; the gap between them is an `interruptions` entry: nothing was observed in it. Saving is idempotent (a retry never adds the route twice) and the draft is removed only once the survey holding the route is on disk.

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

**Evidence limits (mejora 12).** `tcpAttempts` counts only attempts made against a valid target (connected, refused, timeout, unreachable) and `tcpLossPct` is computed over them: DNS failures, invalid targets and attempts without Wi-Fi are reported but never in that denominator. `unobserved` lists spans without attempts (longer than 3 intervals). Every RF scan attempt (successful, failed, cancelled) is compared with each event window; `scanOverlapOutcomes` says which kinds overlapped. Events whose windows overlap (e.g. two AP hops in one cut) share an `incidentId` and their failed attempts are counted once; `roamingIncidents` lists them with bounds. `durationEvidence` says how far the observations go: `bounded` (failures seen and reachable attempts on both sides), `upper_bound_only` (change without failures: only the resolution is known), `lower_bound_only` (not recovered) or `unknown` (a gap without samples). With `"roaming": {"trace": true}` a bounded trace of up to 3 000 attempts is exported as `roamingTrace` (result, association, times; no hosts). `targetAlias` (from `roaming.alias`) and `method` (`tcp-connect`, `icmp-echo`, or `unknown` for a route recovered from a draft saved before 4.0.0-rc.2, shown as «desconocido» in the report) describe the probe. Since rc.2 every stretch of a route records the probe it really used (method, target, alias, interval); a draft written by 4.0.0-rc.1 (probe stored only route-wide) is migrated when it is loaded: its stretches with probe attempts keep the original target and interval with method `unknown`, stretches without probes stay without one (rc.3); a route resumed with another probe exports one `roamingSessions` row per probe (`stretch`), and consumers must key them by `surveyId` + `stretch`. `roamingTrace` rows add `rttMs` (the probe's own RTT, ICMP `time=`) and `durationMs` (the whole attempt).

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
| `avgRttMs` | number \| null | Mean RTT measured by the probe: the TCP handshake for `tcp-connect`; for `icmp-echo` (4.0.0-rc.2) the `time=` of the echo replies, **not** the attempt duration. `null` without a valid RTT |
| `avgAttemptMs` | number \| null | rc.2: mean full duration of the reachable attempts (for ICMP it includes starting `ping`), for cadence and overlap analysis |
| `probeErrors` | integer \| null | rc.2: attempts that could not be made or read (`probe_error`: `ping` did not run, no source address, unreadable output). A measurement problem: not in `lost`, not loss towards the target |
| `stretch`, `stretchStart`, `stretchEnd` | integer, string | rc.2: only when a resumed route used **different probes** in its stretches — one row per probe, never combined (also on its `roamingEvents`, `roamingIncidents` and `roamingTrace` rows) |
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

Data your app can derive from the `.esx` with no nimWifi change: APs on the same channel above −82 dBm (co-channel), APs above −67 dBm (redundant coverage), difference between the best and second AP, and the *BSS Load* of any AP (beacon elements are in `informationElements` of `accessPointMeasurements.json`).

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

## 8. Readable report (`wiprober-report.html`)

The report is a self-contained HTML page **in Spanish** (no scripts) for Operations (mejora 19). For each floor and SSID it shows, in order:

- a short guide that tells three things apart: signal evidence, network degradation, and a drop towards one service;
- the plan;
- the **worst points** first: state, strongest and second AP, associated AP, position, value and threshold;
- private iPerf3, Internet and internal services in **separate tables**, by alias, with measure, unit, method, samples, failures and outcomes;
- **roaming and outages**: time in UTC with milliseconds, the observed window (last reachable → first reachable), the association before and after, the incident, and the approximate position on the route when there is one. Events in the same second keep their order.

The SSIDs reported are the map SSID, the SSIDs used by the tests and the three most widely detected (at most five).

Each SSID section tells the **bands** apart (4.0.0-rc.5): a per-band summary (good, weak, not heard over the measured points), a *Por banda* column in the worst points, band and channel next to every AP, and *cambio de banda* on AP changes between bands. Each floor ends with a collapsed **AP inventory**: every radio with band, best signal, the user's AP name and the inferred vendor/model with its source.

With **requirements** configured, the report starts with a **verdict** per floor and SSID: meets / does not meet / missing data, points meeting over points with valid data (explicit denominator) and how many lack data, and the floor checks; each SSID section then lists what fails (point, requirement, value, threshold, `runId`, detail), the points without enough data and why, and the outages of the floor. The requirements applied are listed. With an **AP table**, AP names are shown next to the BSSIDs (worst points, associated AP, roaming, verdict). Without either, the report is as before.

Each floor shows its **plan per SSID** (mejora 8): Stop-and-Go points at their exact position, Continuous scans as small dots placed on the route by time (approximate; only where two waypoints surround the scan time — the others are counted as *without enough route geometry*, never drawn), planned points and imported points without local RF as dashed rings. Weak points show their value and the threshold. Areas without marks have no sample. A source line counts each kind of evidence, and Continuous scans add their weak and not-detected counts and the worst value to the SSID figures.

A self-contained HTML page (no scripts) for non-specialists, one section per floor and SSID:

- **SSIDs reported:** the SSID chosen on the map (*Map SSID…*), then the SSIDs the phone was associated with during active tests; without either, the three most widely detected. Each SSID is judged on its own: a strong guest SSID never makes an operational SSID look covered.
- **Primary coverage and redundancy (mejora 11):** per SSID, the strongest AP of the latest valid scan decides the state (profile primary threshold); the second strongest *distinct* BSSID of the same SSID, at or above the secondary threshold, is reported as a redundancy hint for roaming (ring around the marker on the map), never as proof of how the client roams. The RSSI of the AP the phone was associated with during the tests and the AP changes observed there are listed next to it. The survey guide counts only local RF as measured: valid Stop-and-Go scans and Continuous scans placed on their route — not route waypoints alone, planned points or imported geometry.
- **Point states:** *Good signal*; *Weak signal measured* (strongest AP of the SSID below −70 dBm in the latest valid scan); *SSID not detected in a valid scan* (the scan saw other networks); *No local measurement* (planned points, points imported from an ESX — their Ekahau data stays opaque — and scans that returned nothing). The signal is the strongest AP of the SSID in the passive scan, not necessarily the AP the phone was connected to during tests.
- **Services:** private iPerf3 server, Internet and internal services (network tests, scripts) listed separately by series (see 9), with measure, median and range, unit, method, samples and failures.
- **Selection (mejora 1):** runs are first split by SSID (of their results, or of the network observed before a run that produced none) and by series; only then is a value chosen per point. Each point keeps two facts: its **last attempt** (with status, e.g. a cancelled run without values) and its **last valid measurement**, each with its date and `runId` (the key of `activeRuns` in the JSON). By default the figures use the last valid value per point; *all runs*, one run (`runId`) or an interval (campaign) can be selected. A pass on another SSID never replaces this SSID's values, and the denominators stay within the SSID.
- **States and denominators (mejora 10):** each series lists its attempts by outcome — `valid`, `partial` (e.g. latency with lost samples, a script with some steps done), `failed`, `skipped`, `cancelled`, `interrupted`, `not_configured` (e.g. no upload URL). *Samples* are points with a valid value; *Outcomes* count one attempt per point (its last) unless all runs are selected. Scripts are checked against their planned steps (`stepsPending` in `scriptRuns`, status `partial`). Results recorded before the SSID was identified go to an **Unknown association** section with their reasons, never into an SSID.
- **AP changes and outages:** association changes during active tests and Continuous-track roaming events, with their bounds.

## 7. History

| Release | Changes to this file |
|---|---|
| `v3.0.0-throughput.1` | Format 1: ZIP with `.esx` + JSON; position, `surveyId`, `target`, `method`, `downMbps`, `upMbps`, `latencyMs`, `ssid`, `bssidStart`/`bssidEnd`, `rssi`, `linkMbps`, `error` |
| `v3.0.0-throughput.2` | Added `rxLinkMbps`, `frequencyMhz`, `channel`, `channelWidthMhz`, `wifiStandard`, `bssStationCount`, `bssChannelUtilizationPct`, `latencyAvgMs`, `jitterMs`, `latencySamples`, `deviceModel`, `androidVersion`. Latency from 10 samples |
| `v3.0.0-throughput.3` | No export changes (survey guide in the app) |
| `v3.0.0-throughput.4` | Added `networkTests` and `scriptSteps` (imported configuration) and `roamingSessions`/`roamingEvents` (Continuous-mode roaming) |
| next (format 2) | Rows per active run with `pointId`/`scanId`/`runId`, `activeRuns` with run and phase statuses, per-phase status and error codes, bytes and real duration, `latencyFailures`/`latencyMethod`, `rttKind`; `wiprober-report.html`; runs on imported/planned points (`scanId: null`, position of the point); `scriptRuns` and `scriptAlias`; script paths from the definition; roaming schema 2 (typed attempt results, bounded outages, association-change types, monotonic time base, grounded approximate positions); measured-network and association fields (`networkId`, `associationSource`, `ssidEnd`, `associationChange`, `resolvedAddress`, endpoints, `targetId`, `category`, aliases) |
