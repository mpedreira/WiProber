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

### Interrupted restores (4.0.0-rc.2)

Every restore works in its own `projects/.restore-<n>/` directory with a `journal.json`
(`prepared` → `parking` → `installed`), the validated copy in `staged/` and, when replacing, the
original in `original/`. The original is moved there only after the copy is validated, and
removed only after the copy is in place **and** opens. If Android kills the app midway, the next
time projects are listed or opened the restore is recovered from the persisted phase and the
files present:

- before the original moved: the copy is discarded, the original stays;
- original moved, copy not yet in place: the validated copy is installed;
- copy in place: it is opened; if it opens the original goes, otherwise the original is put back.

Recovery is idempotent. `.deleting-*` directories belong only to explicit project deletion and
are never used by a restore.
