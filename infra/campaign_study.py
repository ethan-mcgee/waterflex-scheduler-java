"""Explicitly registered calibration studies. A failed case stops the study without retries."""
import copy
from pathlib import Path
import random
import statistics
import uuid

import campaign_analysis as ca
import campaign_config as cc
import campaign_runtime as cr
from experiment_config import fields, read_json, canonical
from experiment_runtime import sha, stamp, write_new, measurement_lock


def validate(spec, base):
    fields(spec, ['version', 'kind', 'name', 'purpose', 'campaigns', 'rules', 'outputLocation'])
    cc.choice(spec['version'], 1)
    cc.choice(spec['kind'], 'warmup', 'concurrency', 'profile')
    cc.slug(spec['name'])
    cc.text(spec['purpose'])
    cc.text(spec['outputLocation'])
    cc.check(isinstance(spec['campaigns'], list) and len(spec['campaigns']) >= 2, 'At least two registered campaigns required')
    rules = spec['rules']
    fields(rules, ['referenceLabel', 'metric', 'tolerancePercent', 'draws', 'seed', 'confidenceLevel', 'minimumRepetitions'])
    cc.text(rules['referenceLabel'])
    cc.choice(rules['metric'], 'moveEvaluationsPerSecond', 'latencyMs')
    cc.number(rules['tolerancePercent'])
    cc.choice(rules['draws'], 10000)
    cc.integer(rules['seed'], 0)
    cc.choice(rules['confidenceLevel'], .95)
    cc.integer(rules['minimumRepetitions'], 3)
    labels, configs, normalized = [], {}, []
    for entry in spec['campaigns']:
        fields(entry, ['label', 'configuration'])
        cc.slug(entry['label'])
        cc.artifact(entry['configuration'])
        source = (base / entry['configuration']['path']).resolve()
        cc.check(source.is_file() and sha(source) == entry['configuration']['sha256'], 'Registered study configuration changed')
        config = cc.resolve(read_json(source), source.parent)
        cc.check(config['analysis']['method'] == 'inventory-only', 'Calibration is a separate cohort, not treatment selection')
        cc.check(config['forks'] >= rules['minimumRepetitions'], 'Insufficient independent fresh-JVM repetitions')
        cc.check(config['layer'] in ('solver', 'policy'), 'Offline calibration supports solver and policy paths; caller calibration requires dedicated deployment fixtures')
        cc.check(len(config['warmup']['paths']) == 1, 'Each path requires a separate calibration study')
        cc.check(all(dataset['cohort'] not in ('invalid-input', 'repair') for dataset in config['datasets']), 'Separate contract/repair calibration evidence')
        value = copy.deepcopy(config)
        for key in ('name', 'purpose', 'outputLocation'):
            value.pop(key)
        if spec['kind'] == 'warmup':
            cc.check(config['warmup']['millisecondsPerFreshJvm'] in (200, 30000, 60000), 'Required warmup probes are 0.2, 30 and 60 seconds')
            cc.check(config['instrumentation']['jfr'] == 'disabled', 'Warmup calibration must be unprofiled')
            value['warmup'].pop('millisecondsPerFreshJvm')
        elif spec['kind'] == 'concurrency':
            cc.check(config['instrumentation']['jfr'] == 'disabled', 'Concurrency calibration must be unprofiled')
            cc.check(len(config['configurations']) >= config['resources']['parallelCases'], 'Declared parallel cases need enough same-block independent instances')
            value['resources'].pop('parallelCases')
        else:
            cc.check(config['instrumentation']['jfr'] in ('disabled', 'profile'), 'Use the declared JFR profile separately from timed runs')
            value['instrumentation'].pop('jfr')
            value['instrumentation'].pop('cohort')
        configs[entry['label']] = (source, config)
        labels.append(entry['label'])
        normalized.append(value)
    cc.unique(labels)
    cc.check(rules['referenceLabel'] in labels, 'Registered reference label required')
    cc.check(all(canonical(value) == canonical(normalized[0]) for value in normalized), 'Calibration changes more than its declared factor')
    if spec['kind'] == 'warmup':
        cc.check(sorted(config['warmup']['millisecondsPerFreshJvm'] for _, config in configs.values()) == [200, 30000, 60000], 'Every required warmup probe must occur exactly once')
        cc.check(configs[rules['referenceLabel']][1]['warmup']['millisecondsPerFreshJvm'] == 60000, 'Warmup reference must be 60 seconds')
    elif spec['kind'] == 'concurrency':
        cc.check(configs[rules['referenceLabel']][1]['resources']['parallelCases'] == 1, 'Serial concurrency reference required')
        cc.unique([config['resources']['parallelCases'] for _, config in configs.values()])
    else:
        cc.check(configs[rules['referenceLabel']][1]['instrumentation']['jfr'] == 'disabled', 'Unprofiled reference required')
        cc.check(any(config['instrumentation']['jfr'] == 'profile' for _, config in configs.values()), 'Separate profiled run required')
    return configs


