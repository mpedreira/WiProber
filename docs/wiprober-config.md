# nimWifi configuration (`wiprober-config.json`, `.har`, configuration ZIP)

Servers, credentials, tests and scripts are never in the code. They are set on the phone (⋮ → *Imported configuration*: roaming probe, network tests, scripts and credentials can be edited there) or imported from a `wiprober-config.json` ([example](wiprober-config.example.json)), a browser `.har` recording or a **configuration ZIP** ([commented example](examples/nimwifi-config-example.zip), sources in [examples/config-zip](examples/config-zip)), and kept in app-private storage (`files/private-config/`) that is excluded from Android backups and device transfers.

## Which Wi-Fi the active tests use

`throughput.expectedSsid` (also in *Throughput tests* on the phone) declares the SSID the active tests must run on. It is not the map SSID (*Map SSID (passive coverage)*, a filter of the coverage map). Before sending anything nimWifi selects the Wi-Fi network (the eligible one that reports the expected SSID when there are several; otherwise the only one, the default, a validated one or the lowest handle — never mobile data, Internet is not required) and compares its SSID with the expected one:

| `networkVerification` | Meaning | Before testing |
|---|---|---|
| `matched` | The network reports the expected SSID | Tests run |
| `mismatch` | Another SSID | Decide: test anyway (`networkDecision: "confirmed"`) or skip |
| `unknown` | Android does not say which SSID it is | Decide, as above |
| `not_declared` | No expected SSID | Tests run |
| `no_wifi` | No Wi-Fi network | Nothing is sent |

DNS, sockets and HTTP of the run stay bound to the selected network. The check is saved with the run (`expectedSsid`, `observedSsid`, `networkVerification`, `networkDecision`).

Since 4.0.0-rc.2 the run is bound to the **network approved in that check** (its Android handle), not to a new selection. Just before any traffic its identity is checked again: if it is gone, has been replaced by another network, or now reports another SSID, nothing is sent, the run ends `skipped` with `network_changed` and the phone asks to check the network and decide again. Since rc.3 no other network is ever chosen at the start: if the check found no Wi-Fi (or could not run) and a Wi-Fi is there when the run starts, that Wi-Fi has not been checked, so nothing is sent (`network_changed`, `unchecked`) and it goes through the check and decision first; if there is still no Wi-Fi, the run sends nothing and every target records `not on Wi-Fi`, keeping the point. A BSSID change inside the same SSID and network is an AP change and is measured and recorded (`associationChange`). If the network is lost during the run, finished values are kept and the failed phases carry `wifi_lost`; the run never moves to another Wi-Fi or to mobile data. `observedSsid`, `networkVerification` and the new `networkId` of the run describe the network the run was really bound to; `networkDecision` keeps the technician's decision.

## Internet server (public speed test)

*Pruebas de velocidad* has one drop-down **Servidor de Internet** (`throughput.internet.server` in the configuration):

| id | Destination | Load |
|---|---|---|
| `cloudflare-auto` | Cloudflare · ubicación automática (its anycast network picks the data centre; the one observed, from `CF-RAY`, is reported as `serverLocation`) | 4 connections |
| `librespeed-ams-clouvider` | LibreSpeed · Ámsterdam · Clouvider | 2 connections, 10 MiB downloads, 4 MB uploads |
| `librespeed-fra-clouvider` | LibreSpeed · Fráncfort · Clouvider | idem |
| `librespeed-lon-clouvider` | LibreSpeed · Londres · Clouvider | idem |
| `librespeed-rom-garr` | LibreSpeed · Roma · GARR | idem |
| `custom` | Personalizado: your download URL (`{bytes}` is replaced with the chunk size) and upload URL (POST) | 4 connections |

The LibreSpeed entries come from the official list (`server-list.json` of github.com/librespeed/speedtest) with the paths each publishes (`garbage.php`, `empty.php`), checked for download and upload over HTTPS. LibreSpeed publishes **no usage conditions for third-party clients**: they are servers lent by their operators, so use them for chosen control points and *Probar servidor*, not continuously. The catalogue is `InternetServers.kt`.

