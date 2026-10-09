"""Paired inference from validated immutable campaign observations, never fixture scores."""
from collections import defaultdict
from decimal import Decimal, InvalidOperation
import math
import random
import statistics

import campaign_config as cc
from experiment_config import canonical

METRICS = ('costCents', 'fairnessVariance', 'latencyMs', 'moveEvaluationsPerSecond')


def decimal_number(value):
    cc.check(type(value) in (str, int, float), 'Explicit decimal measurement required')
    try:
        result = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError('Invalid decimal measurement') from error
    cc.check(result.is_finite() and result >= 0, 'Nonnegative finite measurement required')
    return result


def measurements(result):
    """Missing measurements stay null. Workflow cost needs explicit served identities."""
    values = dict.fromkeys(METRICS)
    reasons = {}
    served = None
    if result['layer'] in ('solver', 'policy'):
        metrics = result['metrics' if result['layer'] == 'solver' else 'retainedMetrics']
        values['costCents'] = metrics['costCents']
        values['latencyMs'] = result['wallMs']
        if result.get('policyMetrics') is not None:
            policy = result['policyMetrics']
            cc.check(policy['costCents'] == metrics['costCents'] and policy['overtimeMinutes'] == metrics['overtimeMinutes'], 'Independent policy metrics disagree')
            values['fairnessVariance'] = decimal_number(policy['fairness']['variance'])
        if result['layer'] == 'solver':
            native = result['nativeMeasurement']
            if native['solveMs'] > 0:
                values['moveEvaluationsPerSecond'] = native['moveEvaluationCount'] * 1000 / native['solveMs']
        proposal = result['proposal' if result['layer'] == 'solver' else 'retained']
        served = sorted(visit for route in proposal['routes'].values() for visit in route)
    for name, value in values.items():
        if value is not None:
            cc.check(type(value) in (int, float, Decimal) and math.isfinite(value) and value >= 0, f'Invalid measurement: {name}')
        else:
            reasons.setdefault(name, 'Measurement unavailable in this validated adapter receipt')
    return {'values': values, 'unavailable': reasons, 'servedIdentities': served}


def interval(values, rules):
    """Resample whole datasets after reducing their paired repetitions, not individual JVMs."""
    if len(values) < 2:
        return {'estimate': statistics.mean(values) if values else None, 'interval': None,
                'unavailableReason': 'At least two independent datasets required for an interval'}
    rng = random.Random(rules['seed'])
    draws = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(rules['draws']))
    tail = (1 - rules['confidenceLevel']) / 2
    # Explicit empirical order statistics, with no extrapolation beyond observed bootstrap draws.
    lower = draws[math.floor(tail * (len(draws) - 1))]
    upper = draws[math.ceil((1 - tail) * (len(draws) - 1))]
    return {'estimate': statistics.mean(values), 'interval': [lower, upper], 'unavailableReason': None}


