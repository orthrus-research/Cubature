"""Synthetic parser checks; fixtures are not native profiling evidence."""

import unittest
import io
import json

import analyze_cubic_profile as profile


def event(kind, timestamp='2026-10-13T00:00:01Z', stack=(), **values):
    return {'type': 'jdk.' + kind, 'values': {
        'startTime': timestamp,
        'stackTrace': {'frames': [
            {'method': {'type': {'name': cls}, 'name': method}}
            for cls, method in stack]}, **values}}


class ProfileSemanticsTest(unittest.TestCase):
    def test_nanosecond_timestamps_and_durations(self):
        self.assertEqual(profile.duration_ns('PT0.000000001S'), 1)
        self.assertEqual(profile.duration_ns('PT1H2M3.5S'), 3723500000000)
        self.assertEqual(profile.timestamp_ns('2026-10-13T00:00:00.123456789Z')
                         - profile.timestamp_ns('2026-10-12T20:00:00.123456788-04:00'), 1)
        with self.assertRaises(ValueError):
            profile.duration_ns(1000)

    def test_start_inclusive_end_exclusive_and_native_separate(self):
        events = [event('ExecutionSample', '2026-10-13T00:00:01Z'),
                  event('ExecutionSample', '2026-10-13T00:00:01.999999999Z'),
                  event('ExecutionSample', '2026-10-13T00:00:02Z'),
                  event('NativeMethodSample', '2026-10-13T00:00:01Z')]
        result = profile.analyze(events, 'test', '2026-10-13T00:00:01Z', '2026-10-13T00:00:02Z')
        self.assertEqual(result['execution']['count'], 2)
        self.assertEqual(result['native']['count'], 1)
        self.assertEqual(result['execution']['missing_stacks'], 2)
        self.assertEqual(result['window_duration_ns'], 1000000000)

    def test_attribution_skips_library_leaf_but_preserves_it(self):
        stack = [('java/util/HashMap', 'get'),
                 ('cubicchunks/regionlib/lib/Region', 'read'),
                 ('io/github/opencubicchunks/cubicchunks/core/server/chunkio/RegionCubeStorage', 'loadCube'),
                 ('io/github/opencubicchunks/cubicchunks/core/server/chunkio/RegionCubeStorage', 'loadCube')]
        result = profile.analyze([event('ExecutionSample', stack=stack)], 'test')['execution']
        self.assertEqual(result['subsystems'][0]['name'], 'RegionLib')
        self.assertEqual(result['leaf_methods'][0]['name'], 'java.util.HashMap.get')
        self.assertEqual(len(result['inclusive_tracked_methods']), 2)
        self.assertEqual(sum(row['count'] for row in result['inclusive_tracked_methods']), 2)

    def test_allocation_weights_and_gc_observations_do_not_merge(self):
        events = [event('ObjectAllocationSample', weight=100, objectClass={'name': '[B'}),
                  event('ObjectAllocationSample', weight=300, objectClass={'name': '[I'}),
                  event('GarbageCollection', sumOfPauses='PT0.02S', longestPause='PT0.02S'),
                  event('GCPhasePause', duration='PT0.02S')]
        result = profile.analyze(events, 'test')
        self.assertEqual(result['allocation']['count'], 2)
        self.assertEqual(result['allocation']['weight_bytes'], 400)
        self.assertEqual(result['allocation']['classes'][0]['share_percent'], 75)
        self.assertEqual(result['gc']['reported_sum_of_pauses_ns'], 20000000)
        self.assertEqual(result['gc']['top_level_pause_duration_ns'], 20000000)

    def test_file_io_and_wait_durations_are_recorded_event_sums(self):
        events = [event('FileWrite', bytesWritten=25, duration='PT0.02S'),
                  event('FileWrite', bytesWritten=30, duration='PT0.03S'),
                  event('ThreadPark', duration='PT2S', eventThread={'javaName': 'worker'})]
        result = profile.analyze(events, 'test')
        io = result['file_io']['jdk.FileWrite']
        self.assertEqual((io['bytes'], io['count'], io['duration_ns']), (55, 2, 50000000))
        self.assertEqual(result['waits']['jdk.ThreadPark']['duration_ns'], 2000000000)

    def test_file_force_retains_duration_without_inventing_written_bytes(self):
        result = profile.analyze([event('FileForce', duration='PT0.025S', metaData=True)], 'force')
        force = result['file_io']['jdk.FileForce']
        self.assertEqual((force['count'], force['bytes'], force['duration_ns']), (1, 0, 25000000))

    def test_repeated_phase_identity_and_timing_fields(self):
        phase = {'name': 'generate', 'iteration': 2, 'elapsedNanos': 5,
                 'operations': 512, 'loadedCubes': 1024, 'checksum': -12345}
        row = list(profile.timing_rows('cleanroom', phase))[0]
        self.assertEqual(row['phase'], 'generate #2')
        self.assertEqual((row['loaded_cubes'], row['checksum']), (1024, -12345))

    def test_chart_repetition_summary_preserves_measured_range(self):
        report = {'runs': [], 'timings': [next(profile.timing_rows('cleanroom', {
            'name': 'generate', 'iteration': index, 'elapsedNanos': value,
            'operations': 512})) for index, value in enumerate((10, 30, 20))]}
        row = profile.combined_chart_timings(report)[0]
        self.assertEqual((row['median_ns'], row['minimum_ns'], row['maximum_ns']), (20, 10, 30))
        self.assertEqual((row['repetitions'], row['operations_per_repetition']), (3, 512))

    def test_stream_decoder_crosses_event_boundaries_and_rejects_truncation(self):
        events = [event('ExecutionSample', stack=[('java/util/HashMap', 'get')]),
                  event('ObjectAllocationSample', weight=100, objectClass={'name': '[B'})]
        raw = json.dumps({'recording': {'events': events}})
        compacted = list(profile.iter_jfr_events(io.StringIO(raw), 64))
        self.assertEqual(profile.analyze(events, 'check'), profile.analyze(compacted, 'check'))
        with self.assertRaises(ValueError):
            list(profile.iter_jfr_events(io.StringIO(raw[:-3]), 64))
        with self.assertRaises(ValueError):
            list(profile.iter_jfr_events(io.StringIO(raw + '{}'), 64))


if __name__ == '__main__':
    unittest.main()
