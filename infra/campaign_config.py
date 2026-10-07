"""Version 2 campaign contract. No defaults, profile aliases, or measured estimates."""
from datetime import datetime
import itertools
import math
import random
import re
from decimal import Decimal, InvalidOperation

from experiment_config import canonical, digest, fields


def check(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low=1, high=2147483647):
    check(type(value) is int and low <= value <= high, f'Expected integer {low}..{high}')


def text(value):
    check(isinstance(value, str) and bool(value.strip()), 'Nonempty string required')


def slug(value):
    check(isinstance(value, str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,59}', value), 'Invalid identity slug')


def choice(value, *options):
    check(type(value) is type(options[0]) and value in options, f'Expected one of {options}')


def strings(value, nonempty=True):
    check(isinstance(value, list) and (bool(value) or not nonempty), 'Array required')
    for item in value:
        text(item)
    unique(value)


def unique(items):
    check(len({canonical(v) for v in items}) == len(items), 'Duplicate values or cases')


def number(value, minimum=0):
    check(type(value) in (int, float) and math.isfinite(value) and value >= minimum, 'Invalid finite number')


def artifact(value):
    fields(value, ['path', 'sha256'])
    text(value['path'])
    check(isinstance(value['sha256'], str) and re.fullmatch('[0-9a-f]{64}', value['sha256']), 'SHA-256 required')


def validate(config):
    base_fields = ['version', 'name', 'purpose', 'edition', 'layer', 'controlId', 'datasets',
        'configurations', 'solverSeeds', 'forks', 'budgets', 'warmup', 'runtime', 'resources',
        'instrumentation', 'analysis', 'applicationLoad', 'execution', 'estimation', 'outputLocation', 'adapter']
    fields(config, base_fields + (['policy'] if 'policy' in config else []))
    if 'policy' in config:
        policy = config['policy']
        fields(policy, ['regularWindowThreshold', 'utilizationThreshold', 'fairnessAllowance', 'bookingDeadlineMs'])
        integer(policy['regularWindowThreshold'], 0)
        integer(policy['bookingDeadlineMs'], 1000, 5000)
        for name in ('utilizationThreshold', 'fairnessAllowance'):
            text(policy[name])
            try:
                value = Decimal(policy[name])
                check(value.is_finite() and 0 <= value <= 1, 'Policy ratio outside 0..1')
                check(format(value.normalize(), 'f') == policy[name], 'Canonical policy decimal required')
            except InvalidOperation as error:
                raise ValueError('Invalid policy decimal') from error
    choice(config['version'], 2)
    slug(config['name'])
    text(config['purpose'])
    choice(config['edition'], 'COMMUNITY')
    choice(config['layer'], 'solver', 'policy', 'workflow')
    if config['layer'] == 'workflow':
        fields(config['applicationLoad'], ['operation', 'mode', 'requestsPerCase', 'requestsPerSecond', 'concurrency', 'schedulerCache', 'providerCache', 'timeoutMs', 'observeCancellation', 'deployment', 'endpointIdentity'])
        choice(config['applicationLoad']['operation'], 'daily-preview', 'booking-offer')
    for key in ('datasets', 'configurations', 'solverSeeds', 'budgets'):
        check(isinstance(config[key], list) and bool(config[key]), f'Nonempty {key} required')
        unique(config[key])
    integer(config['forks'])
    for seed in config['solverSeeds']:
        integer(seed, 0)
    if any(isinstance(dataset, dict) and dataset.get('origin') != 'contract-fixture' for dataset in config['datasets']):
        check('policy' in config, 'Real benchmarks require explicit policy')
    for dataset in config['datasets']:
        fields(dataset, ['id', 'input', 'family', 'role', 'cohort', 'origin', 'datasetSeed',
                         'scoreVersion', 'modelVersion', 'routingIdentity', 'target'])
        slug(dataset['id'])
        artifact(dataset['input'])
        for key in ('family', 'scoreVersion', 'modelVersion', 'routingIdentity'):
            text(dataset[key])
        choice(dataset['role'], 'tuning', 'confirmation')
        choice(dataset['cohort'], 'assigned', 'cold', 'partial', 'repair', 'invalid-input')
        choice(dataset['origin'], 'synthetic', 'historical', 'legacy-fixture', 'contract-fixture')
        if dataset['datasetSeed'] is not None:
            integer(dataset['datasetSeed'], 0)
        check((dataset['origin'] == 'synthetic') == (dataset['datasetSeed'] is not None),
              'Synthetic datasets require an independent datasetSeed; other origins use null')
        if dataset['target'] is not None:
            artifact(dataset['target'])
        check(config['layer'] == 'solver' or dataset['target'] is None,
              'Policy/workflow layers create their own reference, never a frozen target')
    unique([d['id'] for d in config['datasets']])
    unique([d['input']['sha256'] for d in config['datasets']])
    cohorts = {d['cohort'] for d in config['datasets']}
    check('invalid-input' not in cohorts or cohorts == {'invalid-input'}, 'Keep invalid-input campaigns separate')
    check('repair' not in cohorts or cohorts == {'repair'}, 'Keep repair campaigns separate')
    for treatment in config['configurations']:
        fields(treatment, ['id', 'acceptor', 'acceptorSize', 'acceptedCountLimit', 'selectedCountLimit',
            'moves', 'environmentMode', 'moveThreads', 'nativeParallelBenchmarkCount', 'termination'])
        slug(treatment['id'])
        choice(treatment['acceptor'], 'TABU', 'LATE_ACCEPTANCE')
        for key in ('acceptorSize', 'acceptedCountLimit', 'selectedCountLimit'):
            integer(treatment[key])
        check(treatment['acceptedCountLimit'] <= treatment['selectedCountLimit'], 'Accepted count exceeds selected count')
        check(isinstance(treatment['moves'], list) and bool(treatment['moves']), 'Moves required')
        for move in treatment['moves']:
            fields(move, ['family', 'weight'])
            choice(move['family'], 'listChange', 'listSwap', 'subListChange', 'subListSwap')
            number(move['weight'], .000001)
        unique([m['family'] for m in treatment['moves']])
        choice(treatment['environmentMode'], 'NO_ASSERT', 'FULL_ASSERT', 'NON_INTRUSIVE_FULL_ASSERT', 'PHASE_ASSERT')
        choice(treatment['moveThreads'], 'NONE')
        choice(treatment['nativeParallelBenchmarkCount'], 1)
        policy = treatment['termination']
        fields(policy, ['kind', 'scope', 'spentCap', 'stepCap', 'windowMs', 'minimumImprovementRatio', 'unimprovedMs'])
        choice(policy['kind'], 'fixed', 'diminished-returns', 'unimproved-time')
        choice(policy['scope'], 'solver', 'local-search')
        choice(policy['spentCap'], True)
        if policy['stepCap'] is not None:
            integer(policy['stepCap'])
        for key in ('windowMs', 'unimprovedMs'):
            if policy[key] is not None:
                integer(policy[key])
        if policy['minimumImprovementRatio'] is not None:
            number(policy['minimumImprovementRatio'], .000001)
        check((policy['windowMs'] is not None and policy['minimumImprovementRatio'] is not None)
              if policy['kind'] == 'diminished-returns' else
              (policy['windowMs'] is None and policy['minimumImprovementRatio'] is None), 'Inapplicable termination fields must be null')
        check((policy['unimprovedMs'] is not None) == (policy['kind'] == 'unimproved-time'), 'Invalid unimproved field')
    unique([c['id'] for c in config['configurations']])
    check(len({c['environmentMode'] for c in config['configurations']}) == 1, 'Assertion modes require separate runtime cohorts')
    unique([{k: v for k, v in c.items() if k != 'id'} for c in config['configurations']])
    check(config['controlId'] in [c['id'] for c in config['configurations']], 'Selected control required')
    check(any(c['termination']['kind'] == 'fixed' for c in config['configurations']), 'Fixed-budget control required')
    for budget in config['budgets']:
        fields(budget, ['id', 'purpose', 'phase', 'operationMs', 'searchMs', 'referenceMs', 'fairnessMs',
                        'repairMs', 'validationReserveMs', 'transferUnusedToFairness'])
        slug(budget['id'])
        choice(budget['purpose'], 'production', 'longer-budget', 'contract-test')
        choice(budget['phase'], 'reference', 'fairness', 'repair', 'pipeline', 'booking')
        for key in ('operationMs', 'searchMs', 'validationReserveMs'):
            integer(budget[key])
        for key in ('referenceMs', 'fairnessMs', 'repairMs'):
            integer(budget[key], 0)
        choice(budget['transferUnusedToFairness'], True, False)
        phase_total = budget['referenceMs'] + budget['fairnessMs'] + budget['repairMs']
        check(phase_total == (0 if budget['phase'] == 'booking' else budget['searchMs']), 'Phase allowances must sum to search allowance')
        check(budget['searchMs'] + budget['validationReserveMs'] <= budget['operationMs'], 'Search plus reserve exceeds operation')
        if config['layer'] == 'solver':
            check(budget['phase'] not in ('pipeline', 'booking'), 'Solver layer selects one daily phase')
            check(budget[budget['phase'] + 'Ms'] == budget['searchMs'] and not budget['transferUnusedToFairness'], 'Solver phase allocation mismatch')
            if budget['phase'] == 'fairness':
                check(all(d['target'] is not None for d in config['datasets']), 'Solver fairness needs frozen targets')
        else:
            booking = config['layer'] == 'workflow' and config['applicationLoad']['operation'] == 'booking-offer' and 'policy' in config
            check(budget['phase'] == ('booking' if booking else 'repair' if cohorts == {'repair'} else 'pipeline'), 'Policy/workflow phase mismatch')
            if budget['phase'] == 'pipeline':
                check(budget['referenceMs'] > 0 and budget['fairnessMs'] > 0 and budget['repairMs'] == 0, 'Pipeline needs reference and fairness')
        if budget['phase'] == 'booking':
            check(not budget['transferUnusedToFairness'] and budget['operationMs'] == config['policy']['bookingDeadlineMs'], 'Booking allowance must match explicit policy')
        elif budget['purpose'] == 'production' and config['layer'] != 'solver':
            check(budget['operationMs'] == 20000 and budget['searchMs'] == 15000 and
                  (budget['referenceMs'] == 10000 if budget['phase'] == 'pipeline' else budget['repairMs'] == 15000),
                  'Production daily allowances must be preserved')
    unique([b['id'] for b in config['budgets']])
    warm = config['warmup']
    fields(warm, ['millisecondsPerFreshJvm', 'paths', 'disposableInputs', 'calibrationMs', 'calibrationRepetitions', 'stabilityTolerancePercent'])
    integer(warm['millisecondsPerFreshJvm'], 0)
    strings(warm['paths'])
    check(set(warm['paths']) <= {'reference', 'fairness', 'repair', 'daily-policy', 'daily-preview', 'booking-offer', 'input-contract'}, 'Unsupported warmup path')
    if cohorts == {'invalid-input'}:
        check(warm['paths'] == ['input-contract'] and config['layer'] == 'solver', 'Invalid inputs require contract-only warmup')
    elif config['layer'] == 'solver':
        check({b['phase'] for b in config['budgets']} <= set(warm['paths']), 'Warmup must cover every selected phase')
    elif config['layer'] == 'policy':
        check(('repair' if cohorts == {'repair'} else 'daily-policy') in warm['paths'], 'Policy warmup path required')
    choice(warm['disposableInputs'], True)
    check(isinstance(warm['calibrationMs'], list) and bool(warm['calibrationMs']), 'Calibration probes required')
    unique(warm['calibrationMs'])
    for probe in warm['calibrationMs']:
        integer(probe)
    integer(warm['calibrationRepetitions'])
    number(warm['stabilityTolerancePercent'])
    runtime = config['runtime']
    fields(runtime, ['java', 'jdkMajor', 'heapMinMiB', 'heapMaxMiB', 'gc', 'activeProcessorCount', 'flags', 'environment', 'processLifetime'])
    artifact(runtime['java'])
    choice(runtime['jdkMajor'], 25)
    for key in ('heapMinMiB', 'heapMaxMiB', 'activeProcessorCount'):
        integer(runtime[key])
    check(runtime['heapMinMiB'] <= runtime['heapMaxMiB'], 'Heap bounds reversed')
    choice(runtime['gc'], 'SerialGC', 'G1GC')
    choice(runtime['processLifetime'], 'one-case-per-jvm')
    strings(runtime['flags'], False)
    check(all(f in ['-Dfile.encoding=UTF-8', '-XX:+AlwaysPreTouch', '-XX:+UseStringDeduplication'] for f in runtime['flags']), 'Unsupported JVM flag or experiment override')
    check('-XX:+UseStringDeduplication' not in runtime['flags'] or runtime['gc'] == 'G1GC', 'String deduplication needs G1GC')
    check(isinstance(runtime['environment'], dict) and set(runtime['environment']) <= {'LANG', 'LC_ALL', 'TZ'}, 'Only explicit noncredential environment fields supported')
    for value in runtime['environment'].values():
        text(value)
    resources = config['resources']
    fields(resources, ['parallelCases', 'totalCpuAllocation', 'cpuPerCase', 'affinityPolicy', 'concurrencyCalibration', 'memoryLimitMiB'])
    for key in ('parallelCases', 'totalCpuAllocation', 'cpuPerCase', 'memoryLimitMiB'):
        integer(resources[key])
    choice(resources['affinityPolicy'], 'disjoint-physical-cores')
    check(resources['cpuPerCase'] == runtime['activeProcessorCount'], 'CPU and JVM counts disagree')
    check(resources['parallelCases'] * resources['cpuPerCase'] <= resources['totalCpuAllocation'], 'CPU allocation exceeded')
    check(resources['parallelCases'] * runtime['heapMaxMiB'] <= resources['memoryLimitMiB'], 'Heap allocation exceeds declared memory limit')
    check(isinstance(resources['concurrencyCalibration'], list) and bool(resources['concurrencyCalibration']), 'Concurrency calibration required')
    unique(resources['concurrencyCalibration'])
    for value in resources['concurrencyCalibration']:
        integer(value)
    inst = config['instrumentation']
    fields(inst, ['cohort', 'statistics', 'jfr', 'sampleIntervalMs', 'internalDiagnostics', 'constraintProfiling'])
    choice(inst['cohort'], 'quality-basic', 'diagnostic')
    strings(inst['statistics'], False)
    check(set(inst['statistics']) <= {'BEST_SCORE', 'MOVE_EVALUATION_SPEED', 'SCORE_CALCULATION_SPEED', 'MEMORY_USE'}, 'Unsupported statistic')
    choice(inst['jfr'], 'disabled', 'default', 'profile')
    integer(inst['sampleIntervalMs'])
    choice(inst['internalDiagnostics'], True, False)
    choice(inst['constraintProfiling'], False)
    check(inst['cohort'] == 'diagnostic' or (inst['jfr'] == 'disabled' and not inst['internalDiagnostics']), 'Profiling requires a separate diagnostic cohort')
    if cohorts == {'invalid-input'}:
        check(not inst['statistics'] and not inst['internalDiagnostics'], 'Invalid input contracts do not measure native solver statistics')
    analysis = config['analysis']
    fields(analysis, ['method', 'pairKeys', 'draws', 'seed', 'confidenceLevel', 'selection', 'latencyNoninferiorityPercent', 'failureTolerance', 'costDifferenceUpperBoundCents'])
    choice(analysis['method'], 'inventory-only', 'paired-dataset-bootstrap')
    strings(analysis['pairKeys'])
    check(analysis['pairKeys'] == ['datasetHash', 'targetHash', 'budgetHash', 'solverSeed', 'fork', 'scoreVersion', 'modelVersion', 'routingIdentity', 'runtimeHash'], 'Complete pairing identity required')
    choice(analysis['draws'], 10000)
    integer(analysis['seed'], 0)
    choice(analysis['confidenceLevel'], .95)
    choice(analysis['selection'], 'tuning-only', 'frozen-confirmation')
    check(all(d['role'] == ('tuning' if analysis['selection'] == 'tuning-only' else 'confirmation') for d in config['datasets']), 'Dataset role/analysis selection mismatch')
    for key in ('latencyNoninferiorityPercent', 'failureTolerance'):
        number(analysis[key])
    check(type(analysis['costDifferenceUpperBoundCents']) is int, 'Signed integer cents required')
    load = config['applicationLoad']
    if config['layer'] == 'workflow':
        fields(load, ['operation', 'mode', 'requestsPerCase', 'requestsPerSecond', 'concurrency', 'schedulerCache', 'providerCache', 'timeoutMs', 'observeCancellation', 'deployment', 'endpointIdentity'])
        choice(load['operation'], 'daily-preview', 'booking-offer')
        check(load['operation'] in warm['paths'], 'Warmup must cover the application path')
        choice(load['mode'], 'paced-arrival')
        for key in ('requestsPerCase', 'concurrency', 'timeoutMs'):
            integer(load[key])
        number(load['requestsPerSecond'], .000001)
        for key in ('schedulerCache', 'providerCache'):
            choice(load[key], 'cold', 'warm')
        choice(load['observeCancellation'], True)
        choice(load['deployment'], 'embedded', 'remote')
        text(load['endpointIdentity'])
    else:
        check(load is None, 'Inapplicable applicationLoad must be null')
    execution = config['execution']
    fields(execution, ['orderSeed', 'failurePolicy', 'automaticRetries', 'resumePolicy', 'campaignCutoffUtc', 'processGraceMs'])
    integer(execution['orderSeed'], 0)
    choice(execution['failurePolicy'], 'stop-new-blocks')
    choice(execution['automaticRetries'], False)
    choice(execution['resumePolicy'], 'hash-verified-missing-cases')
    integer(execution['processGraceMs'])
    if execution['campaignCutoffUtc'] is not None:
        text(execution['campaignCutoffUtc'])
        try:
            parsed = datetime.fromisoformat(execution['campaignCutoffUtc'].replace('Z', '+00:00'))
        except ValueError as error:
            raise ValueError('Invalid cutoff timestamp') from error
        check(parsed.tzinfo is not None and parsed.utcoffset().total_seconds() == 0, 'Cutoff must be an absolute UTC timestamp')
    estimate = config['estimation']
    fields(estimate, ['startupMsPerJvm', 'preparationMsPerCase', 'validationMsPerCase', 'reportingMsPerCase', 'referenceSetupMsPerDataset', 'serialAnalysisMs'])
    for value in estimate.values():
        number(value)
    text(config['outputLocation'])
    adapter = config['adapter']
    fields(adapter, ['protocol', 'jar'])
    choice(adapter['protocol'], 'waterflex-campaign-jvm-v1')
    artifact(adapter['jar'])
    return config


def resolve(config, base):
    import copy
    result = copy.deepcopy(validate(config))
    for spec in [result['runtime']['java'], result['adapter']['jar'],
                 *[d['input'] for d in result['datasets']],
                 *[d['target'] for d in result['datasets'] if d['target'] is not None]]:
        spec['path'] = str((base / spec['path']).resolve())
    result['outputLocation'] = str((base / result['outputLocation']).resolve())
    return result


def expand(config):
    validate(config)
    blocks = []
    for dataset, budget, seed, fork in itertools.product(config['datasets'], config['budgets'], config['solverSeeds'], range(config['forks'])):
        identity = {'datasetId': dataset['id'], 'datasetHash': dataset['input']['sha256'],
            'targetHash': None if dataset['target'] is None else dataset['target']['sha256'],
            'budgetId': budget['id'], 'budgetHash': digest(budget), 'solverSeed': seed, 'fork': fork, 'scoreVersion': dataset['scoreVersion'],
            'modelVersion': dataset['modelVersion'], 'routingIdentity': dataset['routingIdentity']}
        block_id = digest(identity)
        treatments = list(config['configurations'])
        offset = len(blocks) % len(treatments)
        treatments = treatments[offset:] + treatments[:offset]
        cases = []
        for treatment in treatments:
            row = {**identity, 'blockId': block_id, 'configurationId': treatment['id'], 'configurationHash': digest(treatment)}
            row['id'] = digest(row)
            cases.append(row)
        blocks.append({'id': block_id, 'cases': cases})
    random.Random(config['execution']['orderSeed']).shuffle(blocks)
    unique([b['id'] for b in blocks])
    unique([c['id'] for b in blocks for c in b['cases']])
    return blocks


def estimate(config):
    blocks = expand(config)
    overhead = config['estimation']
    extra = sum(overhead[k] for k in ('startupMsPerJvm', 'preparationMsPerCase', 'validationMsPerCase', 'reportingMsPerCase'))
    budgets = {b['id']: b for b in config['budgets']}
    warm = config['warmup']['millisecondsPerFreshJvm'] * len(config['warmup']['paths'])
    def allowance(case):
        budget = budgets[case['budgetId']]
        load = config['applicationLoad']
        if load is not None:
            return (load['requestsPerCase'] - 1) / load['requestsPerSecond'] * 1000 + load['timeoutMs']
        return budget['operationMs']
    serial, parallel, measured = 0, 0, 0
    workers = config['resources']['parallelCases']
    for block in blocks:
        slots = [0] * workers
        for case in block['cases']:
            measured += allowance(case)
            duration = allowance(case) + warm + extra
            serial += duration
            i = min(range(workers), key=lambda x: slots[x])
            slots[i] += duration
        parallel += max(slots)
    setup = len(config['datasets']) * overhead['referenceSetupMsPerDataset']
    tail = setup + overhead['serialAnalysisMs']
    count = sum(len(b['cases']) for b in blocks)
    return {'version': 2, 'comparisonBlocks': len(blocks), 'cases': count, 'freshJvms': count,
        'warmupInvocations': count * len(config['warmup']['paths']), 'warmupMs': count * warm,
        'operationAllowanceMs': measured, 'declaredOverheadMs': count * extra,
        'referenceSetupMs': setup, 'serialEstimateMs': serial + tail,
        'optimisticParallelEstimateMs': parallel + tail, 'observedRuntimeMs': None,
        'reason': 'Estimates use declared allowances and overhead, not measured runtime. Pipeline counted once; comparison blocks do not overlap.'}
