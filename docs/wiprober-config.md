# Imported configuration (`wiprober-config.json`, `.har`)

Servers, credentials, tests and scripts are never in the code. They are imported on the phone (⚙ menu → *Imported configuration* → *Import file…*) from a `wiprober-config.json` ([example](wiprober-config.example.json)) or a browser `.har` recording, and kept in app-private storage (`files/private-config/`) that is excluded from Android backups and device transfers.

## Import flow

1. The file is parsed and validated. Nothing is saved yet.
2. A **preview** lists every destination (scheme, host, port and methods), the number of tests and scripts, the requests that may write, what was stripped from the file, and the credential names provided or still missing. Cleartext `http://` destinations are flagged.
3. **Activate** saves it. A profile with write requests (`allowWrite`) needs a second, explicit confirmation that lists those steps.

A file that fails to parse or validate is rejected with its reason and **the previous configuration is kept**; every stored file is replaced atomically.

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

## HAR recordings

Only GET/HEAD requests of documents, XHR and fetch are kept (at most 50); request bodies and responses are never imported. By default:

| In the recording | On import |
|---|---|
| `Cookie`, cookies, `X-CSRF-Token`, other session headers | Dropped (the replay keeps its own cookie jar) |
| `Authorization`, `X-API-Key` and other credential headers | Replaced by `${secret.har_<header>}` |
| Sensitive query parameters (`access_token`, `key`, `code`, `sig`, `session`, …) | Value replaced by `${secret.har_<param>}` |
| `user:password@` in the URL, `#fragment` | Removed |

The preview lists what was stripped and which credentials must be provided for those steps to run.

## Requests at run time

- Every connection is bound to the Wi-Fi network; without Wi-Fi nothing is sent and each script is exported as `skipped` (`not_on_wifi`).
- After variables and credentials are resolved, the URL must still be `http`/`https` with a host and no user info, and header values must not contain line breaks (`invalid_target` otherwise).
- **Cleartext:** `http://` follows Android's network security policy, for HTTP tests and for the script client alike. The app keeps the platform default (cleartext blocked on Android 9+), so `http://` steps fail with `cleartext_not_permitted` and send nothing; use `https://`.
- **TLS:** certificates are validated against the system CAs and user/MDM-installed CAs (corporate CAs), and the host name must match the certificate (`hostname_mismatch`, `tls_failed`).
- Limits: 50 MB per response, 100 response headers, 30 cookies per host, no redirects followed, 20 s per step; see [wiprober-performance.md](wiprober-performance.md#3c-http-scripts-scriptruns-scriptsteps) for timing semantics.
