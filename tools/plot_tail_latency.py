"""Plot explicitly selected native lab captures without pooling runs or changing them.

Example (PowerShell):
  python tools/plot_tail_latency.py --run baseline=<base>/runs/write/result.json \
    --run candidate=<candidate>/runs/write/result.json --output <new-chart-directory>

Only a new output directory is written. Matplotlib is imported when rendering,
so the schema/quantile/control tests need only the Python standard library.
Visual review is separate evidence: --visual-qa label=absolute/visual-qa.json
accepts {"schema":1,"scenarios":{"full-view":{"passed":true,
"screenshot":"<run>/capture.png","scope":"Terrain and fixed camera inspected"}}}.
An absent review is unreviewed, never an implicit pass.
--treatment declares one comparison axis: cc-source (default), cc-pacing
(three verified numeric send limits), cc-maintenance (the enable boolean and
three reviewed limits), or jvm (G1/ZGC, Xms and pretouch only).
Separate riven-source and render-upload axes require the
same CC artifact and their reviewed resource/property proof.
Every remaining setting and raw input hash is retained. Missing byte proofs
isolate old captures; a declared treatment is not an accepted performance gain.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from fractions import Fraction
from pathlib import Path
import re
import struct
import sys
import textwrap
import zipfile


EXPECTED_CUBES = 25 ** 3
DEFAULT_METRICS = ("server.autoTicks", "client.frameIntervals")
THRESHOLDS_NS = (16_666_667, 33_333_333, 50_000_000, 100_000_000,
                 250_000_000, 1_000_000_000)
FULL_VIEW_SCENARIOS = ("full-view",)
QUANTILE_METHOD = "nearest-rank ceil(q*n), no interpolation; each run separately"
# Bytecode-reviewed owners. Unknown versions retain strict byte hashes.
AE2_EXPORT_OWNER = "57e68134a5caead143856659aca8d711845ff90b3ffbcf48071f0a409d09f6a7"
CLEANROOM_LOADING_OWNER = "99d9c40f5f1a73eb580f0125fb09a3719df9825236daa2190793767c5338e5e6"
CLEANROOM_LOADING_OWNERS = (CLEANROOM_LOADING_OWNER,
    "079a7e64ee558926391587da0d04c8f77ad10a633440a5276342cc650708f085")
TREATMENTS = ("cc-source", "cc-pacing", "cc-maintenance", "jvm", "riven-source", "render-upload")
PROPERTY_TREATMENTS = {"render-upload": ("cubiclab.uploadStrategy", ("original", "orphan"))}
PACING_KEYS = ("general.I:maxCubeSendTimeMillis", "general.I:maxCubeSendBytesPerTick", "general.I:maxCubePacketBytes")
MAINTENANCE_KEYS = {"general.B:incrementalMaintenance": None,
                    "general.I:maintenanceInspectionsPerTick": (1, 65536),
                    "general.I:maintenanceUnloadsPerTick": (1, 4096),
                    "general.I:maintenanceTimeMillis": (1, 50)}
JVM_GC_FLAGS = ("-XX:+UseG1GC", "-XX:+UseZGC")


class ChartDataError(ValueError):
    """Input cannot support the requested observation or comparison."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    try:
        def reject_constant(value):
            raise ValueError("Non-finite JSON number: " + value)
        value = json.loads(path.read_text(encoding="utf-8-sig"), parse_constant=reject_constant)
    except (OSError, ValueError) as error:
        raise ChartDataError(f"Cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise ChartDataError(f"Expected JSON object: {path}")
    return value


def local_absolute_path(value):
    try:
        path = Path(value)
    except TypeError as error:
        raise ChartDataError(f"Expected an absolute local path: {value}") from error
    if not path.is_absolute() or str(value).startswith(("\\\\", "//")) or "://" in str(value):
        raise ChartDataError(f"Expected an absolute local path: {value}")
    return path.resolve()


def labeled_paths(arguments):
    paths = {}
    for argument in arguments:
        label, separator, value = argument.partition("=")
        if not separator or not label.strip() or not value:
            raise ChartDataError("Use label=absolute/path/result.json")
        if label in paths:
            raise ChartDataError(f"Duplicate run label: {label}")
        paths[label] = local_absolute_path(value)
    return paths


def integer(value, name):
    if type(value) is not int or value < 0:
        raise ChartDataError(f"{name} must be a nonnegative integer")
    return value


def nearest_rank(values, quantile):
    """Use an exact rational rank, avoiding ceil(0.07 * 100) rounding errors."""
    fraction = Fraction(str(quantile))
    if not values or not 0 < fraction <= 1:
        raise ChartDataError("A quantile needs samples and 0 < q <= 1")
    rank = (fraction.numerator * len(values) + fraction.denominator - 1) // fraction.denominator
    return sorted(values)[rank - 1]


def lookup(value, dotted_path):
    for part in dotted_path.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def raw_csv_path(result_path, run_id, side, filename):
    if not isinstance(filename, str) or Path(filename).name != filename or "\\" in filename:
        raise ChartDataError("chronologicalCsv must name a local CSV file, without directories")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", run_id):
        raise ChartDataError("Invalid capture runId")
    # Current runner uses ./raw; support retained result files one directory below ../raw.
    candidates = [base / "raw" / run_id / side / filename
                  for base in (result_path.parent, result_path.parent.parent)]
    found = [path for path in candidates if path.is_file()]
    if len(found) != 1:
        raise ChartDataError(f"Expected one unambiguous raw CSV for {side}/{filename}; found {len(found)}")
    return found[0]


def read_samples(path):
    samples = []
    try:
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["ordinal", "observationEndOffsetNanos", "durationNanos"]:
                raise ChartDataError(f"Unexpected chronology columns: {path}")
            for row in reader:
                if None in row or any(value is None for value in row.values()):
                    raise ChartDataError(f"Unexpected chronology row width in {path}")
                try:
                    ordinal, ended, duration = (int(row[key]) for key in reader.fieldnames)
                except (ValueError, TypeError) as error:
                    raise ChartDataError(f"Non-integer chronology row in {path}") from error
                if ordinal != len(samples) or min(ended, duration) < 0:
                    raise ChartDataError(f"Invalid chronology ordinal/time in {path}")
                samples.append((ordinal, ended, duration))
    except OSError as error:
        raise ChartDataError(f"Cannot read chronology {path}: {error}") from error
    return samples


def metric_from_raw(result_path, run_id, side, name, summary, elapsed_ns):
    metric = {"name": name, "side": side, "validWholeCapture": False, "issues": [],
              "rawCsv": None, "rawSha256": None, "samples": [], "statistics": None,
              "captureReported": summary}
    try:
        if not isinstance(summary, dict):
            raise ChartDataError("Metric or raw chronology label is missing")
        count = integer(summary.get("count"), "count")
        stored = integer(summary.get("storedCount"), "storedCount")
        dropped = integer(summary.get("droppedCount"), "droppedCount")
        path = raw_csv_path(result_path, run_id, side, summary.get("chronologicalCsv"))
        samples = read_samples(path)
        metric.update(rawCsv=str(path), rawSha256=digest(path), samples=samples)
        if stored != len(samples) or count != stored + dropped:
            raise ChartDataError("Summary count/stored/dropped disagree with raw row count")
        if dropped or count != stored or summary.get("percentilesCoverAllObservations") is not True:
            raise ChartDataError("Raw chronology is only a prefix: whole-capture quantiles rejected")
        if not count:
            raise ChartDataError("No observations; quantiles undefined")
        if integer(elapsed_ns, "elapsedNanos") == 0:
            raise ChartDataError("Capture elapsed time must be positive")
        durations = [sample[2] for sample in samples]
        total = sum(durations)
        quantiles = {"p50Ms": nearest_rank(durations, ".5") / 1e6,
                     "p95Ms": nearest_rank(durations, ".95") / 1e6,
                     "p99Ms": nearest_rank(durations, ".99") / 1e6,
                     "maxMs": max(durations) / 1e6}
        for key, actual in quantiles.items():
            reported = summary.get(key)
            if reported is not None and (type(reported) not in (int, float) or
                                         not math.isclose(reported, actual, rel_tol=1e-12, abs_tol=1e-7)):
                raise ChartDataError(f"Raw {key} differs from captured summary")
        if summary.get("totalNanos") is not None and summary["totalNanos"] != total:
            raise ChartDataError("Raw duration total differs from captured summary")
        exceedances = {str(threshold): sum(value > threshold for value in durations)
                       for threshold in THRESHOLDS_NS}
        reported_exceedances = summary.get("exceedanceCountsAboveNanos", {})
        if any(reported_exceedances.get(key, value) != value for key, value in exceedances.items()):
            raise ChartDataError("Raw exceedance counts differ from captured summary")
        excess = sum(max(0, value - 50_000_000) for value in durations)
        if summary.get("excessOver50msNanos", excess) != excess:
            raise ChartDataError("Raw excess-duration burden differs from captured summary")
        metric["statistics"] = {**quantiles, "count": count, "totalNanos": total,
            "meanMs": total / count / 1e6, "quantileMethod": QUANTILE_METHOD,
            "p99Rank": (99 * count + 99) // 100, "empiricalTailResolutionPercent": 100 / count,
            "exceedanceCountsAboveNanos": exceedances,
            "exceedancesPer1000Observations": {key: value * 1000 / count for key, value in exceedances.items()},
            "excessOver50msSeconds": excess / 1e9,
            "excessOver50msSecondsPerCaptureMinute": excess * 60 / elapsed_ns if elapsed_ns > 0 else None,
            "captureElapsedSeconds": elapsed_ns / 1e9,
            "burdenScope": "Sum of per-observation excess; overlapping operations are not exclusive wall time",
            "outOfOrderEndOffsets": sum(b[1] < a[1] for a, b in zip(samples, samples[1:]))}
        metric["validWholeCapture"] = True
    except (ChartDataError, TypeError) as error:
        metric["issues"].append(str(error))
    return metric


def manifest_for(result_path):
    for base in (result_path.parent, result_path.parent.parent, result_path.parent.parent.parent):
        path = base / "lab.json"
        if path.is_file():
            return path, read_json(path)
    return None, {}


def visual_qa(path):
    if path is None:
        return {"status": "unreviewed", "source": None, "scenarios": {}}
    data = read_json(path)
    if type(data.get("schema")) is not int or data["schema"] != 1 or not isinstance(data.get("scenarios"), dict):
        raise ChartDataError("Visual QA needs schema 1 and a scenarios object")
    records = {}
    for name, review in data["scenarios"].items():
        if (not isinstance(review, dict) or type(review.get("passed")) is not bool or
                not isinstance(review.get("scope"), str) or not review["scope"].strip()):
            raise ChartDataError(f"Visual QA {name} needs passed:boolean and scope:string")
        screenshot = local_absolute_path(review.get("screenshot", ""))
        if not screenshot.is_file():
            raise ChartDataError(f"Visual QA screenshot is missing: {screenshot}")
        records[name] = {**review, "screenshot": str(screenshot), "screenshotSha256": digest(screenshot)}
    return {"status": "recorded", "source": str(path), "sha256": digest(path), "scenarios": records}


def full_view_gate(row):
    server = row.get("server", {}).get("preparationView", {})
    client = row.get("client", {}).get("fullView", {})
    if not isinstance(server, dict) or not isinstance(client, dict):
        raise ChartDataError("Full-view summaries must be JSON objects")
    geometry = server.get("geometry")
    lower, upper = server.get("previousIncompleteObservationNanos"), server.get("deliveredNanos")
    clients = server.get("clients", {})
    issues = []
    if not isinstance(geometry, dict) or geometry != client.get("geometry"):
        issues.append("Server/client target camera geometry differs or is missing")
    if not isinstance(geometry, dict) or geometry.get("distance") != 12 or geometry.get("expected") != EXPECTED_CUBES:
        issues.append("Full view must cover the exact distance-12 15,625-cube target")
    if (server.get("expected") != EXPECTED_CUBES or server.get("serverReady") != EXPECTED_CUBES or
            server.get("serverSent") != EXPECTED_CUBES or client.get("resident") != EXPECTED_CUBES or
            not isinstance(clients, dict) or not clients or any(count != EXPECTED_CUBES for count in clients.values())):
        issues.append("Full data coverage/readiness/delivery is incomplete")
    if (type(lower) is not int or type(upper) is not int or not 0 <= lower < upper):
        issues.append("A complete data-residency interval is unavailable")
    observations = server.get("samples", [])
    if not isinstance(observations, list):
        raise ChartDataError("Full-view observations must be a list")
    safe_observations = []
    first_complete = None
    last_incomplete = 0
    previous_time = -1
    for point in observations:
        if (not isinstance(point, dict) or type(point.get("nanos")) is not int or point["nanos"] <= previous_time or
                any(type(point.get(key)) is not int or not 0 <= point[key] <= EXPECTED_CUBES
                    for key in ("ready", "sent", "clientMinimum"))):
            issues.append("Invalid full-view observation chronology/counts")
            break
        previous_time = point["nanos"]
        safe_observations.append(point)
        if all(point[key] == EXPECTED_CUBES for key in ("ready", "sent", "clientMinimum")):
            first_complete = point["nanos"]
            break
        last_incomplete = point["nanos"]
    if upper and (first_complete != upper or last_incomplete != lower):
        issues.append("Endpoint bounds disagree with first complete / last incomplete observations")
    return {"passed": not issues, "issues": issues, "geometry": geometry,
            "expectedCubes": EXPECTED_CUBES, "lowerSeconds": lower / 1e9 if type(lower) is int else None,
            "upperSeconds": upper / 1e9 if type(upper) is int and upper > 0 else None,
            "clientResident": client.get("resident"), "serverReady": server.get("serverReady"),
            "serverSent": server.get("serverSent"), "observations": safe_observations,
            "scope": "Data-residency endpoint: after last incomplete and by first complete observation; not mesh, GPU, or presentation readiness",
            "meshUploadDrawCompletionMeasured": client.get("meshUploadDrawCompletionMeasured", False)}


def jvm_tuning(arguments, instance=None):
    tuning = []
    for argument in arguments:
        if argument.startswith("-XX:StartFlightRecording="):
            options = argument.partition("=")[2].split(",")
            options = [option for option in options if not option.startswith(("filename=", "name="))]
            tuning.append("-XX:StartFlightRecording=" + ",".join(options))
        elif argument.startswith("-XX:FlightRecorderOptions="):
            options = argument.partition("=")[2].split(",")
            normalized = []
            for option in options:
                if instance and option.startswith("repository="):
                    repository, root = Path(option.partition("=")[2]).resolve(), Path(instance).resolve()
                    if repository.is_relative_to(root):
                        option = "repository=<instance:" + root.anchor + ">/" + repository.relative_to(root).as_posix()
                normalized.append(option)
            tuning.append("-XX:FlightRecorderOptions=" + ",".join(normalized))
        elif instance and argument.startswith(("-Djava.io.tmpdir=", "-Djava.library.path=")):
            key, _, value = argument.partition("=")
            path, root = Path(value).resolve(), Path(instance).resolve()
            tuning.append(key + "=<instance:" + root.anchor + ">/" + path.relative_to(root).as_posix()
                          if path.is_relative_to(root) else argument)
        else:
            # Unknown properties and JVM flags remain strict, including instrumentation.
            tuning.append(argument)
    return tuning


def canonical_cache(name, data, ae2_owner=False, loader_owner=False, log=""):
    """Only reviewed generated fields; every other byte/setting remains significant."""
    if name == "config/cleanroom_load_timings.dat" and loader_owner:
        try:
            version, count = struct.unpack_from(">ii", data)
            if version != 4 or not 0 < count <= 256:
                return None
            offset, phases = 8, []
            for _ in range(count):
                length = struct.unpack_from(">H", data, offset)[0]; offset += 2
                phase = data[offset:offset + length].decode("ascii"); offset += length
                millis = struct.unpack_from(">i", data, offset)[0]; offset += 4
                if not re.fullmatch(r"[A-Z_]+", phase) or millis <= 0:
                    return None
                phases.append(phase)
            if offset != len(data) or len(set(phases)) != count:
                return None
            return canonical({"format": version, "startupPhases": phases}).encode(), "Cleanroom LoadingTracker v4 startup-progress duration weights only; all phase names/order retained"
        except (struct.error, UnicodeError):
            return None
    try:
        text = data.decode("utf-8-sig").replace("\r\n", "\n")
    except UnicodeError:
        return None
    if name == "config/splash.properties" and loader_owner:
        lines = text.splitlines()
        if any(line.rstrip().endswith("\\") for line in lines):
            return None
        return "\n".join(line for line in lines if not line.lstrip().startswith(("#", "!"))).encode(), "Java Properties comment lines only; every splash property retained"
    if not ae2_owner:
        return None
    if name == "config/AppliedEnergistics2/VersionChecker.cfg":
        proof = r"\[AE2 VersionChecker/INFO\](?: \[AE2:[SC]\])?: Last check was just recently\."
        if len(re.findall(proof, log)) == 1:
            normalized, count = re.subn(r"^([ \t]*S:lastCheck=)\d+[ \t]*$", r"\1<verified-recent-check>", text, flags=re.M)
            if count == 1:
                return normalized.encode(), "AE2 lastCheck timestamp only; this measured JVM log proves recent-check/no-network branch"
    if name == "config/AppliedEnergistics2/CustomRecipes.cfg":
        proof = r"\[AE2 CSV Export/INFO\](?: \[AE2:[SC]\])?: Same mod configuration was found\. Not updating CSV content\."
        if len(re.findall(proof, log)) == 1 and "B:enableCache=true" in text and "B:enableForceRefresh=false" in text:
            normalized, count = re.subn(r"^([ \t]*S:digest=)[0-9a-f]{32}[ \t]*$", r"\1<verified-export-cache-hit>", text, flags=re.M)
            if count == 1:
                return normalized.encode(), "AE2 ordered-mod-list export-cache digest only; measured JVM log proves cache hit; all settings retained"
    if name == "config/AppliedEnergistics2/items.csv":
        lines = text.splitlines()
        if lines and lines[0].startswith("Mod:Item:MetaData, Localized Name"):
            return "\n".join([lines[0], *sorted(lines[1:])]).encode(), "AE2 generated item-name CSV row order only; header and full row multiset including duplicates retained"
    return None


def verified_input_bytes(manifest, result, result_path, side, name):
    expected = result.get("inputHashesBeforeLaunch", {}).get(side, {}).get(name)
    if not expected:
        return None, None
    snapshot = result.get("preLaunchInputSnapshots", {})
    record = snapshot.get("files", {}).get(side, {}).get(name) if isinstance(snapshot, dict) and snapshot.get("schema") == 1 else None
    if isinstance(record, dict) and isinstance(record.get("path"), str):
        root = result_path.parent.resolve()
        path = (root / record.get("path", "")).resolve()
        if (path.is_relative_to(root) and path.is_file() and record.get("sha256") == expected):
            data = path.read_bytes()
            if len(data) == record.get("bytes") and hashlib.sha256(data).hexdigest() == expected:
                return data, {"snapshot": str(path)}
    if manifest.get(side):
        path = Path(manifest[side]) / name
        if path.is_file():
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() == expected:
                return data, {"file": str(path)}
    if side == "client" and manifest.get("prismImport"):
        archive = Path(manifest["prismImport"])
        if archive.is_file():
            try:
                with zipfile.ZipFile(archive) as saved:
                    data = saved.read("minecraft/" + name)
                if hashlib.sha256(data).hexdigest() == expected:
                    return data, {"archive": str(archive), "entry": "minecraft/" + name}
            except (OSError, zipfile.BadZipFile, KeyError):
                pass
    return None, None


def parse_cube_config(data):
    """Strict Forge scalar/list syntax; unknown syntax remains an unmatched raw hash."""
    settings, categories, pending = {}, [], None
    for raw in data.decode("utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if pending:
            key, values = pending
            if line == ">":
                settings[key] = values; pending = None
            else:
                values.append(line)
            continue
        if line == "}":
            if not categories:
                raise ChartDataError("Unbalanced cube config category")
            categories.pop(); continue
        category = re.fullmatch(r"([A-Za-z0-9_.-]+)\s*\{", line)
        if category:
            categories.append(category.group(1)); continue
        field = re.fullmatch(r"([BIDS]):([A-Za-z0-9_.-]+)(=|\s+<)(.*)", line)
        if not field or not categories:
            raise ChartDataError("Unsupported cube config syntax")
        kind, name, separator, value = field.groups()
        key = ".".join(categories) + "." + kind + ":" + name
        if key in settings:
            raise ChartDataError("Duplicate cube config key")
        if separator != "=":
            if value:
                raise ChartDataError("Unexpected cube config list opening")
            pending = (key, []); continue
        if kind == "B":
            if value not in ("true", "false"):
                raise ChartDataError("Invalid cube config boolean")
            value = value == "true"
        elif kind == "I":
            if not re.fullmatch(r"-?\d+", value):
                raise ChartDataError("Invalid cube config integer")
            value = int(value)
        settings[key] = value
    if categories or pending:
        raise ChartDataError("Unclosed cube config category/list")
    return settings


def selected_jvm_flag(argument):
    return argument in JVM_GC_FLAGS + ("-XX:+AlwaysPreTouch", "-XX:-AlwaysPreTouch") or bool(re.fullmatch(r"-Xms\d+[kKmMgG]?", argument))


def jvm_treatment_values(arguments):
    values = {"initialHeapBytes": None, "collector": None, "pretouch": False}
    for argument in arguments:
        size = re.fullmatch(r"-Xms(\d+)([kKmMgG]?)", argument)
        if size:
            values["initialHeapBytes"] = int(size.group(1)) * (1024 ** {"": 0, "k": 1, "m": 2, "g": 3}[size.group(2).lower()])
        elif argument in JVM_GC_FLAGS:
            values["collector"] = "G1" if argument == JVM_GC_FLAGS[0] else "ZGC"
        elif argument in ("-XX:+AlwaysPreTouch", "-XX:-AlwaysPreTouch"):
            values["pretouch"] = argument == "-XX:+AlwaysPreTouch"
    return values


def canonical_prepared_input(name, data, manifest, treatment):
    if name not in ("config/cubicchunks.cfg", "server.properties", "user_jvm_args.txt"):
        return None
    text = data.decode("utf-8-sig").replace("\r\n", "\n")
    if name == "config/cubicchunks.cfg":
        settings = parse_cube_config(data)
        # Old main lacks only these reviewed defaults for its disabled maintenance experiment.
        if settings.get("general.B:incrementalMaintenance") is False:
            for key, value in (("maintenanceInspectionsPerTick", 256), ("maintenanceTimeMillis", 1), ("maintenanceUnloadsPerTick", 8)):
                settings.setdefault("general.I:" + key, value)
        values = {key: settings[key] for key in PACING_KEYS if key in settings}
        maintenance = {key: settings[key] for key in MAINTENANCE_KEYS if key in settings}
        if treatment == "cc-pacing":
            if len(values) != len(PACING_KEYS) or any(type(v) is not int or not 0 <= v <= 2147483647 for v in values.values()):
                raise ChartDataError("Pacing treatment requires exactly the reviewed nonnegative integer keys")
            controls = {key: value for key, value in settings.items() if key not in PACING_KEYS}
        elif treatment == "cc-maintenance":
            if (len(maintenance) != len(MAINTENANCE_KEYS) or
                    type(maintenance["general.B:incrementalMaintenance"]) is not bool or
                    any(type(maintenance[key]) is not int or not limits[0] <= maintenance[key] <= limits[1]
                        for key, limits in MAINTENANCE_KEYS.items() if limits is not None)):
                raise ChartDataError("Maintenance treatment requires its reviewed boolean and in-range integer limits")
            controls = {key: value for key, value in settings.items() if key not in MAINTENANCE_KEYS}
        else:
            controls = settings
        return "cube-config-v1:", controls, {"parsedSettings": settings, "pacingValues": values,
            "maintenanceValues": maintenance,
            "rule": "All parsed cube config fields retained; only declared pacing/maintenance keys vary in their own treatment; disabled missing maintenance fields use reviewed defaults"}
    if name == "server.properties":
        properties = {}
        for line in text.splitlines():
            if not line.strip() or line.lstrip().startswith(("#", "!")):
                continue
            field = re.fullmatch(r"([^=:\s]+)=(.*)", line)
            if not field or line.endswith("\\") or field.group(1) in properties:
                raise ChartDataError("Unsupported/ambiguous server properties syntax")
            properties[field.group(1)] = field.group(2)
        if properties.get("server-port") != str(manifest.get("port")):
            raise ChartDataError("Server port does not match prepared instance")
        controls = {key: value for key, value in properties.items() if key != "server-port"}
        return "server-properties-v1:", controls, {"parsedSettings": properties,
            "rule": "Exact verified properties; comment lines and manifest-verified instance port only are non-workload metadata"}
    if name == "user_jvm_args.txt":
        arguments = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
        if any(not value.startswith("-") or any(c.isspace() for c in value) for value in arguments):
            raise ChartDataError("Unsupported JVM argfile syntax")
        controls = [value for value in arguments if not (treatment == "jvm" and selected_jvm_flag(value))]
        return "jvm-argfile-v1:", controls, {"arguments": arguments,
            "rule": "Verified JVM argfile; only selected collector/Xms/pretouch flags vary in jvm treatment"}
    return None


def normalized_input_hashes(manifest, result, result_path, treatment="cc-source"):
    hashes = {side: dict(values) for side, values in result.get("inputHashesBeforeLaunch", {}).items()}
    records = []
    names = ("config/AppliedEnergistics2/VersionChecker.cfg", "config/AppliedEnergistics2/CustomRecipes.cfg",
             "config/AppliedEnergistics2/items.csv", "config/splash.properties", "config/cleanroom_load_timings.dat",
             "config/cubicchunks.cfg", "server.properties", "user_jvm_args.txt")
    for side in ("server", "client"):
        mods = manifest.get("modHashes" if side == "server" else "clientModHashes", {})
        ae2_owner = mods.get("ae2-uel-v0.56.6.jar") == AE2_EXPORT_OWNER
        loader_sha = manifest.get("runtimeArtifacts", {}).get(side, {}).get("sha256")
        loader_owner = loader_sha in CLEANROOM_LOADING_OWNERS
        log_path = result_path.parent / (side + ".log")
        log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
        for name in names:
            expected = hashes.get(side, {}).get(name)
            if expected is None:
                continue
            data, source = verified_input_bytes(manifest, result, result_path, side, name)
            if data is None:
                continue  # Never infer pre-launch semantics from changed post-run bytes.
            try:
                prepared = canonical_prepared_input(name, data, manifest, treatment)
            except (ChartDataError, UnicodeError) as error:
                records.append({"side": side, "name": name, "rawSha256": expected, "issue": str(error)})
                continue
            if prepared is not None:
                prefix, controls, details = prepared
                semantic_hash = hashlib.sha256(canonical(controls).encode()).hexdigest()
                hashes[side][name] = prefix + semantic_hash
                records.append({"side": side, "name": name, "rawSha256": expected,
                    "canonicalSha256": semantic_hash, "hashVerifiedSource": source, **details})
                continue
            normalized = canonical_cache(name, data, ae2_owner, loader_owner, log)
            if normalized is None:
                continue
            value, rule = normalized
            semantic_hash = hashlib.sha256(value).hexdigest()
            hashes[side][name] = "semantic-v1:" + semantic_hash
            records.append({"side": side, "name": name, "rawSha256": expected,
                "canonicalSha256": semantic_hash, "rule": rule, "hashVerifiedSource": source,
                "ae2OwnerSha256": AE2_EXPORT_OWNER if ae2_owner else None,
                "loaderOwnerSha256": loader_sha if loader_owner else None,
                "measuredJvmLog": str(log_path) if "JVM log" in rule else None,
                "measuredJvmLogSha256": digest(log_path) if "JVM log" in rule else None})
    return hashes, records


def riven_source_proof(manifest):
    """Only the named prepared JAR may vary; all resource entry bytes stay controls."""
    proof = {"verified": False, "preparedJars": {}, "resourceEntrySha256": {}, "issues": []}
    expected = (manifest.get("riven") or {}).get("sha256")
    settings = (manifest.get("riven") or {}).get("generatorSettings") or {}
    if not isinstance(settings.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", settings["sha256"]):
        proof["issues"].append("Riven-source requires an exact generator-settings fingerprint")
    try:
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ChartDataError("Riven-source requires an exact Riven artifact SHA-256")
        data = None
        for side, field in (("server", "modHashes"), ("client", "clientModHashes")):
            if manifest.get(field, {}).get("riven.jar") != expected or not manifest.get(side):
                raise ChartDataError("Riven-source requires the recorded riven.jar on both sides")
            path = (Path(manifest[side]) / "mods" / "riven.jar").resolve()
            current = path.read_bytes()
            if hashlib.sha256(current).hexdigest() != expected:
                raise ChartDataError("Prepared " + side + " riven.jar differs from recorded SHA-256")
            proof["preparedJars"][side] = {"path": str(path), "sha256": expected, "bytes": len(current)}
            data = current
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = set()
            for entry in archive.infolist():
                name = entry.filename
                if (name in names or "\\" in name or name.startswith("/") or
                        any(part in (".", "..") for part in name.split("/"))):
                    raise ChartDataError("Ambiguous or unsafe Riven JAR entry")
                names.add(name)
                if not entry.is_dir() and not name.endswith(".class"):
                    proof["resourceEntrySha256"][name] = hashlib.sha256(archive.read(entry)).hexdigest()
        if not proof["resourceEntrySha256"]:
            raise ChartDataError("Riven-source requires exact non-class resource entries")
    except (OSError, zipfile.BadZipFile, ChartDataError, RuntimeError, NotImplementedError) as error:
        proof["issues"].append(str(error))
    proof["verified"] = not proof["issues"]
    return proof


def controls_for(manifest, row, result, scenario_index, result_path, input_hashes=None, treatment="cc-source", normalization=(), source_proof=None):
    """Strict strata with exactly one declared treatment axis."""
    issues = []
    guard = result.get('hostWorkloadGuard')
    if guard is not None and (not isinstance(guard, dict) or guard.get('schema') != 'cubiclab.java-workload-guard.v1' or guard.get('passed') is not True or guard.get('issues') or guard.get('closed') is not True or not guard.get('observations')):
        issues.append('Java workload guard failed or did not close; native timing is excluded')
    if manifest.get('benchmarkWorkload'):
        from lab_workload import validate_capture
        try: validate_capture(manifest,row.get('server',{}),row.get('client',{}))
        except RuntimeError as error: issues.append(str(error))
        for side in ['server','client']:
            data,_=verified_input_bytes(manifest,result,result_path,side,'config/cubiclab-workload.json')
            if data is None or hashlib.sha256(data).hexdigest()!=manifest['benchmarkWorkload'].get('sha256'):
                issues.append('Missing exact '+side+' workload input proof')
    cubic = manifest.get("cubicJar", {})
    if not cubic.get("filename") or not cubic.get("sha256"):
        issues.append("Missing exact CubicChunks binary identity")
    if treatment == "riven-source":
        issues.extend((source_proof or {}).get("issues") or ([] if (source_proof or {}).get("verified") else ["Missing Riven resource proof"]))
    content_mods = {}
    for field in ("modHashes", "clientModHashes"):
        hashes = manifest.get(field)
        if not hashes:
            issues.append(f"Missing manifest {field}")
        content_mods[field] = {name: value for name, value in (hashes or {}).items()
                               if name != cubic.get("filename") and not (treatment == "riven-source" and name == "riven.jar" and (source_proof or {}).get("verified"))}
    runtimes = {}
    for side in ("server", "client"):
        runtime = row.get(side, {}).get("runtime", {})
        for name in ("java", "maxHeapBytes", "garbageCollectors", "processors", "os", "arch"):
            if name not in runtime:
                issues.append(f"Missing {side} runtime {name}")
        runtimes[side] = {name: runtime.get(name) for name in
                          ("java", "maxHeapBytes", "garbageCollectors", "processors", "os", "arch")}
        if not re.match(r"^26(?:[.+-]|$)", str(runtime.get("java", ""))):
            issues.append(f"{side} runtime is not a Java 26 qualification")
        # Recording filenames vary; retain every non-path JFR option and JVM tuning flag.
        runtimes[side]["jvmTuning"] = jvm_tuning(runtime.get("jvmArguments", []), manifest.get(side))
        expected_heap = manifest.get("jvmProfile", {}).get("maxHeapMiB")
        if type(expected_heap) is int and runtime.get("maxHeapBytes") != expected_heap * 1024 ** 2:
            issues.append(f"{side} heap differs from prepared JVM profile")
        if treatment == "jvm":
            declared_profile = manifest.get("jvmProfile", {})
            collector = declared_profile.get("collector")
            observed = runtime.get("garbageCollectors", [])
            if collector not in ("G1", "ZGC") or not observed or not all(str(name).startswith(collector) for name in observed):
                issues.append(f"{side} collector is outside the declared reviewed JVM treatment")
            declared_flags = jvm_treatment_values(declared_profile.get("arguments", []))
            initial = declared_profile.get("initialHeapMiB")
            if (type(initial) is not int or initial <= 0 or
                    declared_flags["initialHeapBytes"] != initial * 1024 ** 2 or
                    declared_flags["collector"] != collector or
                    jvm_treatment_values(runtime.get("jvmArguments", [])) != declared_flags):
                issues.append(f"{side} selected JVM flags differ from declared profile")
            runtimes[side]["garbageCollectors"] = "<declared JVM treatment>"
            runtimes[side]["jvmTuning"] = [flag for flag in runtimes[side]["jvmTuning"] if not selected_jvm_flag(flag)]
        if treatment in PROPERTY_TREATMENTS:
            key, allowed = PROPERTY_TREATMENTS[treatment]
            declared = manifest.get("labProperties", {}).get(key)
            prefix = "-D" + key + "="
            observed = [flag[len(prefix):] for flag in runtime.get("jvmArguments", []) if flag.startswith(prefix)]
            if declared not in allowed or observed != [declared]:
                issues.append(f"{side} {key} must be one actual flag matching its declared reviewed value")
            runtimes[side]["jvmTuning"] = [flag for flag in runtimes[side]["jvmTuning"] if not flag.startswith(prefix)]
    client = row.get("client", {})
    render_fields = ("width", "height", "frameCap", "vsync", "fancyGraphics", "fullscreen",
                     "glVendor", "glRenderer", "glVersion")
    for name in render_fields:
        if name not in client:
            issues.append(f"Missing client render control {name}")
    actual_distances = {"horizontal": row.get("server", {}).get("horizontalViewDistance"),
        "vertical": row.get("server", {}).get("verticalViewDistance"),
        "clientRender": client.get("renderDistance"), "clientVertical": client.get("verticalViewDistance")}
    if any(value != 12 for value in actual_distances.values()):
        issues.append("Effective server/client horizontal/vertical distances must all be 12")
    for name in ("runtimeVersion", "labJar", "height", "jvmProfile"):
        if not manifest.get(name):
            issues.append(f"Missing manifest {name}")
    properties = manifest.get("labProperties", {})
    camera = {key: properties.get("cubiclab.view" + key.upper()) for key in ("x", "y", "z")}
    if any(value is None for value in camera.values()):
        issues.append("Missing prepared full-view camera")
    else:
        try:
            camera = {key: float(value) for key, value in camera.items()}
            if not all(math.isfinite(value) for value in camera.values()):
                raise ValueError()
        except (TypeError, ValueError):
            issues.append("Invalid prepared full-view camera")
    riven = manifest.get("riven") or {}
    profile = dict(manifest.get("jvmProfile") or {})
    prepared = {(record["side"], record["name"]): record for record in normalization}
    server_cube = prepared.get(("server", "config/cubicchunks.cfg"), {}).get("parsedSettings", {})
    experiments = manifest.get("cubeExperiments") or {}
    for name, value in experiments.items():
        key = "general." + ("B:" if name == "incrementalMaintenance" else "I:") + name
        actual = server_cube.get(key)
        actual_text = str(actual).lower() if type(actual) is bool else str(actual)
        if actual is None or str(value) != actual_text:
            issues.append("Declared cube experiment differs from exact server config: " + name)
    if treatment == "jvm":
        argfile = prepared.get(("server", "user_jvm_args.txt"), {}).get("arguments")
        if argfile != profile.get("arguments"):
            issues.append("Verified server JVM argfile differs from declared profile")
    if treatment == "jvm":
        profile.pop("initialHeapMiB", None); profile.pop("collector", None)
        profile["arguments"] = [flag for flag in profile.get("arguments", []) if not selected_jvm_flag(flag)]
    hashes = input_hashes if input_hashes is not None else result.get("inputHashesBeforeLaunch", {})
    for side in ("server", "client"):
        if not str(hashes.get(side, {}).get("config/cubicchunks.cfg", "")).startswith("cube-config-v1:"):
            issues.append(f"Missing exact verified {side} cubicchunks.cfg semantics")
    if not str(hashes.get("server", {}).get("server.properties", "")).startswith("server-properties-v1:"):
        issues.append("Missing exact pre-launch server.properties proof; old changed bytes cannot establish controls")
    if treatment == "jvm" and not str(hashes.get("server", {}).get("user_jvm_args.txt", "")).startswith("jvm-argfile-v1:"):
        issues.append("Missing exact verified server JVM argfile")
    effective_height = {name: row.get("server", {}).get(name)
                        for name in ("minY", "maxYExclusive", "verticalCubesPerColumn")}
    if effective_height != manifest.get("height"):
        issues.append("Effective allocated height differs from prepared height")
    controls = {"treatmentAxis": treatment, "cubicSha256": None if treatment == "cc-source" else cubic.get("sha256"),
        "benchmarkWorkload": {k: manifest['benchmarkWorkload'][k] for k in ['schema','id','seed','sha256']} if manifest.get('benchmarkWorkload') else None,
        "phase": result.get("phase"), "workload": row.get("scenario"),
        "scenarioIndex": scenario_index, "scenarioSequence": result.get('scenarioSequence',[r.get("scenario") for r in result.get("scenarios", [])]),
        "operations": row.get("server", {}).get("operations"),
        "participantCount": len(row.get("server", {}).get("participants", [])),
        "runtimeVersion": manifest.get("runtimeVersion"), "runtimeArtifacts": {
            side: manifest.get("runtimeArtifacts", {}).get(side, {}).get("sha256") for side in ("server", "client")},
        "clientLibraries": [{key: library.get(key) for key in ("name", "sha1")}
                            for library in manifest.get("clientRuntime", {}).get("libraries", [])],
        "javaArtifacts": {str(artifact.get("path", "")).replace("\\", "/").rsplit("/", 1)[-1]: artifact.get("sha256")
                          for artifact in manifest.get("javaRuntime", {}).get("criticalArtifacts", [])},
        "runtime": runtimes, "jvmProfile": profile, "height": manifest.get("height"),
        "cubeExperimentDeclarations": {key: value for key, value in experiments.items()
            if not ((treatment == "cc-pacing" and "general.I:" + key in PACING_KEYS) or
                    (treatment == "cc-maintenance" and any(full.rsplit(":", 1)[1] == key for full in MAINTENANCE_KEYS)))},
        "distances": actual_distances, "camera": camera,
        "render": {key: client.get(key) for key in render_fields},
        "content": content_mods, "rivenSha256": None if treatment == "riven-source" and (source_proof or {}).get("verified") else riven.get("sha256"),
        "rivenResourceEntrySha256": (source_proof or {}).get("resourceEntrySha256") if treatment == "riven-source" else None,
        "generatorSettingsSha256": (riven.get("generatorSettings") or {}).get("sha256"),
        "sharedScriptHashes": manifest.get("sharedScriptHashes"), "labSha256": manifest.get("labJar", {}).get("sha256"),
        "nonExperimentalInputHashes": hashes,
        "measurementModes": {side: row.get(side, {}).get("measurementMode") for side in ("server", "client")},
        "labInstrumentation": {key: value for key, value in properties.items()
                               if key not in ("cubiclab.viewX", "cubiclab.viewY", "cubiclab.viewZ") and
                               not (treatment in PROPERTY_TREATMENTS and key == PROPERTY_TREATMENTS[treatment][0])},
        "networkReadTimeoutSeconds": manifest.get("networkReadTimeoutSeconds"),
        "hostWorkloadObserver": {"schema":guard.get("schema"), "policy":guard.get("policy")} if isinstance(guard, dict) else None,
        "jfr": result.get("jfr"), "serverJfrSettings": result.get("serverJfrSettings", {}).get("sha256"),
        "streamTicks": properties.get("cubiclab.streamTicks"),
        "fullViewTimeoutSeconds": properties.get("cubiclab.fullViewTimeoutSeconds")}
    if not controls["phase"] or any(value is None for value in controls["runtimeArtifacts"].values()):
        issues.append("Missing phase or exact loader runtime hash")
    if issues:
        # Unknown controls never collapse into a deceptively matched group.
        controls["uncontrolledInput"] = str(result_path)
    return controls, issues


def load_run(label, result_path, metrics=DEFAULT_METRICS, qa_path=None, treatment="cc-source"):
    if treatment not in TREATMENTS:
        raise ChartDataError("Unsupported declared treatment: " + treatment)
    result_path = local_absolute_path(result_path)
    result = read_json(result_path)
    if (type(result.get("schema")) is not int or result["schema"] != 1 or
            not isinstance(result.get("scenarios"), list)):
        raise ChartDataError("Native result must have schema 1 and a scenarios list")
    ids = set()
    for row in result["scenarios"]:
        if (not isinstance(row, dict) or not isinstance(row.get("scenario"), str) or
                not re.fullmatch(r"[A-Za-z0-9_-]+", row["scenario"]) or not isinstance(row.get("runId"), str) or
                not re.fullmatch(r"[A-Za-z0-9_-]+", row["runId"]) or row["runId"] in ids):
            raise ChartDataError("Scenarios need safe unique runIds and workload names")
        ids.add(row["runId"])
        for side in ("server", "client"):
            capture = row.get(side)
            if (not isinstance(capture, dict) or capture.get("schema") != "cubiclab." + side + ".v1" or
                    capture.get("runId") != row["runId"] or capture.get("scenario") != row["scenario"]):
                raise ChartDataError(f"Unsupported or mismatched {side} capture schema/identity")
    manifest_path, manifest = manifest_for(result_path)
    if qa_path is None:
        possible = result_path.parent / "visual-qa.json"
        qa_path = possible if possible.is_file() else None
    qa = visual_qa(qa_path)
    input_hashes, input_normalization = normalized_input_hashes(manifest, result, result_path, treatment)
    source_proof = riven_source_proof(manifest) if treatment == "riven-source" else None
    full_views = []
    for row in result["scenarios"]:
        if row.get("scenario") in FULL_VIEW_SCENARIOS:
            gate = full_view_gate(row)
            gate.update(scenario=row["scenario"], runId=row.get("runId"))
            review = qa["scenarios"].get(row["scenario"])
            gate["visualStatus"] = "passed" if review and review["passed"] else "failed" if review else "unreviewed"
            full_views.append(gate)
    gate_issues = []
    if not full_views or not all(gate["passed"] for gate in full_views):
        gate_issues.append("No complete same-target 15,625-cube full-view data gate")
    if not full_views or not all(gate["visualStatus"] == "passed" for gate in full_views):
        gate_issues.append("Full-view terrain/camera visual QA failed or is unreviewed")
    if result.get("passed") is not True:
        gate_issues.append("Native run failed: " + str(result.get("failure", "passed is not true")))
    captures = []
    for index, row in enumerate(result["scenarios"]):
        controls, issues = controls_for(manifest, row, result, index, result_path, input_hashes, treatment, input_normalization, source_proof)
        issues = issues + gate_issues
        if row.get("server", {}).get("passed") is not True:
            issues.append("Server scenario did not pass")
        if row.get("client", {}).get("framesWithGui", 0):
            issues.append("A GUI covered captured frames")
        review = qa["scenarios"].get(row.get("scenario"))
        if review and not review["passed"]:
            issues.append("Workload visual QA failed: " + review["scope"])
        for gate in full_views:
            geometry = gate.get("geometry") or {}
            if {key: geometry.get(key) for key in ("x", "y", "z")} != controls["camera"]:
                issues.append("Observed full-view camera differs from prepared camera")
        capture = {"label": label, "scenario": row.get("scenario"), "runId": row.get("runId"),
            "treatment": treatment, "declaredTreatmentValues": {
                "cubicJar": manifest.get("cubicJar"), "cubeExperiments": manifest.get("cubeExperiments"),
                "pacing": {r["side"]: r.get("pacingValues") for r in input_normalization if "pacingValues" in r},
                "maintenance": {r["side"]: r.get("maintenanceValues") for r in input_normalization if "maintenanceValues" in r},
                "jvmProfile": manifest.get("jvmProfile"),
                "riven": manifest.get("riven"), "labProperties": manifest.get("labProperties"),
                "selectedJvmFlags": {side: jvm_treatment_values(row.get(side, {}).get("runtime", {}).get("jvmArguments", []))
                                     for side in ("server", "client")}},
            "observedRuntime": {side: row.get(side, {}).get("runtime") for side in ("server", "client")},
            "phase": result.get("phase"), "controls": controls,
            "controlGroup": hashlib.sha256(canonical(controls).encode()).hexdigest()[:12],
            "candidate": {"cubicJar": manifest.get("cubicJar"), "cubeExperiments": manifest.get("cubeExperiments"),
                          "uploadStrategy": manifest.get("labProperties", {}).get("cubiclab.uploadStrategy", "original")},
            "eligibleComparator": not issues, "issues": issues, "metrics": [],
            "fullView": next((gate for gate in full_views if gate["runId"] == row.get("runId")), None)}
        for metric_path in metrics:
            if metric_path == "server.autoTicks":
                metric_path = "server.completeServerTicks" if "completeServerTicks" in row.get("server", {}) else "server.serverTicks"
            side, separator, metric_name = metric_path.partition(".")
            if not separator or not re.fullmatch(r"(?:server|client)\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", metric_path):
                raise ChartDataError(f"Metric must start with server. or client.: {metric_path}")
            capture["metrics"].append(metric_from_raw(result_path, row.get("runId", ""), side, metric_name,
                lookup(row, metric_path), row.get(side, {}).get("elapsedNanos", 0)))
        captures.append(capture)
    return {"label": label, "resultPath": str(result_path), "resultSha256": digest(result_path),
        "treatment": treatment,
        "passed": result.get("passed") is True, "failure": result.get("failure"), "phase": result.get("phase"),
        "manifestPath": str(manifest_path) if manifest_path else None,
        "manifestSha256": digest(manifest_path) if manifest_path else None,
        "rawInputHashesBeforeLaunch": result.get("inputHashesBeforeLaunch", {}),
        "rawModHashes": {field: manifest.get(field) for field in ("modHashes", "clientModHashes")},
        "preLaunchInputSnapshots": result.get("preLaunchInputSnapshots"),
        "rivenSourceProof": source_proof,
        "inputCanonicalization": input_normalization,
        "visualQa": qa, "fullViews": full_views, "gateIssues": gate_issues, "captures": captures}


def serializable_dataset(runs):
    # Raw observations live in the CSV, not duplicated into an enormous JSON array.
    return {"schema": 1, "createdUtc": datetime.now(timezone.utc).isoformat(),
        "scope": "Explicit native captures, separate per-run quantiles and exact control strata; no pooled quantiles or acceptance claim",
        "quantileMethod": QUANTILE_METHOD, "quantilesRequireCompleteRawCapture": True,
        "fullViewScope": "15,625-cube data residency only; separate from mesh/GPU/presentation readiness",
        "comparisonVariables": "One explicit treatment axis only: " + ", ".join(TREATMENTS) + ". Remaining verified controls are strict. This is not an accepted-gain claim.",
        "visualQaOfGeneratedCharts": "pending human or image review after rendering",
        "runs": [{**run, "captures": [{**capture, "metrics": [
            {key: value for key, value in metric.items() if key != "samples"}
            for metric in capture["metrics"]]} for capture in run.get("captures", [])]} for run in runs]}


def write_data(output, runs):
    (output / "data.json").write_text(json.dumps(serializable_dataset(runs), indent=2, allow_nan=False) + "\n", encoding="utf-8")
    fields = ["label", "phase", "scenario", "runId", "treatment", "declaredTreatmentValues", "controlGroup", "eligibleComparator", "metric", "count",
              "p95Ms", "p99Ms", "maxMs", "above50ms", "above100ms", "above250ms", "above1000ms",
              "excessOver50msSeconds", "excessOver50msSecondsPerCaptureMinute", "issues"]
    with (output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
        for run in runs:
            for capture in run.get("captures", []):
                for metric in capture["metrics"]:
                    stats = metric["statistics"] or {}
                    record = {key: capture.get(key) for key in fields if key in capture}
                    record.update({key: stats.get(key) for key in fields if key in stats})
                    record.update(metric=metric["side"] + "." + metric["name"],
                        declaredTreatmentValues=canonical(capture["declaredTreatmentValues"]),
                        eligibleComparator=capture["eligibleComparator"] and metric["validWholeCapture"],
                        issues="; ".join(capture["issues"] + metric["issues"]))
                    for ms in (50, 100, 250, 1000):
                        record["above" + str(ms) + "ms"] = stats.get("exceedanceCountsAboveNanos", {}).get(str(ms * 1_000_000))
                    writer.writerow(record)
    with (output / "observations.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["label", "phase", "scenario", "runId", "side", "metric", "wholeCaptureValid",
                         "ordinal", "observationEndOffsetNanos", "durationNanos"])
        for run in runs:
            for capture in run.get("captures", []):
                for metric in capture["metrics"]:
                    for sample in metric["samples"]:
                        writer.writerow([capture[key] for key in ("label", "phase", "scenario", "runId")] +
                                        [metric["side"], metric["name"], metric["validWholeCapture"], *sample])


def render(output, runs):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import MaxNLocator

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.spines.top": False,
        "axes.spines.right": False, "axes.grid": True, "grid.alpha": .18, "figure.facecolor": "white",
        "savefig.facecolor": "white", "svg.fonttype": "none"})
    palette = ("#2166ac", "#d66000", "#1b8174", "#7b4ba1", "#bd3445", "#59646f")
    groups = defaultdict(list)
    for run in runs:
        for capture in run.get("captures", []):
            groups[capture["controlGroup"]].append(capture)
    files = []

    def save(figure, name):
        for extension in ("png", "svg"):
            path = output / (name + "." + extension)
            figure.savefig(path, dpi=180, bbox_inches="tight")
            files.append(path.name)
        plt.close(figure)

    # A failed or schema-invalid run cannot disappear simply because it has no samples.
    figure, axis = plt.subplots(figsize=(13, max(2.4, 1.0 + .65 * len(runs))))
    axis.axis("off")
    rows = [[run["label"], "PASS" if run.get("passed") else "FAILED", str(run.get("phase", "unknown")),
             textwrap.fill("; ".join(run.get("gateIssues", [])) or
                           "Data + terrain visual gates passed; review individual metrics", width=105)]
            for run in runs]
    table = axis.table(cellText=rows, colLabels=["Run", "Native", "Phase", "Comparison status"],
                       colWidths=[.15, .09, .08, .68], cellLoc="left", loc="center")
    table.auto_set_font_size(False); table.set_fontsize(8); table.scale(1, 1.9)
    axis.set_title("Retained native-run status — data gates do not prove complete presentation", loc="left", pad=12)
    save(figure, "run-status")

    for group, captures in groups.items():
        first = captures[0]
        controls = first["controls"]
        scenario = str(first["scenario"])
        title = f"{scenario} · {first['phase']} · axis {first['treatment']} · group {group} · observed, not accepted gain"
        runtime = controls["runtime"]["server"]
        height = controls.get("height") or {}
        camera = controls.get("camera") or {}
        view_context = (f"allocated [{height.get('minY')}, {height.get('maxYExclusive')}) · "
                        f"camera ({camera.get('x')}, {camera.get('y')}, {camera.get('z')})")
        subtitle = (f"Java {runtime.get('java')} / Cleanroom {controls['runtimeVersion']} · "
                    f"heap {(runtime.get('maxHeapBytes') or 0) / 1024 ** 3:g} GiB · distance 12\n" + view_context + "\n" +
                    "Each marker is one run; excluded = unmet data/control/visual gate. No pooled quantiles.")
        names = list(dict.fromkeys(metric["side"] + "." + metric["name"]
                                   for capture in captures for metric in capture["metrics"]))
        for metric_name in names:
            entries = [(capture, metric) for capture in captures for metric in capture["metrics"]
                       if metric["side"] + "." + metric["name"] == metric_name]
            figure, axes = plt.subplots(2, 2, figsize=(13.5, 8.7), constrained_layout=True)
            figure.suptitle(title + "\n" + metric_name + "\n" + subtitle, fontsize=11)
            positions = list(range(len(entries)))
            labels = []
            for index, (capture, metric) in enumerate(entries):
                color = palette[index % len(palette)]
                valid = capture["eligibleComparator"] and metric["validWholeCapture"]
                label = capture["label"] + ("" if valid else " [excluded]")
                labels.append(label)
                stats = metric["statistics"]
                if stats:
                    for offset, field, marker in ((-.18, "p95Ms", "o"), (0, "p99Ms", "s"), (.18, "maxMs", "^")):
                        axes[0, 0].scatter(index + offset, stats[field], color=color, marker=marker,
                                           facecolors=color if valid else "none", s=48)
                    durations = sorted(sample[2] / 1e6 for sample in metric["samples"])
                    # P(X > x), including a nonzero plotting position only where observations remain.
                    xs, ys = [], []
                    for ordinal, duration in enumerate(durations):
                        if ordinal + 1 < len(durations) and durations[ordinal + 1] == duration:
                            continue
                        remaining = len(durations) - ordinal - 1
                        if remaining:
                            xs.append(duration); ys.append(100 * remaining / len(durations))
                    axes[0, 1].step(xs, ys, where="post", color=color,
                                    linestyle="-" if valid else "--", label=f"{label} (n={stats['count']:,})")
                    burden = stats["exceedancesPer1000Observations"]
                    axes[1, 0].plot([50, 100, 250, 1000], [burden[str(ms * 1_000_000)] for ms in (50, 100, 250, 1000)],
                                    marker="o", color=color, linestyle="-" if valid else "--", label=label)
                    axes[1, 1].bar(index, stats["excessOver50msSecondsPerCaptureMinute"] or 0,
                                   color=color if valid else "white", edgecolor=color,
                                   hatch=None if valid else "//")
                    axes[1, 1].annotate(f"{stats['excessOver50msSeconds']:.3g} s total", (index, 0),
                                        xytext=(0, 4), textcoords="offset points", ha="center", fontsize=8, rotation=90)
                else:
                    axes[0, 0].annotate("No whole-capture\nquantile", (index, 0),
                                       xytext=(0, 8), textcoords="offset points", ha="center", fontsize=8)
            axes[0, 0].set(title="Per-run latency: circle p95 · square p99 · triangle max", ylabel="Duration (ms)",
                            xticks=positions, xticklabels=labels, ylim=(0, None))
            axes[0, 1].set(title="Empirical exceedance (separate distributions)", xlabel="Duration (ms)",
                            ylabel="Observations strictly above x (%)", yscale="log", ylim=(None, 100), xlim=(0, None))
            if axes[0, 1].get_legend_handles_labels()[0]:
                axes[0, 1].legend(fontsize=8, loc="upper right")
            axes[1, 0].set(title="Threshold exceedance burden", xlabel="Threshold (ms; log scale)",
                            ylabel="Events per 1,000 observations", xscale="log", ylim=(0, None))
            axes[1, 0].set_xticks([50, 100, 250, 1000], ["50", "100", "250", "1,000"])
            axes[1, 1].set(title="Sum of duration above 50 ms / capture minute", ylabel="Excess seconds / capture minute",
                            xticks=positions, xticklabels=labels, ylim=(0, None))
            for axis in (axes[0, 0], axes[1, 1]):
                axis.tick_params(axis="x", labelrotation=25)
            save(figure, f"{scenario}-{group}-{metric_name.replace('.', '-')}-tails")

            figure, axes = plt.subplots(len(entries), 1, figsize=(13, max(3, 2.4 * len(entries))),
                                         squeeze=False, constrained_layout=True)
            figure.suptitle(title + " · " + metric_name + "\nRelative to each JVM's own capture origin; no cross-JVM causal alignment", fontsize=11)
            for index, (capture, metric) in enumerate(entries):
                axis = axes[index, 0]
                axis.scatter([sample[1] / 1e9 for sample in metric["samples"]],
                             [sample[2] / 1e6 for sample in metric["samples"]],
                             s=5, color=palette[index % len(palette)], rasterized=True)
                axis.axhline(50, color="#666666", linestyle=":", linewidth=.8)
                axis.set(xlabel="Observation end since this capture origin (s)", ylabel="Duration (ms)",
                         title=labels[index] + (" · prefix only" if not metric["validWholeCapture"] else ""),
                         xlim=(0, None), ylim=(0, None))
            save(figure, f"{scenario}-{group}-{metric_name.replace('.', '-')}-chronology")

        full = [capture for capture in captures if capture["fullView"]]
        if full:
            figure, axes = plt.subplots(1, 2, figsize=(13, 4.9), constrained_layout=True)
            figure.suptitle(title + "\nFull-view data-residency endpoint · 15,625 cubes\n" + view_context +
                           "\nNo mesh, GPU, swap or presentation completion claim", fontsize=11)
            for index, capture in enumerate(full):
                gate = capture["fullView"]
                color = palette[index % len(palette)]
                label = capture["label"] + ("" if capture["eligibleComparator"] else " [excluded]")
                lower, upper = gate["lowerSeconds"], gate["upperSeconds"]
                if upper is not None and lower is not None and upper > lower:
                    axes[0].hlines(index, lower, upper, color=color, linewidth=5)
                    axes[0].scatter([lower, upper], [index, index], facecolors=["white", color], edgecolors=color, s=45, zorder=3)
                    axes[0].annotate(f"({lower:.3f}, {upper:.3f}] s", (upper, index),
                                     xytext=(5, 6), textcoords="offset points", fontsize=8)
                elif lower is not None:
                    axes[0].scatter([lower], [index], marker=">", color=color, s=65)
                    axes[0].annotate("incomplete; lower bound only", (lower, index),
                                     xytext=(5, 6), textcoords="offset points", fontsize=8)
                else:
                    axes[0].text(0, index, "No valid endpoint interval", fontsize=8)
                observations = gate["observations"]
                for field, style in (("ready", ":"), ("sent", "--"), ("clientMinimum", "-")):
                    axes[1].step([point["nanos"] / 1e9 for point in observations],
                                 [point[field] for point in observations], where="post", color=color,
                                 linestyle=style, label=f"{label}: {field}")
            axes[0].set(xlabel="Time since full-view start (s)", yticks=list(range(len(full))),
                        yticklabels=[c["label"] + ("" if c["eligibleComparator"] else " [excluded]") for c in full],
                        xlim=(0, None), ylim=(-.7, len(full) - .3), title="Observation interval, not an exact latency")
            axes[1].set(xlabel="Time since full-view start (s)", ylabel="Target cubes observed", xlim=(0, None),
                        ylim=(0, EXPECTED_CUBES * 1.03), title="Coverage through the complete target set")
            axes[1].yaxis.set_major_locator(MaxNLocator(integer=True)); axes[1].legend(fontsize=7)
            save(figure, f"{scenario}-{group}-data-residency")
    return files


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", action="append", required=True, help="label=absolute native result.json; repeat for each run")
    parser.add_argument("--visual-qa", action="append", default=[], help="label=absolute visual-qa.json (optional; missing stays unreviewed)")
    parser.add_argument("--metric", action="append", help="Metric path; defaults to completeServerTicks when present, historical serverTicks otherwise, plus client.frameIntervals. Distinct tick boundaries remain separately named.")
    parser.add_argument("--treatment", choices=TREATMENTS, default="cc-source",
                        help="Declared comparison axis; all remaining verified controls stay strict")
    parser.add_argument("--output", required=True, help="New absolute local directory; existing paths are refused")
    args = parser.parse_args(argv)
    try:
        inputs, reviews = labeled_paths(args.run), labeled_paths(args.visual_qa)
        if set(reviews) - set(inputs):
            raise ChartDataError("Visual-QA labels must match an explicit --run label")
        output = local_absolute_path(args.output)
        if output.exists():
            raise ChartDataError("Output already exists; choose a new local directory")
        runs = []
        for label, path in inputs.items():
            try:
                runs.append(load_run(label, path, tuple(args.metric or DEFAULT_METRICS), reviews.get(label), args.treatment))
            except ChartDataError as error:
                runs.append({"label": label, "resultPath": str(path), "passed": False,
                             "failure": str(error), "gateIssues": [str(error)], "captures": []})
        output.mkdir(parents=True, exist_ok=False)
        write_data(output, runs)
        files = render(output, runs)
        (output / "figures.json").write_text(json.dumps({"schema": 1, "files": files,
            "visualQa": "pending inspection; output generation is not visual acceptance"}, indent=2) + "\n", encoding="utf-8")
        print(output / "data.json")
        return 0 if any(run.get("captures") for run in runs) else 1
    except (ChartDataError, OSError, ImportError) as error:
        print(f"plot_tail_latency: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
