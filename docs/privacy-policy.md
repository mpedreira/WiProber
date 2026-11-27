# nimWifi privacy policy

Revision date: 27 November 2026.

nimWifi is a personal, open-source project by [mpedreira](https://github.com/mpedreira), based on [WiProber](https://github.com/htechno/WiProber) by htechno (MIT License). It has no accounts, advertising, analytics or developer server: nothing is collected by the author.

## Data on the device

- **Surveys.** The app processes Wi-Fi network names and identifiers (SSID, BSSID), signal level, frequencies, security capabilities and other information Android provides. Projects store measurement times, the points and routes you mark on the floor plan, floor plans, notes, selected photos, imported ESX files and test results. They stay in the app's private storage until you delete them or clear the app's data.
- **Location permission.** Android requires the location permission to return Wi-Fi scan results. nimWifi never reads GPS coordinates: positions are the ones you set on the plan.
- **Imported configuration.** Server names, URLs, tests, scripts and credentials are stored in private storage and excluded from Android backups and device transfers. Credentials are kept in a separate file, never shown and never exported; credentials in a `.har` recording are removed on import.
- **Backups.** Projects (not the configuration) may be included in Android's backup, depending on your device settings.

## Network traffic

Nothing is sent unless you enable a test or import a configuration. Then the app connects over the Wi-Fi network only to the servers you configured (iPerf3 server, DNS/ping/TCP/HTTP targets, scripted requests, roaming probe target) and, if you enable the Internet speed test with the default URL, to `speed.cloudflare.com`. Those servers receive the phone's IP address and the test traffic; their operators' policies apply.

## Exports

Exports are written only where you choose. They include the survey data, the associated access point of each test, the phone model and Android version, timings and the tested hosts and paths (without query strings or credential values).

## Contact

Questions: open an issue at https://github.com/mpedreira/nimwifi/issues. The original WiProber policy applies to WiProber, not to nimWifi.
