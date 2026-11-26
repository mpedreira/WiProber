# WiProber Privacy Policy

## Notice for the mpedreira/WiProber fork

This repository is a fork of [htechno/WiProber](https://github.com/htechno/WiProber). The policy below is an English translation of the original author's policy (the original Russian text is in the upstream repository). The fork changes the following:

- **Network access.** The fork requests the `INTERNET` and `ACCESS_NETWORK_STATE` permissions for optional tests. Nothing is sent unless the user enables a test or imports a configuration. Then the app connects, over the Wi-Fi network, only to the servers the user configured (iPerf3 server, HTTP/DNS/TCP test targets, scripted requests, roaming probe target) and, for the default Internet speed test, to `speed.cloudflare.com` (Cloudflare). Those servers receive the device's IP address and the test traffic; their operators' own policies apply.
- **Imported configuration.** Server names, URLs and credentials in an imported configuration or `.har` file are stored in the app's private storage on the device and are excluded from Android backups and device transfers. Credentials are kept in a separate file, never shown on screen and never written to exports; credentials found in a `.har` recording are removed on import.
- **Additional data in projects and exports.** Test results stored in projects and exported to `wiprober-performance.json` include the associated access point (SSID, BSSID, signal, link rates, channel), the phone model and Android version, timings and the hosts/paths tested (without query strings).
- The fork does not add advertising, accounts or analytics, and does not send projects to any developer server.
- Questions about the fork: open an issue at https://github.com/mpedreira/WiProber/issues.

## Original policy (translated)

Revision date: 12 October 2026.

The developer and the person responsible for processing data in WiProber: Pavel Semenishchev. Contact: wifi.solution.msk@gmail.com.

WiProber is intended for Wi-Fi surveys. The application processes network names and identifiers (SSID and BSSID), signal level, frequencies, security characteristics and other information provided by Android. Projects store measurement times, points and routes marked by the user on the plan, floor plans, text notes, selected photos and imported ESX files. On export, the file also includes information about the device model and Wi-Fi adapter. This data is used to display networks, conduct the survey and export the results.

Precise and approximate location permissions are required to access Wi-Fi scan results under Android rules. WiProber does not request GPS coordinates: the position on the plan is set by the user. Without the permissions, scanning functions are unavailable. Permissions can be revoked in Android settings.

Projects are stored in the application's private storage on the device until the user deletes them or clears the application data. The application contains no advertising, purchases or analytics services and does not send projects to the developer's server.

Export is performed by user action to a location the user chooses. The system file picker may include cloud storage. Android system backup is allowed in the application; depending on the device and settings, data may be included in a backup or transferred to another device. These operations are governed by the settings and terms of the respective provider.

To delete a project, use the project menu on the home screen. Clearing the application data in Android settings deletes WiProber's local data. Exported files, original photos and system backups are deleted separately using the tools of the respective storage. The developer has no remote access to local projects.

If you contact the developer by email, you share the sender's address and the content of the message in order to receive a reply. Do not send plans, photos and survey results that you do not want to share with the developer. For questions about the processing or deletion of data shared with the developer, contact wifi.solution.msk@gmail.com.

The updated revision of the policy is published together with the application and at the link on its store page.