*Internet público* measures download and upload up to that external provider; keep the private iPerf3 target for the site's wired server. The selection is saved by id; the custom URLs are kept while another destination is selected and come back with *Personalizado*. Settings and profiles from before keep their URLs and enabled state: URLs that exactly match a catalogue entry select it, anything else is *Personalizado*. Changing the drop-down sends nothing.

**HTTP 429** (*the server is limiting requests*): new requests to that destination stop at once; bytes other connections moved before are kept as `downPartialMbps`/`upPartialMbps` for diagnosis only — never a valid value, median or verdict input — and with no bytes the phase has no value. The phase is `failed` with `rate_limited`. nimWifi does not ask that server (host and port) again before its `Retry-After` (seconds or an HTTP date; 60 s without it): later phases and runs to it are `skipped` with `rate_limited` and `downloadRetryAt`/`uploadRetryAt`, other destinations go on, and the series keeps the same server — switching it is your decision. HTTP 407 (proxy authentication) is `proxy_auth_required`, a different cause.

## Survey guide and signal profile

`guide.profile` sets when a point's signal is weak on the map and in the report: `pda` (handheld scanners, −67 dBm, second AP −72 dBm, default), `voz` (voice, −67/−67) or `datos` (data, −70/−75). The same profiles as the wifianalisys report; they can also be chosen in the survey-guide settings.

**Primary network (SSID and band).** `guide.ssid` and `guide.band` (`2.4`, `5` or `6`; empty or absent = every band) choose the network the map is coloured by; on the phone, survey menu → *Primary network (SSID and band)…*, which lists each SSID in every band and in each band it was heard in on the floor. The coverage gradient and the point colours (good / weak / not detected) then use only that SSID's APs in that band: a point covered on 2.4 GHz but not on 5 GHz shows as not covered for *SSID · 5 GHz*. Another SSID or band never fills the gaps. A band without an SSID is ignored; *Automatic* keeps the most widely detected SSID in every band. The report still covers every SSID, with its per-band breakdown.

## Configuration ZIP and comments

A configuration ZIP holds `wiprober-config.json` and, optionally, `.har` recordings: each one becomes one more script (with the HAR rules below). Other files (a README) are ignored. In any imported JSON, keys starting with `_` are comments (`"_comment": "…"`), also inside tests, steps, headers and credentials. Opening a configuration file or ZIP from a file manager imports it with the same preview.

*Export* on the configuration screen writes the current configuration as an importable `wiprober-config.json`: without credential values, listing the credential names the scripts need under `_credentialsNeeded`.

## Import flow

1. The file is parsed and validated. Nothing is saved yet.
2. A **preview** lists every destination (scheme, host, port and methods), the number of tests and scripts, the requests that may write, what was stripped from the file, and the credential names provided or still missing. Cleartext `http://` destinations are flagged.
3. **Activate** saves it. A profile with write requests (`allowWrite`) needs a second, explicit confirmation that lists those steps.

A file that fails to parse or validate is rejected with its reason and **the previous configuration is kept**.

Activation is transactional (mejora 16): the resulting profile — after merging, e.g. a HAR added to the existing scripts — is validated as a whole (number of scripts and tests, unique names, steps, headers, write permissions, credentials, size) and written as a complete revision (`private-config/revisions/<id>/` with definitions and credentials) before one atomic switch of `private-config/active` makes it the profile in use. A failure at any point keeps the previous revision, never a mix of new definitions and old credentials. A missing profile and an unreadable one are told apart: an unreadable profile is not used, the configuration screen says so, and it must be removed before importing. Profiles stored by earlier versions are read and move to revisions on the first change. All profile I/O runs off the main thread and the screen shows the real error of a failed change.

## Credentials

Credentials live in a separate `credentials.json`, never in the definitions, and are never shown, exported or backed up. Definitions reference them as `${secret.NAME}`:

```json
{
  "format": "wiprober-config", "version": 1,
  "credentials": { "password": "change-me" },
  "scripts": [{ "name": "item-lookup", "steps": [{
    "name": "login", "method": "POST", "allowWrite": true,
    "url": "https://app.example.com/api/login",
    "body": "{\"password\":\"${secret.password}\"}" }] }]
}
```

