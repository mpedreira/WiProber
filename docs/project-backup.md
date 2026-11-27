# Project copy (backup and restore)

nimWifi has two different files that leave the phone:

| File | What it is | What opening it does |
|---|---|---|
| **Project copy** (`*.nimwifi-backup.zip`) | The whole local project, restorable | Restores the project with its IDs |
| **Analysis ZIP** (ESX + `wiprober-performance.json` + report) | A deliverable for analysis | **Import ESX only**: the ESX becomes a new project; results are not restored |

## Making a copy

Project hub → three-dot menu of a project → **Back up project**. The copy is built and verified
in app storage first, then written to the chosen destination (see the export notes on providers
without atomic replace).

## Format `nimwifi-project-backup`, version 1

A ZIP whose **first entry** is `nimwifi-backup.json`:

```json
{
  "format": "nimwifi-project-backup",
  "version": 1,
  "projectId": "…",
  "title": "…",
  "createdAt": 1700000000000,
  "appVersion": "4.0.0",
  "files": [{ "path": "manifest.json", "size": 1234, "sha256": "…" }]
}
```

followed by the project workspace, byte for byte, under `project/`: `manifest.json`, every
floor (`floors/<id>/map/…`, `survey.json` with scale, scans, routes, notes, planned points, active
runs and skipped cells, `media/` note photos, `drafts/` open routes) and `base.esx` when the
project was imported from an ESX. IDs, pixel coordinates and units are those of the original.

Not included: the configuration profile, credentials, throughput and guide settings (they live
outside the project, per device), and temporary files.

## Restoring

Open the copy with **Open ESX or project copy** (or from another app). The copy is extracted to
staging and checked: format and version, safe paths, every listed file present, nothing unlisted,
sizes and SHA-256 equal, the project manifest's ID equal to the copy's. The repository then opens
the staged project (manifest, every floor, `base.esx`). Only after all of this is it moved into
place; a failure leaves the phone's projects untouched.

If a project with the same ID already exists, nothing is changed until the user decides:

- **Restore as a new project**: the copy gets a new project ID (everything else is kept);
- **Replace existing**: the existing project is replaced by the copy;
- **Cancel**.

A newer format version than the app supports is refused rather than guessed.
