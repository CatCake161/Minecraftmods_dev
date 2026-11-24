#!/usr/bin/env python3
"""Import ASR speaker measurement ZIPs into the local speaker-data tree.

The crawler stores one ``asr_zip_NNNN.zip`` per attachment and a metadata
JSON array. Each item has the ASR thread type/brand/model plus the original
attachment name. This script:

* keeps only Bookshelf / Center / Tower reviews (powered and studio monitors
  are imported as bookshelf, matching the mod's three cabinet types);
* skips models already present in ``speaker data``;
* deduplicates repeated reviews of the same model, keeping the newest thread;
* normalizes display names to ``Brand Series Model (variant)`` and fixes the
  dashboard/attachment typos listed below;
* extracts the ZIP files into ``speaker data/<category>/<model>``;
* writes ``speaker data/asr_models.json`` so ``convert_spinorama.py`` can
  generate profiles without a hand-maintained model table.

Measurement data is third-party (usually CC BY-NC-SA 4.0). The script keeps
the original LICENSE.txt and adds an ASR-SOURCE.txt with thread provenance.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import zipfile
from collections import defaultdict
from datetime import datetime
from pathlib import Path, PurePosixPath

CATEGORY_DIRS = {
    "bookshelf": "Bookshelves",
    "center": "Center speakers",
    "floorstanding": "Floorstanding speakers",
}

# Models already present in this workspace (curated ``MODELS`` folders and
# earlier manual imports). Extend this set when adding folders by hand, so a
# rerun does not create a second copy of the same speaker.
EXISTING_MODEL_KEYS = {
    "adamaudiot5v", "adamt5v", "ascilabf6b", "edifiermr4",
    "elacadanteas61", "genelec8351b", "hsuccb8", "jbl308pmkii",
    "kaliaudioin8", "kaliin8", "kaliaudiolp6", "kefls50",
    "neumannkh80dsp", "klipschr41m", "neumannkh120ii", "neumannkh120mkii",
    "neumannkh150", "neumannkh310", "neumannkh310a",
    "presonuseris35gen2", "presonuserise5xt", "realisticmc1000",
    "toppingma4", "ascilabc8c", "jbl880", "kefreference4c",
    "revelc208", "revelc52", "ascendacousticscmt340", "jbla180",
    "kefr11meta", "polkt50", "revelf206",
    # Dashboard naming variants for models already imported from ASR.
    "hsuresearchcc8b", "hsuresearchccb8",
    "kef4c", "ascendacousticscmt340se", "revelperformac52",
}

# Canonical manufacturer names. The ASR dashboard contains typos, legal
# suffixes and inconsistent casing; display names always use these.
BRAND_NAMES = {
    "Canton Electronics": "Canton",
    "Dali": "DALI",
    "Emotiva Audio": "Emotiva",
    "GolderEar": "GoldenEar",
    "GRIMM Audio": "Grimm Audio",
    "Human": "Human Speakers",
    "Micca Electronics": "Micca",
    "MOONDROP": "Moondrop",
    "MUSICIAN Audio Technology": "Musician Audio",
    "Selah": "Selah Audio",
    "理性派HiFi": "理性派 HiFi",
}

# Series/variant/typo corrections keyed by (dashboard brand, dashboard model).
# The fallback is "<canonical brand> <dashboard model>", which already fixes
# most attachment-name typos (for example "Inifinity" and "Diapson").
NAME_OVERRIDES = {
    ("Amazon Basics", "80W"): "Amazon Basics 80W Bookshelf",
    ("Arendal", "1723"): "Arendal 1723 Monitor",
    ("Arendal", "1961"): "Arendal 1961 Center",
    ("Ascend Acoustics", "CBM-170SE2"): "Ascend Acoustics CBM-170 SE2",
    ("ATC", "SCM19 (v1)"): "ATC SCM19 V1",
    ("Audio Smile", "LBM Mk3"): "Audio Smile Little British Monitor (LBM Mk3)",
    ("Bagby", "Mandolin"): "Bagby Mandolin (DIY)",
    ("Bang & Olufsen", "Beolab20"): "Bang & Olufsen Beolab 20",
    ("Barefoot Sound", "Footprint01"): "Barefoot Sound Footprint 01",
    ("Boston Acoustics", "A 25"): "Boston Acoustics A25",
    ("Carmody", "S2000"): "Carmody S2000 (DIY)",
    ("Celuaris", "SPK5 Purifi"): "Celuaris Purifi SPK5",
    ("Definitive Technology", "D11"): "Definitive Technology Demand D11",
    ("DIY", "Mechano23"): "Mechano23 (DIY kit)",
    ("ELAC", "DF63"): "ELAC Debut 2.0 DF63",
    ("ELAC", "UB5"): "ELAC Uni-Fi UB5",
    ("ELAC", "UCR52"): "ELAC Uni-Fi Reference UCR52",
    ("Fluid Audio", "FX50V2"): "Fluid Audio FX50 V2",
    ("Focal", "SOLO6 ST6"): "Focal Solo6 ST6",
    ("GR Research", "Little Giant Killer (LGK) 2.0"): "GR Research LGK 2.0",
    ("GRIMM Audio", "LS1c"): "Grimm Audio LS1c (with SB1)",
    ("HEDD", "TYPE 05 A-CORE"): "HEDD Type 05 A-Core",
    ("Hsu Research", "HB-1 Mk 2"): "Hsu Research HB-1 Mk2",
    ("Human", "81 dk"): "Human Speakers 81 DK",
    ("Infinity", "Reference 253"): "Infinity Reference R253",
    ("JBL", "125C"): "JBL Stage 125C",
    ("JBL", "135C"): "JBL Stage 135C",
    ("KRK", "ROKIT RP5 G4"): "KRK ROKIT 5 G4",
    ("M-Audio", "BX8"): "M-Audio BX8 D3",
    ("Neumann", "KH-420"): "Neumann KH420",
    ("Parts Express", "C-Note MT"): "Parts Express C-Note (DIY)",
    ("PMC", "twenty.21"): "PMC Twenty.21",
    ("Polk", "ES20"): "Polk Signature Elite ES20",
    ("Polk", "ES60"): "Polk Signature Elite ES60",
    ("Polk", "S15"): "Polk Signature S15",
    ("PSB", "Alpa P3"): "PSB Alpha P3",
    ("Revel", "C25"): "Revel Concerta2 C25",
    ("Revel", "F208"): "Revel Performa3 F208",
    ("Revel", "F228Be"): "Revel Performa3 F228Be",
    ("Revel", "M105"): "Revel Performa3 M105",
    ("Revel", "M106"): "Revel Performa3 M106",
    ("Rogers", "LS 3/5a"): "Rogers LS3/5a",
    ("Salk", "Wow1"): "Salk WoW1",
    ("Sehlin", "Helium"): "Sehlin Helium (DIY)",
    ("SVS", "Ultra"): "SVS Ultra Bookshelf",
    ("Triangle", "Esprit Antal Ez"): "Triangle Esprit Antal EZ",
}

# A few reviews are filed under the modifier/designer rather than the speaker
# manufacturer (GR Research crossovers for Ascend speakers).
ZIP_BRAND_OVERRIDES = {
    "Ascend Sierra-2EX V2.zip": "Ascend Acoustics",
    "Ascend Sierra-2EX V2 GR Research Upgrade.zip": "Ascend Acoustics",
}

# Variants that share the dashboard brand/model, keyed by attachment name.
ZIP_NAME_OVERRIDES = {
    "HEDD Type 20 MK II Open Port.zip": "HEDD Type 20 MK2 (open port)",
    "HEDD Type 20 MKII Closed Port.zip": "HEDD Type 20 MK2 (closed port)",
    "Ascend Sierra-2EX V2.zip": "Ascend Acoustics Sierra-2EX V2 (stock)",
    "Ascend Sierra-2EX V2 GR Research Upgrade.zip":
        "Ascend Acoustics Sierra-2EX V2 (GR Research upgrade)",
    "HIVI DIY 3.1 Sehlin Mod Spinorama.zip":
        "Swans (HiVi) 3.1A (Sehlin mod)",
}

GENERIC_SUFFIXES = (
    r"\s*[-–—]?\s*audio\s+science\s+review\s*$",
    r"\s+frequency\s+response\s+asr\s*$",
    r"\s+frequency\s+response\s*$",
    r"\s+spinorama\s*$",
    r"\s+spinroama\s*$",
    r"\s+spin\s+data\s*$",
    r"\s+measurements?\s*$",
    r"\s+cea[- ]?2034\s*$",
    r"\s+cta[- ]?2034\s*$",
    r"\s+asr\s*$",
)

INVALID_WINDOWS_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def compact(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def model_key(item: dict) -> str:
    model = re.sub(r"\([^)]*\)", " ", item.get("model", ""))
    return compact(f"{item.get('brand', '')} {model}")


def corrected_type(item: dict) -> str:
    """Prefer explicit thread-title wording over the dashboard category."""
    title = item.get("title", "").lower()
    if "center" in title and "main" not in title and "monitor" not in title:
        return "center"
    if "floor" in title or "tower" in title:
        return "floorstanding"
    if "bookshelf" in title:
        return "bookshelf"
    return item.get("modType", "")


def review_date(item: dict) -> datetime:
    try:
        return datetime.strptime(item.get("date", ""), "%m/%d/%y")
    except ValueError:
        return datetime.min


def sanitize_name(value: str) -> str:
    value = INVALID_WINDOWS_CHARS.sub(" ", value)
    value = re.sub(r"\s+", " ", value).strip(" .")
    return value or "unnamed"


def canonical_brand(item: dict) -> str:
    override = ZIP_BRAND_OVERRIDES.get(item.get("name", ""))
    if override is not None:
        return override
    brand = item.get("brand", "").strip()
    return BRAND_NAMES.get(brand, brand)


def canonical_name(item: dict) -> str:
    """Return the display name: brand + series/model + variant."""
    zip_name = item.get("name", "")
    if zip_name in ZIP_NAME_OVERRIDES:
        return ZIP_NAME_OVERRIDES[zip_name]
    key = (item.get("brand", ""), item.get("model", ""))
    if key in NAME_OVERRIDES:
        return NAME_OVERRIDES[key]
    brand = canonical_brand(item)
    model = item.get("model", "").strip()
    return re.sub(r"\s+", " ", f"{brand} {model}").strip(" .")


def display_name(zip_name: str) -> str:
    name = Path(zip_name).stem
    previous = None
    while previous != name:
        previous = name
        for pattern in GENERIC_SUFFIXES:
            name = re.sub(pattern, "", name, flags=re.IGNORECASE).strip()
    return sanitize_name(name)


def slug(value: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return value or "profile"


def unique_name(base: str, used: set[str]) -> str:
    candidate = base
    index = 2
    while candidate.lower() in used:
        candidate = f"{base} {index}"
        index += 1
    used.add(candidate.lower())
    return candidate


def unique_id(base: str, used: set[str]) -> str:
    candidate = base
    index = 2
    while candidate in used:
        candidate = f"{base}-{index}"
        index += 1
    used.add(candidate)
    return candidate


def zip_has_spin(source: Path) -> bool:
    with zipfile.ZipFile(source) as archive:
        names = [PurePosixPath(info.filename).name.lower()
                 for info in archive.infolist() if not info.is_dir()]
    return (any(name.endswith("spl horizontal.txt") for name in names)
            and any(name.endswith("spl vertical.txt") for name in names))


def extract_zip(source: Path, target: Path) -> bool:
    target.mkdir(parents=True, exist_ok=True)
    written: dict[str, str] = {}
    with zipfile.ZipFile(source) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            parts = PurePosixPath(info.filename)
            if "__MACOSX" in parts.parts:
                continue
            name = parts.name
            if not name or name.startswith("._") or name == ".DS_Store":
                continue
            lower = name.lower()
            if lower.endswith("spl horizontal.txt"):
                name = "SPL Horizontal.txt"
            elif lower.endswith("spl vertical.txt"):
                name = "SPL Vertical.txt"
            name = sanitize_name(name)
            if name.lower() in written:
                stem, suffix = name.rsplit(".", 1) if "." in name else (name, "")
                index = 2
                while name.lower() in written:
                    name = f"{stem} ({index}).{suffix}" if suffix else f"{stem} ({index})"
                    index += 1
            written[name.lower()] = name
            with archive.open(info) as src, (target / name).open("wb") as dst:
                shutil.copyfileobj(src, dst)
    return ((target / "SPL Horizontal.txt").is_file()
            and (target / "SPL Vertical.txt").is_file())


def select_items(items: list[dict]) -> tuple[list[dict], list[tuple[dict, str]]]:
    new_items = []
    skipped = []
    for item in items:
        item["modType"] = corrected_type(item)
        key = model_key(item)
        item["_key"] = key
        if key in EXISTING_MODEL_KEYS:
            skipped.append((item, "existing model"))
        else:
            new_items.append(item)

    groups: dict[str, list[dict]] = defaultdict(list)
    for item in new_items:
        groups[item["_key"]].append(item)

    selected = []
    for group in groups.values():
        if len({item["url"] for item in group}) == 1:
            selected.extend(group)
            continue
        group.sort(key=review_date, reverse=True)
        selected.append(group[0])
        skipped.extend((item, f"duplicate of {group[0]['date']}") for item in group[1:])
    return selected, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zips", type=Path, required=True,
                        help="folder containing asr_zip_NNNN.zip files")
    parser.add_argument("--metadata", type=Path, required=True,
                        help="asr_zips_all.json produced by the crawler")
    parser.add_argument("--input", type=Path, default=Path("speaker data"),
                        help="speaker data root (default: 'speaker data')")
    parser.add_argument("--manifest", type=Path,
                        default=Path("speaker data/asr_models.json"),
                        help="generated model manifest path")
    args = parser.parse_args()

    items = json.loads(args.metadata.read_text(encoding="utf-8"))
    selected, skipped = select_items(items)

    used_names: set[str] = set()
    used_ids: set[str] = set()
    manifest = {}
    imported_sources: set[tuple[str, str]] = set()
    if args.manifest.is_file():
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        by_source = {(item.get("url", ""), item.get("name", "")): item
                     for item in items}
        for entry in manifest.values():
            used_ids.add(entry["id"])
            imported_sources.add((entry.get("thread", ""),
                                  entry.get("originalZip", "")))
            # Refresh display names on every run so naming-table edits apply
            # to models that were imported earlier.
            item = by_source.get((entry.get("thread", ""),
                                  entry.get("originalZip", "")))
            if item is not None:
                entry["name"] = canonical_name(item)
                entry["brand"] = canonical_brand(item)
                entry["model"] = item.get("model", "")
    for category_dir in CATEGORY_DIRS.values():
        directory = args.input / category_dir
        if directory.is_dir():
            used_names.update(path.name.lower() for path in directory.iterdir()
                              if path.is_dir())

    imported = 0
    failed = []
    for item in selected:
        category = CATEGORY_DIRS.get(item.get("modType"))
        if category is None:
            failed.append((item, "unknown type"))
            continue
        if (item.get("url", ""), item.get("name", "")) in imported_sources:
            skipped.append((item, "already imported"))
            continue
        source = args.zips / item["file"]
        if not source.is_file():
            failed.append((item, "zip missing"))
            continue
        if not zip_has_spin(source):
            skipped.append((item, "no SPL Horizontal/Vertical"))
            continue
        name = canonical_name(item)
        folder = unique_name(sanitize_name(name), used_names)
        target = args.input / category / folder
        try:
            if not extract_zip(source, target):
                shutil.rmtree(target, ignore_errors=True)
                failed.append((item, "no SPL Horizontal/Vertical"))
                continue
        except Exception as exc:  # noqa: BLE001 - report bad third-party zips
            shutil.rmtree(target, ignore_errors=True)
            failed.append((item, f"{type(exc).__name__}: {exc}"))
            continue

        # Keep ids derived from the attachment so earlier selections and
        # assignments survive a re-import even when the display name changes.
        profile_id = unique_id(slug(display_name(item["name"])), used_ids)
        manifest[f"{category}/{folder}"] = {
            "id": profile_id,
            "name": name,
            "brand": canonical_brand(item),
            "model": item.get("model", ""),
            "type": item["modType"],
            "source": "Audio Science Review (ASR) / Spinorama",
            "license": "CC BY-NC-SA 4.0",
            "thread": item.get("url", ""),
            "threadTitle": item.get("title", ""),
            "originalZip": item.get("name", ""),
            "zipFile": item.get("file", ""),
            "reviewDate": item.get("date", ""),
        }
        (target / "ASR-SOURCE.txt").write_text(
            f"Source: Audio Science Review (ASR), review by amirm\n"
            f"Thread: {item.get('url', '')}\n"
            f"Title: {item.get('title', '')}\n"
            f"Original attachment: {item.get('name', '')}\n"
            f"Review date: {item.get('date', '')}\n"
            f"License: CC BY-NC-SA 4.0\n",
            encoding="utf-8",
        )
        imported += 1

    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"imported {imported} model(s), {len(failed)} failure(s), "
          f"{len(skipped)} skipped")
    for item, reason in failed:
        print(f"  FAILED {item.get('name')}: {reason}")
    print(f"manifest: {args.manifest} ({len(manifest)} entries)")


if __name__ == "__main__":
    main()