- `credentials` is merged by name with the stored ones, so a file holding only `credentials` can complete a HAR or a shared profile. *Remove* deletes definitions and credentials.
- Literal values of credential headers (`Authorization`, `Proxy-Authorization`, `X-API-Key`, …) and script variables with sensitive names (`password`, `token`, `secret`, `session`, `auth`, `apikey`, …) are moved to the credential store on import; the preview lists them by name.
- A step that references a missing credential fails with `missing_credential` and sends nothing.
- URLs must not contain `user:password@`.
- Credential, cookie and extracted values are redacted from hosts and errors; exported paths come from the definition, without the query string and with token-like segments shown as `…`.

## Operational profile and shareable summary (mejora 17)

- The **operational profile** (what the phone uses, and *Export* on the configuration screen) keeps definitions with `${secret.NAME}` references: besides script headers and variables, sensitive query parameters of HTTP test URLs and of the Internet speed-test URLs are moved to the credentials on import. Credential values are never exported.
- *Export shareable summary* writes `nimwifi-config-summary.json` to share in a ticket or with a colleague: aliases, test types, methods, counts and endpoints with hosts replaced by `host-N` placeholders; no paths, query strings, header values, bodies, cookies, expected texts or credential values. It cannot be imported.
- Errors and traces are redacted the same way everywhere: URL credentials, query strings, `Authorization`/token-like values and every known credential, cookie or extracted value become `…`.
- **Replay cookies** use `java.net.CookieManager` per script run: a cookie is only sent back to the domain and path it was set for, `Secure` cookies only over HTTPS, at most 30 cookies of up to 4 KB. TLS keeps certificate and host-name verification.

## HAR recordings

Only GET/HEAD requests of documents, XHR and fetch are kept (at most 50); request bodies and responses are never imported. By default:

| In the recording | On import |
|---|---|
| `Cookie`, cookies, `X-CSRF-Token`, other session headers | Dropped (the replay keeps its own cookie jar) |
| `Authorization`, `X-API-Key` and other credential headers | Replaced by `${secret.har_<header>}` |
| Sensitive query parameters (`access_token`, `key`, `code`, `sig`, `session`, …) | Value replaced by `${secret.har_<param>}` |
| `user:password@` in the URL, `#fragment` | Removed |

The preview lists what was stripped and which credentials must be provided for those steps to run.

## Watching a service during Continuous routes

`roaming` probes a target during the whole route, independently of the per-point iPerf3 and speed tests (which stay as they are):

- `"method": "tcp"` (default): a TCP connect to `host:port` every `intervalMs` (from 100 ms).
- `"method": "icmp"`: one ping (ICMP echo from the Wi-Fi address) to `host` every `intervalMs` (from 500 ms); the port is not used. Useful to watch an internal server or the gateway without opening connections to it.

`alias` names the target in the report, `trace: true` keeps a bounded log of every attempt. Results are `replied` (ICMP) or `connected`/`refused` (TCP), `timeout`, `unreachable`, `resolution_failed`, `invalid_target` or `no_wifi`; the export says which `method` was used.

## Report requirements (verdict)

`requirements` (optional) turns the report into a decision: for each SSID and floor, whether it meets the thresholds and which points fail. **Every value is yours: there is no default.** Without the section (or with it empty) the report and the export are exactly as before.

```json
"requirements": {
  "throughput":   [{ "alias": "corporate", "minDownMbps": 20, "minUpMbps": 10, "maxLatencyMedianMs": 30, "maxLatencyP95Ms": 80, "maxLatencyLossPct": 5 }],
  "networkTests": [{ "alias": "gateway", "maxMedianMs": 20, "maxLossPct": 2 }, { "alias": "app-web", "maxMedianMs": 300, "maxP95Ms": 800 }],
  "roaming":      { "maxOutageMs": 500, "maxLossesPerFloor": 3 }
}
```

