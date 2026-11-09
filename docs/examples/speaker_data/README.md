# Speaker Data Profiles

Copy `.json` profiles into:

```text
<game>/cinema/speaker_data/profiles/
```

or drag them onto the **Speaker Data** screen. Right-click a speaker block with
an empty hand to open that speaker's panel. Role selection is also in that
panel; `/cinema_speaker_data` opens the type-default editor (debug entry).

The GUI's **Profile** button opens a paged picker, so every profile for the
current cabinet type is directly selectable. Changes save immediately; Close
flushes any debounced field edit.

The development workspace generates 21 ASR profiles into
`run/cinema/speaker_data/profiles/` with:

```text
python docs/examples/speaker_data/convert_spinorama.py
```

These profiles are local data and are not packaged into the mod.

## Simple profile

```json
{
  "id": "my-sub-cardioid",
  "name": "My Sub Cardioid",
  "type": "subwoofer",
  "enclosure": "sealed",
  "pattern": "cardioid",
  "frequencyRange": [20, 200],
  "source": "local test",
  "license": "CC0"
}
```

`pattern` can be `omni`, `cardioid`, or `unmeasured`. For subwoofers,
`enclosure` can be `sealed` (alias `closed`) or `ported` (legacy aliases
`bass-reflex`, `bass_reflex`, `reflex`); omit it if unknown. The built-in
subwoofer profiles are `sub-sealed-omni`, `sub-sealed-cardioid`,
`sub-ported-omni` and `sub-ported-cardioid`; legacy ids `sub-omni` and
`sub-cardioid` map to the sealed variants, while `sub-reflex-omni` and
`sub-reflex-cardioid` map to the ported variants. For built-in and other
unmeasured subwoofers the enclosure now drives the analytic low-frequency
response: sealed uses a second-order Butterworth high-pass at the profile's
-3 dB low edge (30 Hz for the built-in), while ported uses a fourth-order
high-pass at its low edge (20 Hz for the built-in). Measured subwoofer profiles
keep their measured on-axis response and skip the analytic enclosure model.
The manual Low/High range (10-1000 Hz) is applied as a second-order
Butterworth high-pass/low-pass pair; it defaults to the profile range (sealed
30-200 Hz, ported 20-200 Hz).

> This importer expects the `speaker_data` schema below, not the raw Spinorama
> Plotly JSON. The converter under this folder reads `SPL Horizontal.txt` and
> `SPL Vertical.txt` and writes this schema.

## Measured profile

A measured profile contains the three raw measurement datasets:

1. **on-axis response** (`onAxis`): absolute SPL per frequency;
2. **horizontal directivity** (`horizontal`): SPL rows per horizontal angle;
3. **vertical directivity** (`vertical`): SPL rows per vertical angle.

`onAxis`, `horizontal` and `vertical` must use the same SPL scale. The parser
subtracts the on-axis response from each H/V row to obtain relative directivity.
Raw measurement grids are kept unchanged; the converter does not downsample to
1/3 octave or any other grid.

