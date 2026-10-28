"""Paired run-level bootstrap. Never treat correlated ticks as independent repetitions."""
import math
import random
import statistics


def nearest_rank(values, probability):
    if not values or not 0 < probability <= 1:raise ValueError('Invalid quantile input')
    return sorted(values)[max(0,math.ceil(len(values)*probability)-1)]


def paired_interval(pairs, seed=71423, draws=10000):
    """Caller supplies matched independent runs from ONE site/scenario/metric and exact controls."""
    if not pairs or draws < 1000:raise ValueError('Need paired runs and at least 1000 resamples')
    for before,after in pairs:
        if any(type(n) not in (int,float) or not math.isfinite(n) or n<=0 for n in (before,after)):
            raise ValueError('Finite positive paired costs required')
    changes=[100*(after/before-1) for before,after in pairs]
    rng=random.Random(seed);n=len(changes)
    medians=[statistics.median(rng.choices(changes,k=n)) for _ in range(draws)]
    return {'pairs':n,'pairedPercentChanges':changes,'medianPercentChange':statistics.median(changes),
            'bootstrap95PercentInterval':[nearest_rank(medians,.025),nearest_rank(medians,.975)],
            'enoughRepetitionsForReview':n>=5,'seed':seed,'resamples':draws,
            'scope':'Percentile bootstrap of the median paired run change. Descriptive uncertainty with few runs; no pooled-tick independence, causal proof or automatic acceptance. Minimum five matched run pairs for review; retain per-run tails, maxima and output/durability gates.'}


def window_quality(count, dropped, minimum=3000):
    if type(count) is not int or type(dropped) is not int or count<0 or dropped<0:raise ValueError('Invalid counts')
    return {'observations':count,'dropped':dropped,'p99UpperTailObservations':count//100,
            'sufficientForTailReview':count>=minimum and dropped==0,'minimumObservations':minimum,
            'scope':'Sampling gate only. Temporal dependence and independent run repetition still matter; brief save-command windows are event measurements, not stable p99 estimates.'}
