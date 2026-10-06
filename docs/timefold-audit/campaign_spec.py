"""Check the report's proposed parameter inventory, not the production runner schema.

This deliberately accepts only the illustrated design. Implementing the runner,
artifact loading and effective Timefold configuration requires a separate change.
"""
import math
from datetime import datetime


def number(value, minimum=0, exclusive=False):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError('Finite number required')
    if value < minimum or (exclusive and value == minimum):
        raise ValueError('Number outside allowed range')


def positive(value):
    number(value, exclusive=True)


def integer(value):
    if type(value) is not int:
        raise ValueError('Integer required')


def count(value):
    integer(value)
    positive(value)


def string(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Nonempty string required')


def boolean(value):
    if type(value) is not bool:
        raise ValueError('Boolean required')


def choice(*values):
    def check(value):
        if type(value) is not type(values[0]) or value not in values:
            raise ValueError(f'Expected one of {values}')
    return check


def cutoff(value):
    if value is None:
        return
    string(value)
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('Campaign cutoff needs a timezone')


def validate(value, schema, path='configuration'):
    if isinstance(schema, dict):
        if not isinstance(value, dict) or set(value) != set(schema):
            raise ValueError(f'{path}: missing or unknown fields')
        for key, rule in schema.items():
            validate(value[key], rule, f'{path}.{key}')
    elif isinstance(schema, list):
        if not isinstance(value, list) or not value:
            raise ValueError(f'{path}: nonempty array required')
        for index, item in enumerate(value):
            validate(item, schema[0], f'{path}[{index}]')
    else:
        try:
            schema(value)
        except (ValueError, TypeError) as error:
            raise ValueError(f'{path}: {error}') from error


SCHEMA = {
    'schemaVersion': choice(2), 'kind': choice('proposed design only'),
    'campaignId': string, 'purpose': string, 'edition': choice('COMMUNITY'),
    'layer': choice('solver'), 'deploymentModel': choice('long-lived'),
    'scoringVersion': string, 'controlId': string,
    'datasets': {
        'generatorVersion': string, 'fleetSizes': [count],
        'scenarioFamilies': [string], 'datasetSeeds': [integer],
        'initialState': choice('assigned'), 'role': choice('tuning'),
        'routingIdentity': string,
    },
    'algorithms': [{
        'id': string, 'acceptor': choice('TABU', 'LATE_ACCEPTANCE'),
        'acceptorParameter': count, 'acceptedCountLimit': count,
        'selectedCountLimit': count,
        'moves': [{'family': choice('listChange', 'listSwap'), 'weight': positive}],
    }],
    'solverSeeds': [integer], 'processRepetitions': count,
    'budgetCohorts': [{'phase': choice('reference'), 'seconds': positive,
                       'purpose': choice('production', 'longer-budget')}],
    'phaseAllocation': {'referenceFraction': positive, 'transferUnusedToFairness': boolean,
                        'validationReserveSeconds': positive},
    'fairnessReference': {'applicable': choice(False), 'reason': string,
                          'controlId': string, 'seed': integer, 'budgetSeconds': positive},
    'terminationPolicies': [{
        'id': string, 'kind': choice('fixed', 'diminished-returns', 'unimproved-time'),
        'solverSpentCap': choice(True), 'phaseScope': choice('local-search'),
        'windowSeconds': positive, 'minimumImprovementRatio': positive,
        'unimprovedSeconds': positive,
    }],
    'warmup': {'secondsPerFreshJvm': positive, 'paths': [string],
               'disposableInputs': choice(True), 'calibrationSeconds': [positive],
               'calibrationRepetitions': count, 'stabilityTolerancePercent': positive},
    'runtime': {'jvmPath': string, 'jdkVersion': string, 'heapMinMiB': count,
                'heapMaxMiB': count, 'gc': choice('SerialGC', 'G1GC'),
                'activeProcessorCount': count, 'flags': [string],
                'environmentMode': choice('NO_ASSERT'),
                'processLifetime': choice('one-case-per-jvm'),
                'moveThreads': choice('NONE'), 'nativeParallelBenchmarkCount': choice(1)},
    'resources': {'parallelCases': count, 'totalCpuAllocation': count,
                  'cpuPerCase': count, 'affinityPolicy': choice('disjoint-physical-cores'),
                  'concurrencyCalibration': [count]},
    'instrumentation': {'cohort': choice('quality-basic'), 'statistics': [string],
                        'jfrEnabled': boolean, 'jfrSettings': string,
                        'sampleIntervalMs': count, 'internalDiagnostics': boolean,
                        'constraintProfiling': choice(False)},
    'analysis': {'pairKeys': [string], 'method': choice('paired-dataset-bootstrap'),
                 'draws': count, 'seed': integer, 'confidenceLevel': positive,
                 'scenarioGroups': [string], 'selection': choice('tuning-only'),
                 'latencyNoninferiorityPercent': number, 'failureTolerance': number,
                 'costDifferenceUpperBoundCents': number},
    'applicationLoad': {'applicable': choice(False), 'reason': string,
                        'operation': string, 'mode': choice('paced-arrival'),
                        'requestsPerCase': count, 'requestsPerSecond': positive,
                        'concurrency': count, 'cacheState': string,
                        'timeoutSeconds': positive, 'observeCancellation': boolean},
    'execution': {'orderSeed': integer, 'failurePolicy': choice('stop-new-blocks'),
                  'automaticRetries': choice(False), 'resumePolicy': choice('hash-verified-blocks'),
                  'campaignCutoffUtc': cutoff},
    'estimation': {'startupSecondsPerCase': number, 'preparationSecondsPerCase': number,
                   'validationSecondsPerCase': number, 'reportingSecondsPerCase': number,
                   'serialAnalysisSeconds': number},
    'outputLocation': string,
}


def unique(values, name):
    if len(set(values)) != len(values):
        raise ValueError(f'Duplicate {name}')


def verify_configuration(config):
    validate(config, SCHEMA)
    for name in ('solverSeeds',):
        unique(config[name], name)
    datasets = config['datasets']
    for name in ('fleetSizes', 'scenarioFamilies', 'datasetSeeds'):
        unique(datasets[name], name)
    for name in ('algorithms', 'terminationPolicies'):
        unique([item['id'] for item in config[name]], name)
    if config['controlId'] not in {a['id'] for a in config['algorithms']}:
        raise ValueError('Control must be a selected algorithm')
    if config['fairnessReference']['controlId'] != config['controlId']:
        raise ValueError('Reference control identity mismatch')
    if 'fixed' not in {p['kind'] for p in config['terminationPolicies']}:
        raise ValueError('Fixed-budget control required')
    if config['phaseAllocation']['referenceFraction'] >= 1:
        raise ValueError('Reference fraction must leave fairness allowance')
    if config['analysis']['confidenceLevel'] >= 1:
        raise ValueError('Confidence level must be below one')
    runtime, resources = config['runtime'], config['resources']
    if runtime['heapMinMiB'] > runtime['heapMaxMiB']:
        raise ValueError('Heap minimum exceeds maximum')
    if resources['parallelCases'] * resources['cpuPerCase'] > resources['totalCpuAllocation']:
        raise ValueError('Parallel cases exceed CPU allocation')
    for algorithm in config['algorithms']:
        unique([m['family'] for m in algorithm['moves']], 'move families')
    # All values are illustrative requested settings. Artifact loading, JVM flag
    # resolution and pinned-release configuration smoke tests are future work.
    return [len(config['algorithms']), len(config['terminationPolicies']),
            math.prod(len(datasets[n]) for n in ('fleetSizes', 'scenarioFamilies', 'datasetSeeds')),
            len(config['solverSeeds']), config['processRepetitions']]
