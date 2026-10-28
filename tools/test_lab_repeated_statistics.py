import unittest
from lab_repeated_statistics import paired_interval,window_quality

class RepeatedStatisticsTest(unittest.TestCase):
    def test_pairing_preserves_effect_across_different_baseline_costs(self):
        value=paired_interval([(n,n*.9) for n in (1,10,100,1000,10000)])
        self.assertAlmostEqual(value['medianPercentChange'],-10)
        for bound in value['bootstrap95PercentInterval']:self.assertAlmostEqual(bound,-10)
        self.assertTrue(value['enoughRepetitionsForReview'])
    def test_sparse_runs_and_truncated_windows_remain_unqualified(self):
        self.assertFalse(paired_interval([(100,90),(200,180)])['enoughRepetitionsForReview'])
        self.assertFalse(window_quality(16,0)['sufficientForTailReview'])
        self.assertFalse(window_quality(12000,1)['sufficientForTailReview'])
        self.assertTrue(window_quality(6000,0)['sufficientForTailReview'])
    def test_reproducible_and_rejects_invalid_costs(self):
        pairs=[(10,9),(10,15),(30,28),(40,20),(50,45)]
        self.assertEqual(paired_interval(pairs),paired_interval(pairs))
        for pairs in ([],[(0,1)],[(1,float('nan'))],[(1,-1)]):
            with self.assertRaises(ValueError):paired_interval(pairs)

if __name__=='__main__':unittest.main()
