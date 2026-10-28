"""Guard against double-counting nested spans and losing MethodTrace identity."""
import unittest
from analyze_cubic_profile import compact_event
from trace_cubic_lab import split_tick, stats


class TraceTest(unittest.TestCase):
    def test_nested_reentrant_and_cross_boundary_stages_partition_wall_time(self):
        parts = split_tick(100, 200, [(90, 160, 'streaming'), (120, 140, 'completion'),
                                     (150, 170, 'fixture'), (180, 220, 'provider')])
        self.assertAlmostEqual(sum(parts.values()), .0001)
        self.assertAlmostEqual(parts['streamingMs'], .00003)
        self.assertAlmostEqual(parts['completionMs'], .00002)
        self.assertAlmostEqual(parts['fixtureMs'], .00002)
        self.assertAlmostEqual(parts['providerMs'], .00002)
        self.assertAlmostEqual(parts['otherMs'], .00001)

    def test_method_identity_survives_stream_compaction(self):
        event = compact_event({'type': 'jdk.MethodTrace', 'values': {
            'startTime': '2026-10-15T01:00:00Z', 'duration': 'PT0.001S',
            'method': {'type': {'name': 'a/b/C', 'classLoader': {}}, 'name': 'run', 'descriptor': '()V'}}})
        self.assertEqual(event['values']['method'], {'name': 'run', 'type': {'name': 'a/b/C'}})

    def test_nearest_rank_tail_has_observed_value(self):
        self.assertEqual(stats(list(range(1, 21)))['p95Ms'], .000019)

    def test_thread_identity_and_data_loss_survive_compaction(self):
        event = compact_event({'type': 'jdk.DataLoss', 'values': {
            'startTime': '2026-10-20T01:00:00Z', 'amount': 4096, 'total': 8192,
            'eventThread': {'javaName': 'main', 'javaThreadId': 1, 'osThreadId': 42}}})
        self.assertEqual(event['values']['amount'], 4096)
        self.assertEqual(event['values']['total'], 8192)
        self.assertEqual(event['values']['eventThread']['javaThreadId'], 1)
        self.assertEqual(event['values']['eventThread']['osThreadId'], 42)


if __name__ == '__main__':
    unittest.main()
