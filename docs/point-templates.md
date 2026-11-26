# Point templates and reusing points

## Measuring again at an existing point

In Stop-and-Go, tap an existing point — measured locally, imported from an ESX, or planned from a template — to choose:

- **Measure at this point (scan + tests):** a new passive scan at exactly the point's coordinates, followed by the configured active tests. It keeps the point's `pointId`; the original scan (or the imported ESX survey) is left untouched.
- **Active tests only at this point:** the active tests without a new scan (`scanId: null` in the export).
- **New point here:** a new point at the tap position.

Undo removes only the latest local action (scan, run or template import). Coordinates stay in original floor-plan pixels, so new measurements line up with imported geometry.

## Importing a point template

*⋮ → Import point template…* adds planned points to the current floor. They are drawn as hollow rings and reported as *No local measurement* until measured. A preview shows how many points will be added, the scale applied and how many were ignored, before anything is added.

JSON:

```json
{
  "format": "wiprober-points", "version": 1,
  "image": { "width": 2000, "height": 1600 },
  "points": [ { "id": "A1", "label": "Dock 1", "x": 200, "y": 160 } ]
}
```

CSV (`,` or `;`, an `id` column is optional; the first line may give the plan size):

```
# image 2000x1600
id,label,x,y
A1,Dock 1,200,160
```

- `x`/`y` are pixels of the plan the template was made for (`image`; CSV without it: the current plan). If that plan has a different size but the same proportions (±1 %), points are scaled; different proportions are rejected, because the template belongs to another drawing.
- Points outside the plan are ignored. Ids are derived from the floor and the template `id`, so importing the same template again adds nothing twice.
- Planned points are saved with the floor; they are not written to the ESX.

## NetSpot compatibility: blocked

Importing a NetSpot project or export directly is **not implemented**. No real NetSpot export file was available to verify its format, and guessing it would risk misplacing points. Until a real sample (with its floor plan) is available, export the point list from NetSpot (or any tool) and convert it to the template format above, checking the plan size so the points line up.
