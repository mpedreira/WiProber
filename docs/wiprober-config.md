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

## Survey guide and signal profile

`guide.profile` sets when a point's signal is weak on the map and in the report: `pda` (handheld scanners, −67 dBm, second AP −72 dBm, default), `voz` (voice, −67/−67) or `datos` (data, −70/−75). The same profiles as the wifianalisys report; they can also be chosen in the survey-guide settings.

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

## Redirects

HTTP tests and script steps succeed on 2xx. A 3xx is reported as `redirect` (with where it went) unless the test or step sets `"allowRedirect": true` or `expectStatus` to that code — so a redirect to a login page is never counted as a working service by accident.

## Requests at run time

- Every connection is bound to the Wi-Fi network; without Wi-Fi nothing is sent and each script is exported as `skipped` (`not_on_wifi`).
- After variables and credentials are resolved, the URL must still be `http`/`https` with a host and no user info, and header values must not contain line breaks (`invalid_target` otherwise).
- **Cleartext:** `http://` follows Android's network security policy, for HTTP tests and for the script client alike. The app keeps the platform default (cleartext blocked on Android 9+), so `http://` steps fail with `cleartext_not_permitted` and send nothing; use `https://`.
- **TLS:** certificates are validated against the system CAs and user/MDM-installed CAs (corporate CAs), and the host name must match the certificate (`hostname_mismatch`, `tls_failed`).
- Limits: 50 MB per response, 100 response headers, 30 cookies per host, no redirects followed, 20 s per step; see [wiprober-performance.md](wiprober-performance.md#3c-http-scripts-scriptruns-scriptsteps) for timing semantics.
