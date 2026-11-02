# Speaker Specs Profiles (Spike)

Copy `.json` files into:

```text
<game>/cinema/speaker_specs/profiles/
```

or drag them onto the **Speaker Directivity (Spike)** screen. Right-click a
speaker block with an empty hand to open that speaker's panel. Role selection is
also in that panel; `/cinema_directivity` opens the type-default editor (debug
entry).

## Simple profile

```json
{
  "id": "my-sub-cardioid",
  "name": "My Sub Cardioid",
  "type": "subwoofer",
  "pattern": "cardioid",
  "frequencyRange": [20, 200],
  "source": "local test",
  "license": "CC0"
}
```

`pattern` can be `omni`, `cardioid`, or `unmeasured`. The built-in subwoofer
profiles already provide `sub-omni` and `sub-cardioid`.

> This importer expects the normalized `speaker_specs` schema below, not the
> raw Spinorama Plotly JSON. A1d will add a converter for the API/static JSON
> (`SPL Horizontal`, `SPL Vertical`, `On Axis`) into this format.

## Measured profile

A measured profile contains the three Spinorama datasets:

1. **on-axis response** (`onAxis`): absolute SPL per frequency;
2. **horizontal directivity** (`horizontal`): SPL rows per horizontal angle;
3. **vertical directivity** (`vertical`): SPL rows per vertical angle.

`onAxis`, `horizontal` and `vertical` must use the same SPL scale. The parser
subtracts the on-axis response from each H/V row to obtain relative directivity.

```json
{
  "id": "my-speaker",
  "name": "My Speaker",
  "type": "floorstanding",
  "pattern": "measured",
  "frequencyRange": [40, 20000],
  "source": "Spinorama / measurement source",
  "license": "CC BY-NC-SA 4.0",
  "quality": "high",
  "frequencies": [125, 250, 500, 1000, 2000, 4000, 8000, 16000],
  "onAxis": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5],
  "horizontal": [
    {"angle": 0, "spl": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5]},
    {"angle": 30, "spl": [87.5, 87.6, 87.3, 87.0, 86.4, 85.3, 83.7, 81.0]},
    {"angle": 90, "spl": [85.0, 84.7, 84.1, 83.0, 80.8, 78.5, 74.9, 70.5]},
    {"angle": 180, "spl": [78.0, 76.2, 74.1, 72.0, 69.8, 67.5, 62.9, 57.5]}
  ],
  "vertical": [
    {"angle": 0, "spl": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5]},
    {"angle": 30, "spl": [87.5, 87.4, 86.9, 86.2, 85.3, 84.0, 81.9, 78.5]},
    {"angle": 90, "spl": [82.0, 81.2, 80.1, 78.0, 75.8, 73.5, 70.9, 67.5]}
  ]
}
```

- `frequencies` must be positive and have the same length as `onAxis` and each
  row's `spl`.
- Rows are sorted by angle; the parser mirrors half-plane tables if needed.
- Horizontal and vertical tables are combined as a separable approximation for
  the spike; this is not a full 3D balloon.
- The GUI's `SPL gain dB` is a single per-speaker level trim; it is separate from
  the measured on-axis response.
- File size is limited to 1 MB; frequencies max 128, rows max 181.
- `synthetic_floorstanding.json` is synthetic test data, not a real
  measurement. Do not use it for acoustic validation.
- Real Spinorama measurement data is third-party and usually CC BY-NC-SA 4.0;
  keep its source/license metadata and do not redistribute it commercially.
- The Spinorama REST API requires a manually approved Bearer key. Keep it in a
  local environment variable for the converter, never in the repository or the
  mod; the static site JSON is a fallback but is not a stable public API.
- Per-speaker selections live in `cinema/speaker_specs/assignments.json`
  (client-local, keyed by dimension + block position). Type defaults live in
  `settings.json`.