| Field | Applies to |
|---|---|
| `throughput[].alias` | A private iPerf3 target by its label (`throughput.iperf.label`); Internet tests are not judged |
| `minDownMbps`, `minUpMbps` | The point's last valid download/upload |
| `maxLatencyMedianMs` | The point's TCP-connect latency (the median of its samples) |
| `maxLatencyP95Ms` | 95th percentile (nearest rank, no interpolation) of the points' latencies of the SSID on the floor |
| `maxLatencyLossPct` | Failed handshakes over latency samples at the point |
| `networkTests[].alias` | A network test by alias (or name without alias); `maxMedianMs`: its time at the point (DNS lookup, ping average RTT, TCP connect, HTTP total), `maxP95Ms` over the floor, `maxLossPct`: ping loss |
| `roaming.maxOutageMs` | Upper bound of each observed outage window of the SSID on the floor (an open outage fails; one whose duration cannot be bounded is *missing data*) |
| `roaming.maxLossesPerFloor` | Reachability/association losses of the SSID on the floor |
| `bands[]` | `{"band": "5", "minRssiDbm": -67}` (bands `2.4`, `5`, `6`; one entry per band; −120..0 dBm): the SSID's strongest AP **in that band** at the point must reach it, on top of the profile threshold. A valid scan that did not hear the SSID in that band does not meet it; a point without a valid scan lacks data |

Signal uses the **signal profile's threshold** (`guide.profile`), the same as the map. Limits are inclusive (a value equal to the threshold meets it). Aliases are unique; numbers must be ≥ 0 and percentages ≤ 100, otherwise the import is refused with the reason.

The verdict has three states per point and per SSID/floor: **meets**, **does not meet**, **missing data**. A point has *missing data* when a requirement has no valid measurement there (planned point, imported point without local RF, a run skipped, cancelled or without value): `null` is never `0` nor a network failure. Any failure decides; otherwise any missing datum; otherwise it meets. A loss of reachability inside a point's measurement window makes the point fail even when its median is good; an AP change without loss is only explained. Each SSID is judged on its own.

## AP names (BSSID → AP)

`apNames` (optional) names the physical APs in the report and the export:

```json
"apNames": [
  { "bssid": "02:00:00:00:30:04", "name": "AP-P3-04", "location": "Pasillo 3", "floor": "Baja" },
  { "bssid": "02:00:00:00:31:04", "name": "AP-P3-04" }
]
```

or, in a configuration ZIP, `ap-names.csv` with the header `bssid,name,location,floor` (`;` also works; location and floor optional). Both form one table. BSSIDs are normalized (`AA-BB-…`, `aa:bb:…` or `aabb…` → `aa:bb:cc:dd:ee:ff`); a malformed or repeated BSSID refuses the import. Nothing is ever grouped by prefix, OUI or similarity: each BSSID stays a separate measured AP, and an unmapped BSSID is shown as it is. Giving several BSSIDs (the radios of one AP) the same name is your explicit decision, used only for display.

## Redirects

HTTP tests and script steps succeed on 2xx. A 3xx is reported as `redirect` (with where it went) unless the test or step sets `"allowRedirect": true` or `expectStatus` to that code — so a redirect to a login page is never counted as a working service by accident.

## Requests at run time

- Every connection is bound to the Wi-Fi network; without Wi-Fi nothing is sent and each script is exported as `skipped` (`not_on_wifi`).
- After variables and credentials are resolved, the URL must still be `http`/`https` with a host and no user info, and header values must not contain line breaks (`invalid_target` otherwise).
- **Cleartext:** `http://` follows Android's network security policy, for HTTP tests and for the script client alike. The app keeps the platform default (cleartext blocked on Android 9+), so `http://` steps fail with `cleartext_not_permitted` and send nothing; use `https://`.
- **TLS:** certificates are validated against the system CAs and user/MDM-installed CAs (corporate CAs), and the host name must match the certificate (`hostname_mismatch`, `tls_failed`).
- Limits: 50 MB per response, 100 response headers, 30 cookies per host, no redirects followed, 20 s per step; see [wiprober-performance.md](wiprober-performance.md#3c-http-scripts-scriptruns-scriptsteps) for timing semantics.