```json
{
  "id": "my-speaker",
  "name": "My Speaker",
  "type": "floorstanding",
  "pattern": "measured",
  "frequencyRange": [40.0, 20000.0],
  "source": "Spinorama / measurement source",
  "license": "CC BY-NC-SA 4.0",
  "frequencies": [125, 250, 500, 1000, 2000, 4000, 8000, 16000],
  "onAxis": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5],
  "horizontal": [
    {"angle": -180, "spl": [78.0, 76.2, 74.1, 72.0, 69.8, 67.5, 62.9, 57.5]},
    {"angle": -90, "spl": [85.0, 84.7, 84.1, 83.0, 80.8, 78.5, 74.9, 70.5]},
    {"angle": 0, "spl": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5]},
    {"angle": 30, "spl": [87.5, 87.6, 87.3, 87.0, 86.4, 85.3, 83.7, 81.0]},
    {"angle": 90, "spl": [85.0, 84.7, 84.1, 83.0, 80.8, 78.5, 74.9, 70.5]},
    {"angle": 180, "spl": [78.0, 76.2, 74.1, 72.0, 69.8, 67.5, 62.9, 57.5]}
  ],
  "vertical": [
    {"angle": -180, "spl": [78.0, 76.2, 74.1, 72.0, 69.8, 67.5, 62.9, 57.5]},
    {"angle": -90, "spl": [82.0, 81.2, 80.1, 78.0, 75.8, 73.5, 70.9, 67.5]},
    {"angle": 0, "spl": [88.0, 88.2, 88.1, 88.0, 87.8, 87.5, 86.9, 85.5]},
    {"angle": 30, "spl": [87.5, 87.4, 86.9, 86.2, 85.3, 84.0, 81.9, 78.5]},
    {"angle": 90, "spl": [82.0, 81.2, 80.1, 78.0, 75.8, 73.5, 70.9, 67.5]},
    {"angle": 180, "spl": [78.0, 76.2, 74.1, 72.0, 69.8, 67.5, 62.9, 57.5]}
  ]
}
```

- `frequencies` must be positive and have the same length as `onAxis` and each
  row's `spl`. The parser sorts frequencies ascending. File size is limited to
  1 MB; frequencies max 256, rows max 181.
- Angle convention follows ANSI/CTA-2034-A Figure 1: 0° is the on-axis
  reference; positive horizontal is toward the listener's right when facing the
  speaker; positive vertical is up. The rear point is both +180° and -180°.
  The converter adds a `-180` row with the same SPL as `+180`.
- `frequencyRange` is the on-axis -3 dB band, rounded to 1 Hz. For measured
  profiles it is recomputed from the lowest/highest `onAxis` samples within
  -3 dB of the full-band energy average
  `10*log10(mean(10^(SPL/10)))` over every measured on-axis sample; a stale
  JSON value is ignored. Boundaries are interpolated to the neighbouring
  below-threshold sample, and interior dips do not truncate the range. A
  boundary at the measurement edge uses that edge. For analytic/unmeasured
  profiles the JSON value is kept.
- `onAxis` keeps absolute SPL and the raw shape. For future per-speaker EQ, the
  profile exposes `onAxisEqGainDb(f)` = measured on-axis SPL minus the same
  full-band energy average. This normalizes the overall on-axis loudness
  without flattening the measured response. Each measured on-axis sample has
  equal weight; because the raw grid is log-spaced, this is approximately
  log-frequency weighting, not a linear-frequency integral.
- Rows are sorted by angle; the parser mirrors half-plane tables if needed.
  Horizontal and vertical tables are combined as a separable approximation
  (dB sum), not a full 3D balloon.
- The GUI's `SPL gain dB` is a single per-speaker level trim; it is separate from
  the measured on-axis response. For non-subwoofer measured profiles the
  Low/High Hz fields are read-only and always use the -3 dB `frequencyRange`;
  only `SPL gain dB` is editable. Subwoofers always keep a manually editable
  bass-management band (10–1000 Hz, default follows the profile: sealed
  30–200 Hz, ported 20–200 Hz) while the profile's -3 dB range is shown
  separately. Unmeasured/analytic profiles can still edit
  Low/High Hz.
- The converter does not package profiles into the mod. Generated profiles are
  local user/import data under `cinema/speaker_data/profiles/`.
- `synthetic_floorstanding.json` is synthetic test data, not a real
  measurement. Do not use it for acoustic validation.
- Real Spinorama measurement data is third-party and usually CC BY-NC-SA 4.0;
  keep its source/license metadata and do not redistribute it commercially.
- The Spinorama REST API requires a manually approved Bearer key. Keep it in a
  local environment variable for the converter, never in the repository or the
  mod; the static site JSON is a fallback but is not a stable public API.
- Per-speaker selections live in `cinema/speaker_data/assignments.json`
  (client-local, keyed by dimension + block position). Type defaults live in
  `settings.json`.
