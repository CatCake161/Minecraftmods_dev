#!/usr/bin/env python3
"""Convert ASR/Spinorama SPL Horizontal/Vertical text files to speaker data JSON.

The input is the raw ASR data set: one folder per model containing
``SPL Horizontal.txt`` and ``SPL Vertical.txt``. Only those two files are read.
No downsampling, smoothing, or 1/3-octave conversion is performed.

Generated profiles are local user/import data. They are written to
``run/cinema/speaker_data/profiles`` by default and are not packaged into the
mod.

Angle convention (ANSI/CTA-2034-A Figure 1):
  0 deg      on-axis reference
  +H         toward the listener's right when facing the speaker
  +V         up
  +-180      the same rear point

The source files use On-Axis, +-10 ... +-170, and +180. A -180 row is added
with the same SPL as +180 so the stored table covers -180 ... +180.

The stored ``frequencyRange`` is the low/high -10 dB crossing of the on-axis
response relative to its full-band energy average, rounded to 1 Hz.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Iterable

# id, display name, speaker type
MODELS = {
    "Adam T5V Spinorama": ("adam-t5v", "Adam T5V", "bookshelf"),
    "AsciLab F6B Frequency Response": ("ascilab-f6b", "Ascilab F6B", "bookshelf"),
    "Edifier MR4 Frequency Response ASR": ("edifier-mr4", "Edifier MR4", "bookshelf"),
    "Genelec 8351B Frequency Response": ("genelec-8351b", "Genelec 8351B", "bookshelf"),
    "JBL 308P MKII Spinorama": ("jbl-308p-mkii", "JBL 308P MkII", "bookshelf"),
    "Kali LP6 Spinorama": ("kali-lp6", "Kali LP6", "bookshelf"),
    "KH80 New Spinorama": ("neumann-kh80", "Neumann KH80", "bookshelf"),
    "Neumann KH120 II": ("neumann-kh120-ii", "Neumann KH120 II", "bookshelf"),
    "Neumann KH150": ("neumann-kh150", "Neumann KH150", "bookshelf"),
    "Neumann KH310 Spinorama": ("neumann-kh310", "Neumann KH310", "bookshelf"),
    "Presonus ERIS 3.5 Gen 2": (
        "presonus-eris-3-5-gen2", "PreSonus Eris 3.5 Gen 2", "bookshelf"),
    "Presonus Eris E5 XT Spinorama": (
        "presonus-eris-e5-xt", "PreSonus Eris E5 XT", "bookshelf"),
    "Topping MA4 Frequency Response": ("topping-ma4", "Topping MA4", "bookshelf"),
    "Ascilab C8C": ("ascilab-c8c", "Ascilab C8C", "center"),
    "JBL Project Array 880 Center Frequency Response": (
        "jbl-project-array-880-center", "JBL Project Array 880 Center", "center"),
    "KEF Reference 4C Frequency Response ASR": (
        "kef-reference-4c", "KEF Reference 4C", "center"),
    "Revel C208": ("revel-c208", "Revel C208", "center"),
    "JBL A180 Frequency Response": ("jbl-a180", "JBL A180", "floorstanding"),
    "KEF R11 Meta": ("kef-r11-meta", "KEF R11 Meta", "floorstanding"),
    "Polk T50 Frequency Response": ("polk-t50", "Polk T50", "floorstanding"),
    "Revel F206": ("revel-f206", "Revel F206", "floorstanding"),
}

CATEGORY_TYPES = {
    "Bookshelves": "bookshelf",
    "Center speakers": "center",
    "Floorstanding speakers": "floorstanding",
}

RANGE_THRESHOLD_DB = -10.0
SOURCE = "Audio Science Review (ASR) / Spinorama"
LICENSE = "CC BY-NC-SA 4.0"


def parse_angle(label: str) -> float:
    label = label.strip().replace("\u00b0", "")
    if label == "On-Axis":
        return 0.0
    return float(label)


def read_table(path: Path) -> tuple[list[float], list[float], list[list[float]]]:
    rows: list[list[str]] = []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        for row in csv.reader(handle, delimiter="\t", quotechar='"'):
            if row:
                rows.append([cell.strip() for cell in row])
    if len(rows) < 4:
        raise ValueError(f"{path}: not enough rows")
    header = rows[1]
    angles: list[float] = []
    for index, cell in enumerate(header):
        if index == 0:
            angles.append(0.0)
        elif cell:
            angles.append(parse_angle(cell))
    data = rows[3:]
    for row in data:
        if len(row) < 2 * len(angles):
            raise ValueError(f"{path}: row has too few columns")
    frequencies = [float(row[0].replace(",", "")) for row in data]
    columns: list[list[float]] = []
    for index in range(len(angles)):
        pair = 2 * index + 1
        columns.append([float(row[pair].replace(",", "")) for row in data])
    return frequencies, angles, columns


def energy_average_db(values: list[float]) -> float:
    if not values:
        return 0.0
    return 10.0 * math.log10(sum(10.0 ** (value / 10.0) for value in values)
                             / len(values))


def crossing_hz(f0: float, f1: float, db0: float, db1: float,
                threshold: float) -> float:
    if db1 == db0:
        return f0
    t = (threshold - db0) / (db1 - db0)
    t = max(0.0, min(1.0, t))
    return math.exp(math.log(f0) + t * (math.log(f1) - math.log(f0)))


def round_hz(value: float) -> int:
    return int(math.floor(value + 0.5))


def frequency_range(frequencies: list[float], on_axis: list[float]) -> list[int]:
    reference = energy_average_db(on_axis)
    threshold = reference + RANGE_THRESHOLD_DB
    start = min(range(len(frequencies)), key=lambda i: abs(frequencies[i] - 1000.0))
    low = frequencies[0]
    for index in range(start, 0, -1):
        if on_axis[index] >= threshold > on_axis[index - 1]:
            low = crossing_hz(frequencies[index - 1], frequencies[index],
                              on_axis[index - 1], on_axis[index], threshold)
            break
    high = frequencies[-1]
    for index in range(start, len(frequencies) - 1):
        if on_axis[index] >= threshold > on_axis[index + 1]:
            high = crossing_hz(frequencies[index], frequencies[index + 1],
                               on_axis[index], on_axis[index + 1], threshold)
            break
    if not math.isfinite(low) or not math.isfinite(high) or high <= low:
        low = frequencies[0]
        high = frequencies[-1]
    rounded_low = round_hz(low)
    rounded_high = round_hz(high)
    if rounded_high <= rounded_low:
        return [round_hz(frequencies[0]), round_hz(frequencies[-1])]
    return [rounded_low, rounded_high]


def convert_model(model_dir: Path, category: str) -> dict:
    model = MODELS.get(model_dir.name)
    if model is None:
        raise ValueError(f"unknown model folder: {model_dir.name}")
    model_id, name, speaker_type = model
    expected_type = CATEGORY_TYPES.get(category)
    if expected_type is None:
        raise ValueError(f"unknown category: {category}")
    if speaker_type != expected_type:
        raise ValueError(f"{model_dir.name}: type {speaker_type} does not match {category}")

    frequencies_h, angles_h, columns_h = read_table(model_dir / "SPL Horizontal.txt")
    frequencies_v, angles_v, columns_v = read_table(model_dir / "SPL Vertical.txt")
    if frequencies_h != frequencies_v:
        raise ValueError(f"{model_dir.name}: H/V frequency grids differ")
    if angles_h != angles_v:
        raise ValueError(f"{model_dir.name}: H/V angle headers differ")
    if columns_h[0] != columns_v[0]:
        raise ValueError(f"{model_dir.name}: H/V on-axis columns differ")
    if any(not math.isfinite(value) for column in columns_h + columns_v for value in column):
        raise ValueError(f"{model_dir.name}: non-finite SPL value")

    by_angle_h = dict(zip(angles_h, columns_h))
    by_angle_v = dict(zip(angles_v, columns_v))
    if 180.0 not in by_angle_h or 180.0 not in by_angle_v:
        raise ValueError(f"{model_dir.name}: missing +180 degree column")
    if -180.0 in by_angle_h or -180.0 in by_angle_v:
        raise ValueError(f"{model_dir.name}: unexpected -180 degree column")

    def rows(by_angle: dict[float, list[float]]) -> list[dict]:
        result = [{"angle": -180.0, "spl": by_angle[180.0]}]
        for angle in sorted(by_angle):
            if angle == 180.0:
                result.append({"angle": angle, "spl": by_angle[angle]})
            else:
                result.append({"angle": angle, "spl": by_angle[angle]})
        return result

    on_axis = columns_h[0]
    return {
        "id": model_id,
        "name": name,
        "type": speaker_type,
        "pattern": "measured",
        "frequencyRange": frequency_range(frequencies_h, on_axis),
        "source": SOURCE,
        "license": LICENSE,
        "frequencies": frequencies_h,
        "onAxis": on_axis,
        "horizontal": rows(by_angle_h),
        "vertical": rows(by_angle_v),
    }


def find_models(root: Path) -> Iterable[tuple[Path, str]]:
    for category_dir in sorted(root.iterdir()):
        if not category_dir.is_dir() or category_dir.name not in CATEGORY_TYPES:
            continue
        for model_dir in sorted(category_dir.iterdir()):
            if not model_dir.is_dir():
                continue
            if ((model_dir / "SPL Horizontal.txt").is_file()
                    and (model_dir / "SPL Vertical.txt").is_file()):
                yield model_dir, category_dir.name


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("speaker data"),
                        help="raw measurement root (default: 'speaker data')")
    parser.add_argument("--output", type=Path,
                        default=Path("run/cinema/speaker_data/profiles"),
                        help="profile output directory")
    args = parser.parse_args()

    if not args.input.is_dir():
        parser.error(f"input directory not found: {args.input}")
    args.output.mkdir(parents=True, exist_ok=True)
    count = 0
    for model_dir, category in find_models(args.input):
        profile = convert_model(model_dir, category)
        target = args.output / f"{profile['id']}.json"
        target.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8", newline="\n")
        print(f"{profile['id']}: {len(profile['frequencies'])} bands, "
              f"{len(profile['horizontal'])} H rows, {len(profile['vertical'])} V rows, "
              f"range {profile['frequencyRange'][0]:.1f}-{profile['frequencyRange'][1]:.1f} Hz")
        count += 1
    print(f"wrote {count} profile(s) to {args.output}")


if __name__ == "__main__":
    main()