def execute(source):
    source = source.resolve()
    spec = read_json(source)
    configs = validate(spec, source.parent)
    root = (source.parent / spec['outputLocation']).resolve() / f'{stamp()}-{spec["name"]}-{uuid.uuid4().hex[:8]}'
    root.mkdir(parents=True, exist_ok=False)
    cr.bytes_new(root / 'original-study.json', source.read_bytes())
    write_new(root / 'registration.json', {'specification': spec, 'toolkit': cr.toolkit_hashes(),
        'campaigns': {label: {'source': str(path), 'sha256': sha(path)} for label, (path, _) in configs.items()},
        'automaticRetries': False})
    print(f'Study: {root}', flush=True)
    try:
        with measurement_lock():
            for label, (path, _) in configs.items():
                print(f'Registered probe: {label}', flush=True)
                # Keep JVM working directories at the normal campaign depth on Windows.
                # The study retains an immutable manifest-hashed pointer to each independent archive.
                run = cr.new_run(path)
                write_new(root / 'probes' / f'{label}.json', {'label': label, 'run': str(run), 'manifestHash': sha(run / 'manifest.json')})
                cr.execute(run)
                cr.analyze(run)
        write_new(root / 'terminal.json', {'state': 'COMPLETE', 'at': stamp(), 'failure': None})
    except BaseException as error:
        write_new(root / 'terminal.json', {'state': 'INTERRUPTED' if isinstance(error, KeyboardInterrupt) else 'FAILED',
            'at': stamp(), 'failure': {'type': type(error).__name__, 'message': str(error)}})
        raise
    report = analyze(root)
    write_new(root / 'analysis' / f'{stamp()}-{uuid.uuid4().hex[:8]}.json', report)
    return report


def ratio_interval(values, rules):
    rng = random.Random(rules['seed'])
    draws = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(rules['draws']))
    import math
    tail = (1 - rules['confidenceLevel']) / 2
    return [draws[math.floor(tail * (len(draws) - 1))], draws[math.ceil((1 - tail) * (len(draws) - 1))]]


def analyze(root):
    spec = read_json(root / 'original-study.json')
    registration = read_json(root / 'registration.json')
    cc.check(registration['specification'] == spec and registration['toolkit'] == cr.toolkit_hashes(), 'Study registration or toolkit changed')
    cc.check(read_json(root / 'terminal.json')['state'] == 'COMPLETE', 'Incomplete/failed study has no calibration recommendation')
    samples, raw, runtimes = {}, {}, {}
    for entry in spec['campaigns']:
        label = entry['label']
        probe = read_json(root / 'probes' / f'{label}.json')
        run = Path(probe['run'])
        cc.check(probe['manifestHash'] == sha(run / 'manifest.json'), 'Registered probe archive changed')
        config, blocks, runtime = cr.verify(run)
        runtimes[label] = runtime
        raw[label] = {'run': str(run), 'runtimeHash': runtime['runtimeHash'], 'resources': config['resources'],
                      'warmup': config['warmup'], 'instrumentation': config['instrumentation']}
        rows = cr.observations(run, blocks)
        cc.check(len(rows) == sum(len(block['cases']) for block in blocks), 'Missing calibration observations')
        values = {}
        for row in rows.values():
            cc.check(row['state'] == 'SUCCEEDED', 'Failed calibration observation')
            directory = Path(row['path'])
            request = read_json(directory / 'request.json')
            receipt = cr.adapter_receipt(directory / 'adapter-receipt.json', request, config)
            cc.check(receipt['evidenceKind'] == 'benchmark' and cr.benchmark_result(receipt['result'], request) is True, 'Calibration needs independently valid real benchmark results')
            case = row['case']
            key = canonical({name: case[name] for name in ('datasetHash', 'targetHash', 'budgetHash', 'solverSeed', 'fork', 'scoreVersion', 'modelVersion', 'routingIdentity', 'configurationHash')})
            cc.check(key not in values, 'Duplicate calibration observation')
            values[key] = ca.measurements(receipt['result'])['values'][spec['rules']['metric']]
            cc.check(values[key] is not None and values[key] > 0, 'Calibration metric unavailable or zero')
        samples[label] = values
    reference = samples[spec['rules']['referenceLabel']]
    observed_reference = copy.deepcopy(runtimes[spec['rules']['referenceLabel']]['observed'])
    if spec['kind'] == 'concurrency':
        observed_reference['affinity'].pop('slots')
    for runtime in runtimes.values():
        observed = copy.deepcopy(runtime['observed'])
        if spec['kind'] == 'concurrency':
            observed['affinity'].pop('slots')
        cc.check(observed == observed_reference, 'Observed hardware, JDK, flags or available affinity changed between calibration probes')
    comparisons = []
    for label, values in samples.items():
        cc.check(set(values) == set(reference), 'Calibration pairing mismatch')
        if label == spec['rules']['referenceLabel']:
            continue
        # Forks are independent fresh processes for this fixed-work calibration. Dataset effects stay separate.
        groups = {}
        for key, value in values.items():
            import json
            identity = json.loads(key)
            group = canonical({name: identity[name] for name in ('datasetHash', 'budgetHash', 'configurationHash')})
            groups.setdefault(group, []).append(value / reference[key])
        evidence = []
        tolerance = spec['rules']['tolerancePercent'] / 100
        for group, ratios in sorted(groups.items()):
            cc.check(len(ratios) >= spec['rules']['minimumRepetitions'], 'Insufficient calibration repetitions')
            bounds = ratio_interval(ratios, spec['rules'])
            stable = bounds[0] >= 1 - tolerance and bounds[1] <= 1 + tolerance
            evidence.append({'group': group, 'ratios': ratios, 'meanRatio': statistics.mean(ratios), 'interval': bounds, 'stable': stable})
        comparisons.append({'label': label, 'groups': evidence,
            'disposition': 'diagnostic-only' if spec['kind'] == 'profile' else 'within-registered-tolerance' if all(row['stable'] for row in evidence) else 'ambiguous-register-new-attempt'})
    return {'version': 1, 'kind': spec['kind'], 'rules': spec['rules'], 'probes': raw, 'comparisons': comparisons,
            'recommendation': None, 'reason': 'No automatic defaults or production promotion; ambiguous studies require a new explicit registration',
            'scope': 'Fixed-work synthetic offline calibration; caller/cache and production allowances remain separate'}
