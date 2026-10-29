"""Synthetic evidence-contract tests; these are not Minecraft measurements."""
import copy
import csv
import io
import hashlib
import json
from pathlib import Path
import tempfile
import struct
import unittest
import zipfile
from contextlib import redirect_stderr

import plot_tail_latency as chart
from lab_input_snapshot import retain_prelaunch_inputs


class NativeFixture:
    def __init__(self, root, name="base", values=(1_000_000, 2_000_000, 100_000_000)):
        self.root = root / name
        self.path = self.root / "runs" / "write" / "result.json"
        self.path.parent.mkdir(parents=True)
        self.id = name + "-capture"
        self.geometry = {"x": 10112.5, "y": 128.0, "z": 4256.5, "distance": 12, "expected": 15625}
        self.height = {"minY": -128, "maxYExclusive": 384, "verticalCubesPerColumn": 32}
        runtime = {"java": "26.0.2.1", "maxHeapBytes": 8 * 1024 ** 3,
                   "garbageCollectors": ["ZGC Minor Pauses"], "processors": 32, "os": "Windows 11",
                   "arch": "amd64", "jvmArguments": ["-Xms1G", "-Xmx8G", "-XX:+UseZGC",
                   "-XX:StartFlightRecording=filename=" + name + ".jfr"]}
        self.manifest = {"schema": 1, "runtimeVersion": "0.7.8-alpha", "height": self.height,
            "runtimeArtifacts": {side: {"sha256": "loader-hash"} for side in ("server", "client")},
            "cubicJar": {"filename": name + "-cc.jar", "sha256": name + "-binary"},
            "labJar": {"filename": "lab.jar", "sha256": "same-harness"},
            "modHashes": {name + "-cc.jar": name + "-binary", "lab.jar": "same-harness", "riven.jar": "same-riven"},
            "clientModHashes": {name + "-cc.jar": name + "-binary", "lab.jar": "same-harness", "riven.jar": "same-riven"},
            "jvmProfile": {"maxHeapMiB": 8192, "initialHeapMiB": 1024, "collector": "ZGC",
                           "arguments": ["-Xms1G", "-Xmx8G", "-XX:+UseZGC"]},
            "labProperties": {"cubiclab.viewX": "10112.5", "cubiclab.viewY": "128", "cubiclab.viewZ": "4256.5",
                              "cubiclab.streamTicks": "240", "cubiclab.fullViewTimeoutSeconds": "840"},
            "riven": {"sha256": "same-riven", "generatorSettings": {"sha256": "same-settings"}},
            "sharedScriptHashes": {"content.groovy": "same-script"},
            "cubeExperiments": {"maxCubeSendTimeMillis": "0", "maxCubeSendBytesPerTick": "0",
                                "maxCubePacketBytes": "0", "deliveryMode": "BASELINE"},
            "server": str(self.root / "server"), "client": str(self.root / "client"), "port": 25620}
        common = {"runId": self.id, "scenario": "full-view", "runtime": runtime, "elapsedNanos": 3_000_000_000,
                  "measurementMode": "ordinary-profile-with-bounded-lab-counters"}
        self.row = {"scenario": "full-view", "runId": self.id,
            "server": {**copy.deepcopy(common), **self.height, "schema": "cubiclab.server.v1", "passed": True,
                       "horizontalViewDistance": 12, "verticalViewDistance": 12, "operations": 15625,
                       "participants": ["not-a-performance-control-identity"],
                       "preparationView": {"geometry": self.geometry, "expected": 15625, "serverReady": 15625,
                           "serverSent": 15625, "clients": {"one-client": 15625}, "previousIncompleteObservationNanos": 1_000_000_000,
                           "deliveredNanos": 2_000_000_000, "samples": [
                               {"nanos": 1_000_000_000, "ready": 15600, "sent": 15600, "clientMinimum": 15000},
                               {"nanos": 2_000_000_000, "ready": 15625, "sent": 15625, "clientMinimum": 15625}]}},
            "client": {**copy.deepcopy(common), "schema": "cubiclab.client.v1", "framesWithGui": 0,
                       "renderDistance": 12, "verticalViewDistance": 12, "width": 1280, "height": 720,
                       "frameCap": 120, "vsync": False, "fancyGraphics": False, "fullscreen": False,
                       "glVendor": "fixture", "glRenderer": "fixture", "glVersion": "fixture",
                       "fullView": {"geometry": self.geometry, "resident": 15625, "meshUploadDrawCompletionMeasured": False}}}
        self.result = {"schema": 1, "phase": "write", "passed": True, "jfr": True, "scenarios": [self.row]}
        self.result["inputHashesBeforeLaunch"] = {"server": {}, "client": {}}
        self.cube_config = ("general {\n S:deliveryMode=BASELINE\n B:disableLighting=false\n"
                            " I:maxCubeSendTimeMillis=0\n I:maxCubeSendBytesPerTick=0\n"
                            " I:maxCubePacketBytes=0\n I:verticalCubeLoadDistance=12\n"
                            " S:excludedDimensions <\n  -1\n >\n}\n").encode()
        for side in ("server", "client"):
            self.set_input(side, "config/cubicchunks.cfg", self.cube_config)
        self.set_input("server", "server.properties", b"#generated\nserver-port=25620\nview-distance=12\nonline-mode=false\n")
        self.set_input("server", "user_jvm_args.txt", b"-Xms1G\n-Xmx8G\n-XX:+UseZGC\n")
        self.files = {}
        for side, field in (("server", "serverTicks"), ("client", "frameIntervals")):
            filename = "recorded-" + side + "-arbitrary-label.csv"
            self.row[side][field] = self.summary(values, filename)
            path = self.path.parent / "raw" / self.id / side / filename
            path.parent.mkdir(parents=True)
            with path.open("w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["ordinal", "observationEndOffsetNanos", "durationNanos"])
                for index, value in enumerate(values):
                    writer.writerow([index, (index + 1) * 1_000_000_000, value])
            self.files[side] = path
        self.screenshot = self.root / "fixture.png"
        self.screenshot.write_bytes(b"schema fixture, not native visual evidence")
        self.qa_path = self.root / "visual-qa.json"
        self.qa = {"schema": 1, "scenarios": {"full-view": {"passed": True,
            "screenshot": str(self.screenshot), "scope": "Synthetic schema fixture; not an actual visual review"}}}
        self.save()

    @staticmethod
    def summary(values, filename):
        return {"count": len(values), "storedCount": len(values), "droppedCount": 0,
            "totalNanos": sum(values), "percentilesCoverAllObservations": True, "chronologicalCsv": filename,
            "p95Ms": chart.nearest_rank(values, ".95") / 1e6,
            "p99Ms": chart.nearest_rank(values, ".99") / 1e6, "maxMs": max(values) / 1e6,
            "exceedanceCountsAboveNanos": {str(threshold): sum(value > threshold for value in values)
                                           for threshold in chart.THRESHOLDS_NS},
            "excessOver50msNanos": sum(max(0, value - 50_000_000) for value in values)}

    def save(self):
        self.path.write_text(json.dumps(self.result), encoding="utf-8")
        (self.root / "lab.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.qa_path.write_text(json.dumps(self.qa), encoding="utf-8")

    def set_input(self, side, name, data):
        path = Path(self.manifest[side]) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.result["inputHashesBeforeLaunch"][side][name] = hashlib.sha256(data).hexdigest()

    def same_binary(self, other):
        old = self.manifest["cubicJar"]["filename"]
        self.manifest["cubicJar"] = copy.deepcopy(other.manifest["cubicJar"])
        for field in ("modHashes", "clientModHashes"):
            self.manifest[field].pop(old)
            self.manifest[field][self.manifest["cubicJar"]["filename"]] = self.manifest["cubicJar"]["sha256"]

    def configure_maintenance(self, mode="MAINTENANCE", inspections=256, unloads=64, millis=1):
        config = self.cube_config.replace(b"deliveryMode=BASELINE", ("deliveryMode=" + mode).encode())
        for name, value in (("maintenanceInspectionsPerTick", inspections), ("maintenanceUnloadsPerTick", unloads),
                            ("maintenanceTimeMillis", millis)):
            config = config.replace(b"general {", ("general {\n I:" + name + "=" + str(value)).encode())
            self.manifest["cubeExperiments"][name] = str(value)
        self.manifest["cubeExperiments"]["deliveryMode"] = mode
        self.set_input("server", "config/cubicchunks.cfg", config)

    def configure_property(self, key, value):
        self.manifest["labProperties"][key] = value
        prefix = "-D" + key + "="
        for side in ("server", "client"):
            arguments = self.row[side]["runtime"]["jvmArguments"]
            self.row[side]["runtime"]["jvmArguments"] = [arg for arg in arguments if not arg.startswith(prefix)] + [prefix + value]

    def configure_riven_jar(self, code=b"base code", resources=None):
        if resources is None:
            resources = {"mcmod.info": b"riven fixture", "riven-settings.json": b'{"schema":2}'}
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("example/Generator.class", code)
            for name, value in resources.items():
                archive.writestr(name, value)
        jar = data.getvalue(); sha = hashlib.sha256(jar).hexdigest()
        for side, field in (("server", "modHashes"), ("client", "clientModHashes")):
            path = Path(self.manifest[side]) / "mods" / "riven.jar"
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(jar)
            self.manifest[field]["riven.jar"] = sha
        self.manifest["riven"]["sha256"] = sha
        self.manifest["riven"]["generatorSettings"] = {"value": {"schema": 2},
            "sha256": hashlib.sha256(b'{"schema":2}').hexdigest()}

    def load(self, review=True, treatment="cc-source"):
        self.save()
        return chart.load_run(self.root.name, self.path, qa_path=self.qa_path if review else None, treatment=treatment)


class TailLatencyContractTest(unittest.TestCase):
    def test_java_guard_is_a_control_and_failed_guard_excludes_timings(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture=NativeFixture(Path(temporary))
            old=fixture.load()['captures'][0]
            fixture.result['hostWorkloadGuard']={'schema':'cubiclab.java-workload-guard.v1','passed':True,'closed':True,'issues':[],
                'observations':[{'java':[]}],'policy':{'intervalSeconds':5}}
            guarded=fixture.load()['captures'][0]
            self.assertTrue(guarded['eligibleComparator'])
            self.assertNotEqual(old['controlGroup'],guarded['controlGroup'])
            fixture.result['hostWorkloadGuard']['issues']=[{'kind':'foreign-java'}]
            # Even a stale passed=true cannot mask a retained contamination record.
            rejected=fixture.load()['captures'][0]
            self.assertFalse(rejected['eligibleComparator'])
            self.assertIn('Java workload guard', ';'.join(rejected['issues']))

    def test_default_prefers_complete_ticks_and_never_relabels_historical_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture=NativeFixture(Path(temporary))
            self.assertEqual('serverTicks',chart.load_run('old',fixture.path)['captures'][0]['metrics'][0]['name'])
            fixture.row['server']['completeServerTicks']=copy.deepcopy(fixture.row['server']['serverTicks']);fixture.save()
            self.assertEqual('completeServerTicks',chart.load_run('new',fixture.path)['captures'][0]['metrics'][0]['name'])
            explicit=chart.load_run('explicit',fixture.path,metrics=('server.serverTicks',))
            self.assertEqual('serverTicks',explicit['captures'][0]['metrics'][0]['name'])

    def test_workload_hash_and_actual_peer_identity_are_strict_comparison_controls(self):
        import lab_workload
        fixture=NativeFixture(self.root,'workload')
        value={'schema':1,'id':'land','seed':7,'camera':[10112.5,128,4256.5,0,28],'scenarios':{'write':['full-view']}}
        data=json.dumps(value).encode();descriptor=lab_workload.identity(value,data)
        fixture.manifest['benchmarkWorkload']=dict(descriptor,value=value)
        fixture.row['server']['worldSeed']=7
        fixture.row['client'].update(overlayObservationAvailable=True,overlayWaitRequired=True)
        for side in ['server','client']:
            fixture.row[side]['benchmarkWorkload']=descriptor.copy()
            fixture.set_input(side,'config/cubiclab-workload.json',data)
        valid=fixture.load()['captures'][0];self.assertTrue(valid['eligibleComparator'])
        fixture.row['client']['benchmarkWorkload']['sha256']='mismatch'
        self.assertFalse(fixture.load()['captures'][0]['eligibleComparator'])

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_nearest_rank_is_exact_without_interpolation_or_float_ceil_artifact(self):
        values = list(range(1, 101))
        self.assertEqual(chart.nearest_rank(values, ".07"), 7)
        self.assertEqual(chart.nearest_rank(values, ".95"), 95)
        self.assertEqual(chart.nearest_rank(values, ".99"), 99)
        self.assertEqual(chart.nearest_rank([1, 9], ".95"), 9)
        with self.assertRaises(chart.ChartDataError):
            chart.nearest_rank([], ".99")

    def test_unequal_run_sizes_remain_separate_and_never_average_run_p99(self):
        a = NativeFixture(self.root, "a", (1_000_000,) * 99 + (100_000_000,)).load()
        b = NativeFixture(self.root, "b", (1_000_000_000,)).load()
        a_stats = a["captures"][0]["metrics"][0]["statistics"]
        b_stats = b["captures"][0]["metrics"][0]["statistics"]
        self.assertEqual((a_stats["count"], a_stats["p99Ms"]), (100, 1))
        self.assertEqual((b_stats["count"], b_stats["p99Ms"]), (1, 1000))
        data = chart.serializable_dataset([a, b])
        self.assertNotIn("pooled", data)
        self.assertNotIn("statistics", data)
        self.assertNotEqual((a_stats["p99Ms"] + b_stats["p99Ms"]) / 2,
                            chart.nearest_rank([1] * 99 + [100, 1000], ".99"))

    def test_actual_csv_label_and_parent_raw_fallback_are_respected(self):
        fixture = NativeFixture(self.root)
        run = fixture.load()
        self.assertTrue(run["captures"][0]["metrics"][0]["validWholeCapture"])
        source = fixture.files["server"]
        fallback = fixture.path.parent.parent / "raw" / fixture.id / "server" / source.name
        fallback.parent.mkdir(parents=True)
        source.replace(fallback)
        metric = fixture.load()["captures"][0]["metrics"][0]
        self.assertTrue(Path(metric["rawCsv"]).samefile(fallback))
        self.assertEqual(Path(metric["rawCsv"]).name, fallback.name)
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(fallback.read_bytes())
        self.assertIn("unambiguous", fixture.load()["captures"][0]["metrics"][0]["issues"][0])

    def test_csv_path_traversal_cannot_load_unrelated_raw_data(self):
        fixture = NativeFixture(self.root)
        fixture.row["server"]["serverTicks"]["chronologicalCsv"] = "../outside.csv"
        metric = fixture.load()["captures"][0]["metrics"][0]
        self.assertFalse(metric["validWholeCapture"])
        self.assertIsNone(metric["statistics"])
        self.assertIn("without directories", metric["issues"][0])

    def test_dropped_prefix_never_becomes_whole_capture_quantile(self):
        fixture = NativeFixture(self.root)
        summary = fixture.row["server"]["serverTicks"]
        summary.update(count=9, droppedCount=6, percentilesCoverAllObservations=False,
                       p95Ms=999, p99Ms=999, maxMs=999)
        metric = fixture.load()["captures"][0]["metrics"][0]
        self.assertFalse(metric["validWholeCapture"])
        self.assertIsNone(metric["statistics"])
        self.assertEqual(len(metric["samples"]), 3)
        self.assertEqual(metric["captureReported"]["maxMs"], 999)
        self.assertIn("prefix", metric["issues"][0])

    def test_count_and_summary_corruption_reject_raw_quantile(self):
        fixture = NativeFixture(self.root)
        fixture.row["server"]["serverTicks"]["storedCount"] = 99
        self.assertIn("row count", fixture.load()["captures"][0]["metrics"][0]["issues"][0])
        fixture.row["server"]["serverTicks"]["storedCount"] = 3
        fixture.row["server"]["serverTicks"]["p99Ms"] = 99
        self.assertIn("p99Ms differs", fixture.load()["captures"][0]["metrics"][0]["issues"][0])

    def test_bad_ordinal_or_negative_duration_is_rejected(self):
        fixture = NativeFixture(self.root)
        fixture.files["server"].write_text("ordinal,observationEndOffsetNanos,durationNanos\n1,10,-1\n")
        self.assertIn("ordinal/time", fixture.load()["captures"][0]["metrics"][0]["issues"][0])

    def test_concurrent_end_timestamp_reordering_does_not_discard_complete_distribution(self):
        fixture = NativeFixture(self.root)
        fixture.files["server"].write_text("ordinal,observationEndOffsetNanos,durationNanos\n"
                                           "0,20,1000000\n1,10,2000000\n2,30,100000000\n")
        metric = fixture.load()["captures"][0]["metrics"][0]
        self.assertTrue(metric["validWholeCapture"])
        self.assertEqual(metric["statistics"]["outOfOrderEndOffsets"], 1)

    def test_exceedance_is_strict_and_excess_burden_not_event_duration_sum(self):
        fixture = NativeFixture(self.root, values=(50_000_000, 100_000_000, 250_000_001))
        stats = fixture.load()["captures"][0]["metrics"][0]["statistics"]
        self.assertEqual(stats["exceedanceCountsAboveNanos"]["50000000"], 2)
        self.assertEqual(stats["exceedanceCountsAboveNanos"]["100000000"], 1)
        self.assertAlmostEqual(stats["excessOver50msSeconds"], .250000001)
        self.assertAlmostEqual(stats["excessOver50msSecondsPerCaptureMinute"], 5.00000002)

    def test_full_view_requires_same_target_every_cube_and_interval_bounds(self):
        fixture = NativeFixture(self.root)
        gate = fixture.load()["fullViews"][0]
        self.assertEqual((gate["lowerSeconds"], gate["upperSeconds"]), (1, 2))
        self.assertTrue(gate["passed"])
        self.assertIn("not mesh", gate["scope"])
        fixture.row["client"]["fullView"]["resident"] = 15624
        self.assertFalse(fixture.load()["fullViews"][0]["passed"])
        fixture.row["client"]["fullView"]["resident"] = 15625
        fixture.row["client"]["fullView"]["geometry"] = {**fixture.geometry, "y": 288}
        self.assertFalse(fixture.load()["fullViews"][0]["passed"])
        fixture.row["client"]["fullView"]["geometry"] = fixture.geometry
        fixture.row["server"]["preparationView"]["deliveredNanos"] = 1_000_000_000
        self.assertFalse(fixture.load()["fullViews"][0]["passed"])

    def test_controls_separate_phase_heap_runtime_camera_and_harness(self):
        baseline = NativeFixture(self.root, "a")
        baseline_group = baseline.load()["captures"][0]["controlGroup"]
        mutations = [lambda f: f.result.update(phase="read"),
                     lambda f: f.row["server"]["runtime"].update(maxHeapBytes=4 * 1024 ** 3),
                     lambda f: f.row["client"]["runtime"].update(java="25.0.1"),
                     lambda f: f.manifest["labProperties"].update({"cubiclab.viewY": "288"}),
                     lambda f: f.manifest["labJar"].update(sha256="different-harness")]
        for index, mutate in enumerate(mutations):
            fixture = NativeFixture(self.root, "b" + str(index)); mutate(fixture)
            self.assertNotEqual(fixture.load()["captures"][0]["controlGroup"], baseline_group)

    def test_default_source_treatment_varies_binary_but_keeps_pacing_strict(self):
        baseline = NativeFixture(self.root, "a").load()
        candidate = NativeFixture(self.root, "b")
        candidate.row["server"]["participants"] = ["another-identity-same-peer-count"]
        candidate_run = candidate.load()
        self.assertEqual(baseline["captures"][0]["controlGroup"], candidate_run["captures"][0]["controlGroup"])
        self.assertTrue(candidate_run["captures"][0]["eligibleComparator"])
        candidate.manifest["cubeExperiments"]["maxCubeSendTimeMillis"] = "8"
        candidate.set_input("server", "config/cubicchunks.cfg", candidate.cube_config.replace(b"SendTimeMillis=0", b"SendTimeMillis=8"))
        self.assertNotEqual(baseline["captures"][0]["controlGroup"], candidate.load()["captures"][0]["controlGroup"])

    def test_pacing_treatment_varies_only_three_exact_verified_numeric_settings(self):
        baseline, candidate = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        candidate.same_binary(baseline)
        config = candidate.cube_config
        for name, value in (("maxCubeSendTimeMillis", 8), ("maxCubeSendBytesPerTick", 4194304), ("maxCubePacketBytes", 262144)):
            config = config.replace((name + "=0").encode(), (name + "=" + str(value)).encode())
            candidate.manifest["cubeExperiments"][name] = str(value)
        candidate.set_input("server", "config/cubicchunks.cfg", config)
        a, b = baseline.load(treatment="cc-pacing"), candidate.load(treatment="cc-pacing")
        self.assertEqual(a["captures"][0]["controlGroup"], b["captures"][0]["controlGroup"])
        self.assertTrue(b["captures"][0]["eligibleComparator"], b["captures"][0]["issues"])
        self.assertNotEqual(a["rawInputHashesBeforeLaunch"], b["rawInputHashesBeforeLaunch"])
        self.assertEqual(b["captures"][0]["declaredTreatmentValues"]["pacing"]["server"][chart.PACING_KEYS[0]], 8)

    def test_non_treatment_settings_remain_strict_in_pacing_mode(self):
        base = NativeFixture(self.root, "a")
        group = base.load(treatment="cc-pacing")["captures"][0]["controlGroup"]
        for index, (before, after) in enumerate(((b"disableLighting=false", b"disableLighting=true"),
                (b"deliveryMode=BASELINE", b"deliveryMode=MAINTENANCE"),
                (b"verticalCubeLoadDistance=12", b"verticalCubeLoadDistance=13"),
                (b"  -1", b"  -2"), (b" B:disableLighting=false", b" I:maintenanceTimeMillis=2\n B:disableLighting=false"))):
            candidate = NativeFixture(self.root, "b" + str(index)); candidate.same_binary(base)
            candidate.set_input("server", "config/cubicchunks.cfg", candidate.cube_config.replace(before, after))
            if before == b"deliveryMode=BASELINE":
                candidate.manifest["cubeExperiments"]["deliveryMode"] = "MAINTENANCE"
            self.assertNotEqual(group, candidate.load(treatment="cc-pacing")["captures"][0]["controlGroup"])

    def test_pacing_mode_requires_same_binary_and_non_numeric_or_unknown_syntax_fails_closed(self):
        base = NativeFixture(self.root, "a")
        group = base.load(treatment="cc-pacing")["captures"][0]["controlGroup"]
        candidate = NativeFixture(self.root, "b")
        self.assertNotEqual(group, candidate.load(treatment="cc-pacing")["captures"][0]["controlGroup"])
        candidate.same_binary(base)
        for replacement in (b"I:maxCubeSendTimeMillis=-1", b"S:maxCubeSendTimeMillis=8", b"unknown syntax"):
            candidate.set_input("server", "config/cubicchunks.cfg", candidate.cube_config.replace(b"I:maxCubeSendTimeMillis=0", replacement))
            capture = candidate.load(treatment="cc-pacing")["captures"][0]
            self.assertFalse(capture["eligibleComparator"])
            self.assertNotEqual(group, capture["controlGroup"])

    def test_manifest_pacing_claim_must_match_proven_input_bytes(self):
        fixture = NativeFixture(self.root)
        fixture.manifest["cubeExperiments"]["maxCubeSendTimeMillis"] = "8"
        capture = fixture.load(treatment="cc-pacing")["captures"][0]
        self.assertFalse(capture["eligibleComparator"])
        self.assertTrue(any("experiment differs" in issue for issue in capture["issues"]))

    def test_maintenance_axis_matches_only_same_binary_and_four_declared_fields(self):
        base, candidate = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        candidate.same_binary(base)
        candidate.configure_maintenance(inspections=128, unloads=4, millis=2)
        a, b = base.load(treatment="cc-maintenance"), candidate.load(treatment="cc-maintenance")
        self.assertTrue(b["captures"][0]["eligibleComparator"], b["captures"][0]["issues"])
        self.assertEqual(a["captures"][0]["controlGroup"], b["captures"][0]["controlGroup"])
        values = b["captures"][0]["declaredTreatmentValues"]["maintenance"]["server"]
        self.assertEqual(values, {"general.S:deliveryMode": "MAINTENANCE",
            "general.I:maintenanceInspectionsPerTick": 128, "general.I:maintenanceUnloadsPerTick": 4,
            "general.I:maintenanceTimeMillis": 2})
        self.assertNotEqual(a["rawInputHashesBeforeLaunch"], b["rawInputHashesBeforeLaunch"])
        self.assertNotEqual(base.load(treatment="cc-pacing")["captures"][0]["controlGroup"],
                            candidate.load(treatment="cc-pacing")["captures"][0]["controlGroup"])

    def test_maintenance_axis_keeps_other_settings_and_binary_strict(self):
        base = NativeFixture(self.root, "a")
        group = base.load(treatment="cc-maintenance")["captures"][0]["controlGroup"]
        for index, (before, after) in enumerate(((b"disableLighting=false", b"disableLighting=true"),
                (b"maxCubeSendTimeMillis=0", b"maxCubeSendTimeMillis=8"),
                (b"  -1", b"  -2"), (b" B:disableLighting=false", b" I:unknownMaintenance=1\n B:disableLighting=false"))):
            candidate = NativeFixture(self.root, "b" + str(index)); candidate.same_binary(base)
            candidate.set_input("server", "config/cubicchunks.cfg", candidate.cube_config.replace(before, after))
            if before == b"maxCubeSendTimeMillis=0":
                candidate.manifest["cubeExperiments"]["maxCubeSendTimeMillis"] = "8"
            self.assertNotEqual(group, candidate.load(treatment="cc-maintenance")["captures"][0]["controlGroup"])
        other = NativeFixture(self.root, "other")
        self.assertNotEqual(group, other.load(treatment="cc-maintenance")["captures"][0]["controlGroup"])

    def test_maintenance_limits_require_reviewed_ranges_and_a_known_delivery_mode(self):
        fixture = NativeFixture(self.root)
        for values in ((0, 8, 1), (65537, 8, 1), (256, 0, 1), (256, 4097, 1), (256, 8, 0), (256, 8, 51)):
            fixture.configure_maintenance(inspections=values[0], unloads=values[1], millis=values[2])
            self.assertFalse(fixture.load(treatment="cc-maintenance")["captures"][0]["eligibleComparator"])
        for replacement in (b"I:deliveryMode=1", b"S:deliveryMode=FAST"):
            fixture.configure_maintenance()
            path = Path(fixture.manifest["server"]) / "config/cubicchunks.cfg"
            fixture.set_input("server", "config/cubicchunks.cfg", path.read_bytes().replace(b"S:deliveryMode=MAINTENANCE", replacement))
            self.assertFalse(fixture.load(treatment="cc-maintenance")["captures"][0]["eligibleComparator"])

    def test_legacy_disabled_maintenance_flag_reads_as_baseline_and_an_enabled_one_stays_distinct(self):
        def controls(body):
            data = ("general {\n" + body + " I:maxCubeSendTimeMillis=0\n}\n").encode()
            return chart.canonical_prepared_input("config/cubicchunks.cfg", data, {}, "cc-source")[1]
        current = controls(" S:deliveryMode=BASELINE\n")
        self.assertEqual(current, controls(" B:incrementalMaintenance=false\n"))
        self.assertEqual(current, controls(""))
        enabled = controls(" B:incrementalMaintenance=true\n")
        self.assertNotEqual(current, enabled)
        self.assertNotIn(chart.DELIVERY_MODE_KEY, enabled)

    def test_maintenance_configuration_needs_exact_prelaunch_byte_proof(self):
        fixture = NativeFixture(self.root)
        fixture.configure_maintenance()
        path = Path(fixture.manifest["server"]) / "config/cubicchunks.cfg"
        path.write_bytes(path.read_bytes() + b"#later runtime rewrite\n")
        capture = fixture.load(treatment="cc-maintenance")["captures"][0]
        self.assertFalse(capture["eligibleComparator"])
        self.assertTrue(any("exact verified server cubicchunks.cfg" in issue for issue in capture["issues"]))

    def test_jvm_treatment_allows_selected_gc_initial_heap_and_pretouch_only(self):
        base, candidate = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        candidate.same_binary(base)
        arguments = ["-Xms8192M", "-Xmx8G", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"]
        candidate.manifest["jvmProfile"].update(initialHeapMiB=8192, collector="G1", arguments=arguments)
        candidate.set_input("server", "user_jvm_args.txt", ("\n".join(arguments) + "\n").encode())
        for side in ("server", "client"):
            candidate.row[side]["runtime"].update(garbageCollectors=["G1 Young Generation", "G1 Old Generation"],
                                               jvmArguments=arguments + ["-XX:StartFlightRecording=filename=b.jfr"])
        a, b = base.load(treatment="jvm"), candidate.load(treatment="jvm")
        self.assertEqual(a["captures"][0]["controlGroup"], b["captures"][0]["controlGroup"])
        self.assertTrue(b["captures"][0]["eligibleComparator"], b["captures"][0]["issues"])
        candidate.row["server"]["runtime"]["jvmArguments"].append("-XX:ConcGCThreads=4")
        self.assertNotEqual(a["captures"][0]["controlGroup"], candidate.load(treatment="jvm")["captures"][0]["controlGroup"])

    def test_jvm_unknown_flags_and_properties_are_never_erased(self):
        a, b = self.root / "a", self.root / "b"
        same = lambda root: ["-Djava.io.tmpdir=" + str(root / "java-tmp"),
                             "-Djava.library.path=" + str(root / "natives")]
        self.assertEqual(chart.jvm_tuning(same(a), a), chart.jvm_tuning(same(b), b))
        for flag in ("-Dunreviewed.feature=true", "-Xss2M", "--add-opens=java.base/java.lang=ALL-UNNAMED"):
            self.assertNotEqual(chart.jvm_tuning(same(a), a), chart.jvm_tuning(same(a) + [flag], a))

    def test_jvm_treatment_keeps_max_heap_runtime_and_other_treatments_strict(self):
        base = NativeFixture(self.root, "a")
        group = base.load(treatment="jvm")["captures"][0]["controlGroup"]
        mutations = [lambda f: f.row["server"]["runtime"].update(maxHeapBytes=4 * 1024 ** 3),
                     lambda f: f.row["client"]["runtime"].update(java="26.0.3"),
                     lambda f: f.manifest["labProperties"].update({"cubiclab.uploadStrategy": "orphan"}),
                     lambda f: f.manifest["labProperties"].update({"cubiclab.renderDiagnostics": "true"}),
                     lambda f: f.manifest["jvmProfile"].update(maxHeapMiB=4096)]
        for index, mutate in enumerate(mutations):
            fixture = NativeFixture(self.root, "b" + str(index)); fixture.same_binary(base); mutate(fixture)
            self.assertNotEqual(group, fixture.load(treatment="jvm")["captures"][0]["controlGroup"])
        other = NativeFixture(self.root, "other")
        self.assertNotEqual(group, other.load(treatment="jvm")["captures"][0]["controlGroup"])

    def test_jvm_declared_profile_must_match_effective_flags_and_exact_argfile(self):
        fixture = NativeFixture(self.root)
        fixture.manifest["jvmProfile"]["initialHeapMiB"] = 8192
        self.assertFalse(fixture.load(treatment="jvm")["captures"][0]["eligibleComparator"])
        fixture.manifest["jvmProfile"]["initialHeapMiB"] = 1024
        fixture.set_input("server", "user_jvm_args.txt", b"-Xms8G\n-Xmx8G\n-XX:+UseZGC\n")
        capture = fixture.load(treatment="jvm")["captures"][0]
        self.assertFalse(capture["eligibleComparator"])
        self.assertTrue(any("argfile differs" in issue for issue in capture["issues"]))

    def test_changed_old_properties_without_snapshot_are_unqualified_and_never_merge(self):
        a, b = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        for fixture in (a, b):
            path = Path(fixture.manifest["server"]) / "server.properties"
            path.write_bytes(path.read_bytes() + b"max-players=20\n")
        arun, brun = a.load(), b.load()
        self.assertFalse(arun["captures"][0]["eligibleComparator"])
        self.assertNotEqual(arun["captures"][0]["controlGroup"], brun["captures"][0]["controlGroup"])
        self.assertTrue(any("pre-launch server.properties proof" in value for value in arun["captures"][0]["issues"]))

    def test_exact_snapshot_survives_postrun_mutation_and_preserves_every_raw_proof(self):
        fixture = NativeFixture(self.root)
        snapshots = retain_prelaunch_inputs(fixture.manifest, fixture.result["inputHashesBeforeLaunch"], fixture.path.parent)
        fixture.result["preLaunchInputSnapshots"] = snapshots
        before = fixture.load()
        (Path(fixture.manifest["server"]) / "server.properties").write_bytes(b"post-run rewritten bytes\n")
        after = fixture.load()
        self.assertTrue(after["captures"][0]["eligibleComparator"])
        self.assertEqual(before["captures"][0]["controlGroup"], after["captures"][0]["controlGroup"])
        self.assertEqual(after["preLaunchInputSnapshots"], snapshots)
        self.assertEqual(after["rawInputHashesBeforeLaunch"], fixture.result["inputHashesBeforeLaunch"])
        record = next(r for r in after["inputCanonicalization"] if r["name"] == "server.properties")
        self.assertIn("snapshot", record["hashVerifiedSource"])

    def test_same_server_port_metadata_does_not_hide_other_properties_or_unknown_inputs(self):
        a, b = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        group = a.load()["captures"][0]["controlGroup"]
        b.manifest["port"] = 25621
        b.set_input("server", "server.properties", b"#other comment\nserver-port=25621\nview-distance=12\nonline-mode=false\n")
        self.assertEqual(group, b.load()["captures"][0]["controlGroup"])
        b.set_input("server", "server.properties", b"server-port=25621\nview-distance=12\nonline-mode=true\n")
        self.assertNotEqual(group, b.load()["captures"][0]["controlGroup"])
        a.result["inputHashesBeforeLaunch"]["server"]["unknown-input.bin"] = "unreviewed-bytes"
        self.assertNotEqual(group, a.load()["captures"][0]["controlGroup"])

    def test_undeclared_future_treatment_is_rejected(self):
        fixture = NativeFixture(self.root)
        with self.assertRaisesRegex(chart.ChartDataError, "Unsupported declared treatment"):
            fixture.load(treatment="unreviewed-axis")

    def test_riven_source_axis_varies_class_bytes_with_exact_resource_proof(self):
        a, b = NativeFixture(self.root, "a"), NativeFixture(self.root, "b")
        b.same_binary(a)
        a.configure_riven_jar(); b.configure_riven_jar(code=b"optimized code")
        arun, brun = a.load(treatment="riven-source"), b.load(treatment="riven-source")
        self.assertTrue(brun["captures"][0]["eligibleComparator"], brun["captures"][0]["issues"])
        self.assertEqual(arun["captures"][0]["controlGroup"], brun["captures"][0]["controlGroup"])
        self.assertNotEqual(arun["rawModHashes"], brun["rawModHashes"])
        self.assertEqual(brun["rivenSourceProof"]["resourceEntrySha256"], arun["rivenSourceProof"]["resourceEntrySha256"])
        self.assertTrue(brun["rivenSourceProof"]["verified"])
        self.assertEqual(brun["rivenSourceProof"]["preparedJars"]["server"]["sha256"], b.manifest["riven"]["sha256"])

    def test_riven_source_axis_keeps_resources_settings_cc_and_lab_properties_strict(self):
        base = NativeFixture(self.root, "a"); base.configure_riven_jar()
        group = base.load(treatment="riven-source")["captures"][0]["controlGroup"]
        variants = [lambda f: f.configure_riven_jar(resources={"mcmod.info": b"changed content", "riven-settings.json": b'{"schema":2}'}),
                    lambda f: f.manifest["riven"]["generatorSettings"].update(sha256="0" * 64),
                    lambda f: f.configure_property("cubiclab.renderDiagnostics", "true"),
                    lambda f: f.manifest["cubicJar"].update(sha256="different CC")]
        for index, mutate in enumerate(variants):
            fixture = NativeFixture(self.root, "b" + str(index)); fixture.same_binary(base)
            fixture.configure_riven_jar(code=b"new code"); mutate(fixture)
            self.assertNotEqual(group, fixture.load(treatment="riven-source")["captures"][0]["controlGroup"])

    def test_riven_source_unknown_or_changed_prepared_jar_cannot_establish_proof(self):
        fixture = NativeFixture(self.root); fixture.configure_riven_jar()
        (Path(fixture.manifest["client"]) / "mods/riven.jar").write_bytes(b"changed after preparation")
        capture = fixture.load(treatment="riven-source")["captures"][0]
        self.assertFalse(capture["eligibleComparator"])
        self.assertTrue(any("differs from recorded SHA" in issue for issue in capture["issues"]))
        fixture.configure_riven_jar(); fixture.manifest["riven"].pop("generatorSettings")
        self.assertFalse(fixture.load(treatment="riven-source")["captures"][0]["eligibleComparator"])
        fixture.configure_riven_jar(resources={})
        self.assertFalse(fixture.load(treatment="riven-source")["captures"][0]["eligibleComparator"])

    def test_riven_resource_added_removed_or_unsafe_names_remain_distinct_or_rejected(self):
        base = NativeFixture(self.root, "a"); base.configure_riven_jar()
        group = base.load(treatment="riven-source")["captures"][0]["controlGroup"]
        fixture = NativeFixture(self.root, "b"); fixture.same_binary(base)
        fixture.configure_riven_jar(resources={"mcmod.info": b"riven fixture", "added.json": b"new"})
        self.assertNotEqual(group, fixture.load(treatment="riven-source")["captures"][0]["controlGroup"])
        fixture.configure_riven_jar(resources={"../ambiguous.json": b"bad"})
        self.assertFalse(fixture.load(treatment="riven-source")["captures"][0]["eligibleComparator"])

    def test_property_axes_remove_only_the_exact_declared_matching_runtime_property(self):
        for treatment, (key, values) in chart.PROPERTY_TREATMENTS.items():
            a = NativeFixture(self.root, treatment + "-a")
            b = NativeFixture(self.root, treatment + "-b"); b.same_binary(a)
            a.configure_property(key, values[0]); b.configure_property(key, values[1])
            arun, brun = a.load(treatment=treatment), b.load(treatment=treatment)
            self.assertEqual(arun["captures"][0]["controlGroup"], brun["captures"][0]["controlGroup"])
            self.assertTrue(brun["captures"][0]["eligibleComparator"], brun["captures"][0]["issues"])
            self.assertEqual(brun["captures"][0]["declaredTreatmentValues"]["labProperties"][key], values[1])
            b.configure_property("cubiclab.renderDiagnostics", "true")
            self.assertNotEqual(arun["captures"][0]["controlGroup"], b.load(treatment=treatment)["captures"][0]["controlGroup"])

    def test_property_axes_missing_mismatched_duplicate_or_unknown_values_fail_closed(self):
        for treatment, (key, values) in chart.PROPERTY_TREATMENTS.items():
            fixture = NativeFixture(self.root, treatment)
            fixture.manifest["labProperties"][key] = values[0]
            self.assertFalse(fixture.load(treatment=treatment)["captures"][0]["eligibleComparator"])
            fixture.configure_property(key, values[0]); fixture.manifest["labProperties"][key] = values[1]
            self.assertFalse(fixture.load(treatment=treatment)["captures"][0]["eligibleComparator"])
            fixture.configure_property(key, "unreviewed")
            self.assertFalse(fixture.load(treatment=treatment)["captures"][0]["eligibleComparator"])
            fixture.configure_property(key, values[0])
            fixture.row["client"]["runtime"]["jvmArguments"].append("-D" + key + "=" + values[0])
            self.assertFalse(fixture.load(treatment=treatment)["captures"][0]["eligibleComparator"])

    def test_property_axes_keep_other_axis_properties_and_all_binaries_strict(self):
        for treatment, (key, values) in chart.PROPERTY_TREATMENTS.items():
            base = NativeFixture(self.root, treatment + "-a"); base.configure_property(key, values[0])
            group = base.load(treatment=treatment)["captures"][0]["controlGroup"]
            fixture = NativeFixture(self.root, treatment + "-b"); fixture.same_binary(base)
            fixture.configure_property(key, values[1])
            other = "cubiclab.fullViewRenderReady" if treatment == "render-upload" else "cubiclab.uploadStrategy"
            fixture.configure_property(other, "true" if treatment == "render-upload" else "orphan")
            self.assertNotEqual(group, fixture.load(treatment=treatment)["captures"][0]["controlGroup"])
            fixture.manifest["labProperties"].pop(other)
            fixture.row["server"]["runtime"]["jvmArguments"] = [arg for arg in fixture.row["server"]["runtime"]["jvmArguments"] if not arg.startswith("-D" + other + "=")]
            fixture.row["client"]["runtime"]["jvmArguments"] = [arg for arg in fixture.row["client"]["runtime"]["jvmArguments"] if not arg.startswith("-D" + other + "=")]
            fixture.manifest["riven"]["sha256"] = "changed Riven"
            fixture.manifest["modHashes"]["riven.jar"] = "changed Riven"
            self.assertNotEqual(group, fixture.load(treatment=treatment)["captures"][0]["controlGroup"])

    def test_missing_controls_or_wrong_distance_cannot_be_accepted(self):
        fixture = NativeFixture(self.root)
        fixture.row["client"]["verticalViewDistance"] = 8
        self.assertFalse(fixture.load()["captures"][0]["eligibleComparator"])
        fixture.row["client"]["verticalViewDistance"] = 12
        fixture.manifest.pop("height")
        self.assertFalse(fixture.load()["captures"][0]["eligibleComparator"])

    def test_missing_or_failed_visual_review_is_retained_and_excluded(self):
        fixture = NativeFixture(self.root)
        unknown = fixture.load(review=False)
        self.assertEqual(unknown["fullViews"][0]["visualStatus"], "unreviewed")
        self.assertFalse(unknown["captures"][0]["eligibleComparator"])
        fixture.qa["scenarios"]["full-view"].update(passed=False, scope="Sky-only failed visual fixture")
        failed = fixture.load()
        self.assertEqual(failed["fullViews"][0]["visualStatus"], "failed")
        self.assertFalse(failed["captures"][0]["eligibleComparator"])
        self.assertIn("Sky-only", failed["visualQa"]["scenarios"]["full-view"]["scope"])

    def test_failed_native_run_keeps_samples_but_cannot_be_comparator(self):
        fixture = NativeFixture(self.root)
        fixture.result.update(passed=False, failure="later workload timed out")
        run = fixture.load()
        self.assertEqual(run["failure"], "later workload timed out")
        self.assertFalse(run["captures"][0]["eligibleComparator"])
        self.assertTrue(run["captures"][0]["metrics"][0]["validWholeCapture"])

    def test_schema_and_capture_identity_cannot_be_silently_mixed(self):
        fixture = NativeFixture(self.root)
        fixture.result["schema"] = 2
        with self.assertRaisesRegex(chart.ChartDataError, "schema 1"):
            fixture.load()
        fixture.result["schema"] = 1
        fixture.row["client"]["runId"] = "different-capture"
        with self.assertRaisesRegex(chart.ChartDataError, "schema/identity"):
            fixture.load()

    def test_cli_requires_explicit_unique_local_paths_and_new_output(self):
        with self.assertRaisesRegex(chart.ChartDataError, "Duplicate"):
            chart.labeled_paths(["a=" + str(self.root / "a"), "a=" + str(self.root / "b")])
        with self.assertRaises(chart.ChartDataError):
            chart.labeled_paths(["a=relative/result.json"])
        fixture = NativeFixture(self.root)
        with redirect_stderr(io.StringIO()) as errors:
            code = chart.main(["--run", "a=" + str(fixture.path), "--output", str(self.root)])
        self.assertEqual(code, 2)
        self.assertIn("already exists", errors.getvalue())

    def test_jfr_repository_normalizes_only_owned_path_and_preserves_tuning(self):
        a, b = self.root / "a", self.root / "b"
        arguments = lambda root: ["-XX:FlightRecorderOptions=repository=" + str(root / "jfr-repository") + ",stackdepth=256"]
        self.assertEqual(chart.jvm_tuning(arguments(a), a), chart.jvm_tuning(arguments(b), b))
        self.assertIn("stackdepth=256", chart.jvm_tuning(arguments(a), a)[0])
        self.assertNotEqual(chart.jvm_tuning(arguments(a), a), chart.jvm_tuning(arguments(b), a))

    def test_reviewed_cache_fields_require_owner_and_measured_cache_branch(self):
        name = "config/AppliedEnergistics2/VersionChecker.cfg"
        a = b"cache {\r\n S:lastCheck=100\r\n I:interval=24\r\n}\r\n"
        b = a.replace(b"100", b"200")
        log = "[AE2 VersionChecker/INFO]: Last check was just recently."
        self.assertIsNone(chart.canonical_cache(name, a, log=log))
        self.assertIsNone(chart.canonical_cache(name, a, ae2_owner=True))
        self.assertEqual(chart.canonical_cache(name, a, ae2_owner=True, log=log)[0],
                         chart.canonical_cache(name, b, ae2_owner=True, log=log)[0])
        self.assertEqual(chart.canonical_cache(name, a, ae2_owner=True, log=log)[0],
                         chart.canonical_cache(name, a, ae2_owner=True, log=log.replace("]: ", "] [AE2:S]: "))[0])
        self.assertNotEqual(chart.canonical_cache(name, a, ae2_owner=True, log=log)[0],
                            chart.canonical_cache(name, b.replace(b"=24", b"=12"), ae2_owner=True, log=log)[0])
        name = "config/AppliedEnergistics2/CustomRecipes.cfg"
        data = b" S:digest=0123456789abcdef0123456789abcdef\n B:enableCache=true\n B:enableForceRefresh=false\n"
        log = "[AE2 CSV Export/INFO]: Same mod configuration was found. Not updating CSV content."
        self.assertIsNotNone(chart.canonical_cache(name, data, ae2_owner=True, log=log))
        self.assertIsNone(chart.canonical_cache(name, data.replace(b"enableCache=true", b"enableCache=false"), ae2_owner=True, log=log))

    def test_item_csv_normalization_preserves_every_row_and_duplicate(self):
        name = "config/AppliedEnergistics2/items.csv"
        prefix = b"Mod:Item:MetaData, Localized Name\n"
        a, b = prefix + b"x:1, One\nx:2, Two\nx:1, One\n", prefix + b"x:2, Two\nx:1, One\nx:1, One\n"
        self.assertEqual(chart.canonical_cache(name, a, ae2_owner=True)[0], chart.canonical_cache(name, b, ae2_owner=True)[0])
        self.assertNotEqual(chart.canonical_cache(name, a, ae2_owner=True)[0],
                            chart.canonical_cache(name, prefix + b"x:2, Two\nx:1, One\n", ae2_owner=True)[0])
        self.assertIsNone(chart.canonical_cache(name, a))

    def test_startup_timing_normalization_keeps_format_and_phase_names(self):
        name = "config/cleanroom_load_timings.dat"
        def data(phase, time):
            return struct.pack(">iiH", 4, 1, len(phase)) + phase + struct.pack(">i", time)
        self.assertEqual(chart.canonical_cache(name, data(b"INIT", 4), loader_owner=True)[0],
                         chart.canonical_cache(name, data(b"INIT", 400), loader_owner=True)[0])
        self.assertNotEqual(chart.canonical_cache(name, data(b"INIT", 4), loader_owner=True)[0],
                            chart.canonical_cache(name, data(b"POSTINIT", 4), loader_owner=True)[0])
        self.assertIsNone(chart.canonical_cache(name, data(b"INIT", 4) + b"trailing", loader_owner=True))
        self.assertIsNone(chart.canonical_cache(name, data(b"INIT", 4)))

    def test_reviewed_loading_owners_preserve_exact_identity_and_unknown_is_strict(self):
        for index, owner in enumerate((*chart.CLEANROOM_LOADING_OWNERS, "f" * 64)):
            with self.subTest(owner=owner):
                fixture = NativeFixture(self.root, name="loading-owner-" + str(index))
                fixture.manifest["runtimeArtifacts"]["client"]["sha256"] = owner
                name = "config/splash.properties"
                data = b"#generated timestamp\nenabled=true\n"
                fixture.result["inputHashesBeforeLaunch"]["client"][name] = hashlib.sha256(data).hexdigest()
                path = Path(fixture.manifest["client"]) / name
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
                run = fixture.load()
                records = [r for r in run["inputCanonicalization"] if r["name"] == name]
                if owner in chart.CLEANROOM_LOADING_OWNERS:
                    self.assertEqual(len(records), 1)
                    self.assertEqual(records[0]["loaderOwnerSha256"], owner)
                    self.assertIn("every splash property retained", records[0]["rule"])
                else:
                    self.assertEqual(records, [])

    def test_changed_postrun_files_require_exact_prelaunch_archive_bytes(self):
        fixture = NativeFixture(self.root)
        fixture.manifest["runtimeArtifacts"]["client"]["sha256"] = chart.CLEANROOM_LOADING_OWNER
        name = "config/splash.properties"
        before, after = b"#old timestamp\nenabled=true\n", b"#new timestamp\nenabled=false\n"
        import hashlib
        expected = hashlib.sha256(before).hexdigest()
        fixture.result["inputHashesBeforeLaunch"]["client"][name] = expected
        path = Path(fixture.manifest["client"]) / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(after)
        run = fixture.load()
        self.assertEqual([r for r in run["inputCanonicalization"] if r["name"] == name], [])
        archive = fixture.root / "retained-primed.zip"
        with zipfile.ZipFile(archive, "w") as saved:
            saved.writestr("minecraft/" + name, before)
        fixture.manifest["prismImport"] = str(archive)
        run = fixture.load()
        self.assertEqual(run["rawInputHashesBeforeLaunch"]["client"][name], expected)
        record = next(r for r in run["inputCanonicalization"] if r["name"] == name)
        self.assertEqual(record["hashVerifiedSource"]["archive"], str(archive))
        self.assertEqual(record["rawSha256"], expected)

    def test_csv_and_json_keep_prefix_failure_without_promoting_reported_p99(self):
        fixture = NativeFixture(self.root)
        fixture.row["server"]["serverTicks"].update(count=4, droppedCount=1, percentilesCoverAllObservations=False)
        run = fixture.load()
        output = self.root / "data-only"; output.mkdir()
        chart.write_data(output, [run])
        data = json.loads((output / "data.json").read_text())
        self.assertIsNone(data["runs"][0]["captures"][0]["metrics"][0]["statistics"])
        with (output / "summary.csv").open(newline="") as stream:
            record = next(csv.DictReader(stream))
        self.assertEqual(record["p99Ms"], "")
        self.assertEqual(record["eligibleComparator"], "False")
        self.assertEqual({path.suffix for path in output.iterdir()}, {".csv", ".json"})


if __name__ == "__main__":
    unittest.main()