def paired(config, blocks, observations, loaded, runtime_hash):
    """Loaded maps dispatched case IDs to already validated benchmark receipts and validity."""
    wanted = {case['id']: case for block in blocks for case in block['cases']}
    cc.check(len(wanted) == sum(len(block['cases']) for block in blocks), 'Duplicate requested case identity')
    cc.check(set(observations) <= set(wanted) and set(loaded) <= set(observations), 'Unknown analysis observation')
    datasets = {dataset['id']: dataset for dataset in config['datasets']}
    counts = {}
    for treatment in config['configurations']:
        ids = [key for key, case in wanted.items() if case['configurationId'] == treatment['id']]
        states = [observations[key]['state'] for key in ids if key in observations]
        counts[treatment['id']] = {'requested': len(ids), 'dispatched': len(states),
            'completed': sum(state in ('SUCCEEDED', 'FAILED') for state in states),
            'successful': states.count('SUCCEEDED'), 'failed': states.count('FAILED'),
            'interrupted': states.count('INTERRUPTED'), 'abandoned': states.count('ABANDONED'),
            'missing': len(ids) - len(states), 'valid': sum(key in loaded and loaded[key]['valid'] is True for key in ids),
            'paired': 0, 'reliabilityDenominator': len(ids)}
    result = {'method': 'paired-dataset-bootstrap', 'unit': 'dataset', 'difference': 'candidate-minus-control',
              'rules': config['analysis'],
              'counts': counts, 'baselines': [], 'comparisons': [], 'unmatched': [], 'automaticPromotion': False}
    baseline_groups = defaultdict(list)
    for case_id, sample in loaded.items():
        case = wanted[case_id]
        metadata = datasets[case['datasetId']]
        if sample['valid'] is not True or metadata['cohort'] in ('invalid-input', 'repair'):
            continue
        key = (case['configurationId'], case['budgetHash'], metadata['cohort'], metadata['scoreVersion'], metadata['modelVersion'], metadata['routingIdentity'])
        baseline_groups[key].append({'datasetHash': case['datasetHash'], 'family': metadata['family'], 'values': measurements(sample['result'])['values']})
    for cohort, samples in baseline_groups.items():
        grouped = defaultdict(list)
        for sample in samples:
            grouped[sample['datasetHash']].append(sample)
        reduced = []
        for dataset_hash, repeats in sorted(grouped.items()):
            values = {}
            for metric in METRICS:
                series = [sample['values'][metric] for sample in repeats]
                values[metric] = statistics.mean(series) if all(value is not None for value in series) else None
            reduced.append({'datasetHash': dataset_hash, 'family': repeats[0]['family'], 'validRepetitions': len(repeats), 'means': values})
        means = {}
        for metric in METRICS:
            series = [row['means'][metric] for row in reduced]
            means[metric] = statistics.mean(series) if all(value is not None for value in series) else None
        result['baselines'].append({'cohort': list(cohort), 'runtimeHash': runtime_hash, 'datasets': reduced, 'equalDatasetMeans': means})
    for treatment in config['configurations']:
        candidate = treatment['id']
        if candidate == config['controlId']:
            continue
        groups = defaultdict(list)
        seen = set()
        for block in blocks:
            cases = {case['configurationId']: case for case in block['cases']}
            control_case, candidate_case = cases[config['controlId']], cases[candidate]
            identity = {key: runtime_hash if key == 'runtimeHash' else control_case[key] for key in config['analysis']['pairKeys']}
            cc.check(identity == {key: runtime_hash if key == 'runtimeHash' else candidate_case[key] for key in config['analysis']['pairKeys']}, 'Pair identity mismatch')
            key = canonical(identity)
            cc.check(key not in seen, 'Duplicate paired observation')
            seen.add(key)
            metadata = datasets[control_case['datasetId']]
            left, right = loaded.get(control_case['id']), loaded.get(candidate_case['id'])
            reason = None
            for case, observation in ((control_case, left), (candidate_case, right)):
                if case['id'] not in observations:
                    reason = f'{case["configurationId"]}: never dispatched'
                    break
                if observations[case['id']]['state'] != 'SUCCEEDED':
                    reason = f'{case["configurationId"]}: {observations[case["id"]]["state"]}'
                    break
                if observation is None or observation['valid'] is not True:
                    reason = f'{case["configurationId"]}: no independently valid scheduling outcome'
                    break
            if metadata['cohort'] in ('invalid-input', 'repair'):
                reason = 'Contract/repair cohort excluded from feasible cost ranking'
            if reason is not None:
                result['unmatched'].append({'candidate': candidate, 'pair': identity, 'reason': reason})
                continue
            lm, rm = measurements(left['result']), measurements(right['result'])
            deltas, unavailable = {}, {}
            for metric in METRICS:
                if lm['values'][metric] is None or rm['values'][metric] is None:
                    deltas[metric] = None
                    unavailable[metric] = [lm['unavailable'].get(metric), rm['unavailable'].get(metric)]
                elif metric == 'costCents' and (lm['servedIdentities'] is None or lm['servedIdentities'] != rm['servedIdentities']):
                    deltas[metric] = None
                    unavailable[metric] = ['Different or unavailable served customer identities']
                else:
                    deltas[metric] = rm['values'][metric] - lm['values'][metric]
            grouping = (control_case['budgetHash'], metadata['cohort'], metadata['scoreVersion'], metadata['modelVersion'], metadata['routingIdentity'])
            groups[grouping].append({'datasetHash': identity['datasetHash'], 'family': metadata['family'], 'pair': identity,
                                    'deltas': deltas, 'unavailable': unavailable})
            counts[candidate]['paired'] += 1
        for cohort, pairs in groups.items():
            reduced = defaultdict(list)
            for pair in pairs:
                reduced[pair['datasetHash']].append(pair)
            per_dataset = []
            for dataset_hash, repeats in sorted(reduced.items()):
                cc.check(len({row['family'] for row in repeats}) == 1, 'Dataset cannot change scenario family')
                metrics = {}
                for metric in METRICS:
                    values = [row['deltas'][metric] for row in repeats]
                    metrics[metric] = statistics.mean(values) if all(value is not None for value in values) else None
                per_dataset.append({'datasetHash': dataset_hash, 'family': repeats[0]['family'], 'pairedRepetitions': len(repeats), 'effects': metrics})
            summaries = {}
            families = sorted({row['family'] for row in per_dataset})
            for metric in METRICS:
                values = [row['effects'][metric] for row in per_dataset]
                summaries[metric] = interval(values, config['analysis']) if all(value is not None for value in values) else {
                    'estimate': None, 'interval': None, 'unavailableReason': 'Missing paired metric; no imputation or complete-case substitution'}
            family_results = {}
            for family in families:
                family_results[family] = {}
                for metric in METRICS:
                    values = [row['effects'][metric] for row in per_dataset if row['family'] == family]
                    family_results[family][metric] = statistics.mean(values) if all(value is not None for value in values) else None
            reliability = counts[candidate]['valid'] == counts[candidate]['requested'] and counts[config['controlId']]['valid'] == counts[config['controlId']]['requested']
            cost = summaries['costCents']['interval']
            guard = all(family_results[family]['costCents'] is not None and family_results[family]['costCents'] <= 0
                        and family_results[family]['fairnessVariance'] is not None and family_results[family]['fairnessVariance'] <= 0 for family in families)
            cost_gate = cost is not None and cost[1] < config['analysis']['costDifferenceUpperBoundCents'] and guard and reliability
            result['comparisons'].append({'candidate': candidate, 'control': config['controlId'], 'cohort': list(cohort), 'runtimeHash': runtime_hash,
                'pairedCount': len(pairs), 'datasetCount': len(per_dataset), 'perDataset': per_dataset, 'metrics': summaries,
                'families': family_results, 'pairs': pairs, 'costGateSatisfied': cost_gate,
                'disposition': 'evidence-for-later-confirmation' if cost_gate else 'inconclusive-or-gate-not-met',
                'latencyGateUnavailableReason': 'Request p95 and held-out service promotion are separate phase 15 gates'})
    # Counts are comparison-specific for candidates; the control can be paired with several candidates.
    counts[config['controlId']]['paired'] = len({canonical(pair['pair']) for comparison in result['comparisons'] for pair in comparison['pairs']})
    return json_measurements(result)


def json_measurements(value):
    """Keep exact decimal fairness differences in wire strings through family eligibility gates."""
    if isinstance(value, Decimal):
        return format(value, 'f')
    if isinstance(value, dict):
        return {key: json_measurements(item) for key, item in value.items()}
    if isinstance(value, list):
        return [json_measurements(item) for item in value]
    return value
