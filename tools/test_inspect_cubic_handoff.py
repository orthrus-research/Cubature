"""Queue accounting must tolerate absent IDs without inventing individual latency tails."""
import unittest
from inspect_cubic_handoff import population, classify_completions


class HandoffAccountingTest(unittest.TestCase):
    def test_complete_cohort_mean_is_invariant_while_individual_tail_is_not(self):
        starts = [0, 1_000_000, 2_000_000]; ends = [10_000_000, 11_000_000, 12_000_000]
        a = population(starts, ends, 0, 13_000_000)
        b = population(starts, list(reversed(ends)), 0, 13_000_000)
        self.assertEqual(a['areaJobNanos'], 30_000_000)
        self.assertEqual(a['meanMs'], b['meanMs'])
        self.assertNotEqual(max(e-s for s,e in zip(starts,ends)),
                            max(e-s for s,e in zip(starts,reversed(ends))))
        self.assertNotIn('p95Ms', a)

    def test_epoch_shift_and_simultaneous_admissions_do_not_lose_precision(self):
        offset = 1_800_000_000_000_000_000
        a = population([0, 2, 2], [2, 3, 5], 0, 6)
        b = population([offset, offset+2, offset+2], [offset+2, offset+3, offset+5], offset, offset+6)
        self.assertEqual(a['areaJobNanos'], b['areaJobNanos'])
        self.assertEqual(a['peakPopulation'], 2)

    def test_incomplete_cross_boundary_or_impossible_cohorts_are_rejected(self):
        for starts, ends in [([1,2],[3]), ([1],[20]), ([5],[4])]:
            with self.subTest(starts=starts,ends=ends), self.assertRaises(ValueError):
                population(starts,ends,0,10)

    def test_missing_and_reentrant_owner_matches_are_not_guessed(self):
        child = [{'start': 3, 'end': 4}]
        cube, column = classify_completions([{'start':2,'end':5},{'start':6,'end':8}], child)
        self.assertEqual(len(cube),1); self.assertEqual(len(column),1)
        with self.assertRaises(ValueError):
            classify_completions([{'start':1,'end':6},{'start':2,'end':5}], child)
        with self.assertRaises(ValueError):
            classify_completions([{'start':6,'end':8}], child)


if __name__ == '__main__':
    unittest.main()
