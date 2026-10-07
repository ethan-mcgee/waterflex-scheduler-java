"""Append-only campaign orchestration for the pinned, fresh-JVM adapter protocol.

Production layer adapters are separate artifacts. This runner never interprets a
fixture as solver evidence and never supplies replacement measurements.
"""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from datetime import datetime, timezone
import ctypes
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

import campaign_config as cc
from experiment_config import digest, fields, read_json, unique_object
from experiment_runtime import command, sha, stamp, topology, tree_hashes, verify_files, write_new, StopRequested

TOOLKIT = ['campaign_config.py', 'campaign_runtime.py', 'campaign_analysis.py', 'campaign_study.py', 'campaign_profile.py', 'experiments.py', 'experiment_config.py', 'experiment_runtime.py']


def toolkit_hashes():
    return {name: sha(Path(__file__).parent / name) for name in TOOLKIT}


def bytes_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def checked_artifact(spec):
    path = Path(spec['path'])
    cc.check(path.is_file() and not path.is_symlink() and sha(path) == spec['sha256'], f'Artifact hash mismatch: {path}')
    return path


def environment(config):
    # Process launch support only. No credentials or undeclared JVM/application controls.
    allowed = {'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'TMPDIR', 'PATH', 'PATHEXT'}
    env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    return {**env, **config['runtime']['environment']}


def java_flags(config):
    r = config['runtime']
    return [f'-Xms{r["heapMinMiB"]}m', f'-Xmx{r["heapMaxMiB"]}m', f'-XX:+Use{r["gc"]}',
            f'-XX:ActiveProcessorCount={r["activeProcessorCount"]}', *r['flags']]


def linux_cores():
    cc.check(hasattr(os, 'sched_getaffinity'), 'Physical-core affinity unavailable on this platform')
    groups = {}
    for cpu in sorted(os.sched_getaffinity(0)):
        root = Path(f'/sys/devices/system/cpu/cpu{cpu}/topology')
        key = ((root / 'physical_package_id').read_text().strip(), (root / 'core_id').read_text().strip())
        groups.setdefault(key, []).append(cpu)
    return list(groups.values())


def resource_slots(config):
    r = config['resources']
    if os.name == 'nt':
        observed = topology()
        # Use one logical CPU per physical core. Explicit CPU units are physical cores.
        cores = [[mask.bit_length() - 1] for mask in observed['cores'] if not mask & 1]
    else:
        cores = [[group[0]] for group in linux_cores()]
    cc.check(len(cores) >= r['totalCpuAllocation'], 'Requested physical cores exceed available affinity')
    slots = []
    for slot in range(r['parallelCases']):
        cpus = [core[0] for core in cores[slot * r['cpuPerCase']:(slot + 1) * r['cpuPerCase']]]
        slots.append(cpus)
    return {'availablePhysicalCoreCpus': cores, 'slots': slots}


def hardware():
    if os.name == 'nt':
        class Memory(ctypes.Structure):
            _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong),
                        *[(k, ctypes.c_ulonglong) for k in ('total', 'available', 'pageTotal', 'pageAvailable', 'virtualTotal', 'virtualAvailable', 'extended')]]
        memory = Memory()
        memory.length = ctypes.sizeof(memory)
        cc.check(ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)), 'Cannot observe memory')
        total = memory.total
        model = platform.processor()
    else:
        total = os.sysconf('SC_PHYS_PAGES') * os.sysconf('SC_PAGE_SIZE')
        model = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                      if line.startswith('model name')), None)
    return {'platform': platform.platform(), 'machine': platform.machine(), 'cpuModel': model,
            'logicalCpus': os.cpu_count(), 'memoryBytes': total}


def activity():
    if os.name == 'nt':
        probe = subprocess.run(['tasklist', '/FO', 'CSV', '/NH'], capture_output=True, text=True, timeout=15, check=False)
        return {'processInventory': probe.stdout if probe.returncode == 0 else None,
                'unavailableReason': None if probe.returncode == 0 else f'tasklist exited {probe.returncode}',
                'scope': 'Names/PIDs/memory only, no arguments or environment; activity is observed, not isolation proof'}
    names = []
    for path in sorted(Path('/proc').glob('[0-9]*/comm')):
        try:
            names.append({'pid': int(path.parent.name), 'name': path.read_text().strip()})
        except OSError:
            pass  # A process can exit during an observational inventory.
    return {'processInventory': names, 'unavailableReason': None, 'scope': 'Names/PIDs only; activity is observed, not isolation proof'}


def observe(config):
    java = checked_artifact(config['runtime']['java'])
    modules = java.parent.parent / 'lib/modules'
    cc.check(modules.is_file(), 'Pinned full JDK with lib/modules required')
    flags = java_flags(config)
    probe = subprocess.run([str(java), *flags, '-XshowSettings:vm', '-version'], env=environment(config),
                           capture_output=True, text=True, timeout=30, check=False)
    cc.check(probe.returncode == 0, f'JVM settings probe failed: {probe.stderr}')
    version = re.search(r'(?:openjdk|java) version "(\d+)', probe.stderr + probe.stdout)
    cc.check(version is not None and int(version.group(1)) == config['runtime']['jdkMajor'], 'Observed JDK version mismatch')
    result = {'javaSha256': sha(java), 'jdkModulesSha256': sha(modules), 'jvmFlags': flags,
              'settingsProbe': probe.stderr + probe.stdout, 'hardware': hardware(), 'affinity': resource_slots(config),
              'environment': config['runtime']['environment'], 'python': sys.version}
    cc.check(result['hardware']['memoryBytes'] >= config['resources']['memoryLimitMiB'] * 1048576,
             'Declared memory allocation exceeds physical memory')
    return result


def runtime_identity(config, observed):
    # Instrumented/calibration/load-cache cohorts must never acquire an equal
    # pairing identity merely because they used the same JDK and CPU.
    return digest({'observed': observed, 'warmup': config['warmup'], 'instrumentation': config['instrumentation'],
                   'applicationLoad': config['applicationLoad'], 'resources': config['resources'],
                   'environmentMode': config['configurations'][0]['environmentMode']})


def new_run(config_path, base=None):
    original = config_path.read_bytes()
    parsed = json.loads(original.decode('utf-8-sig'), object_pairs_hook=unique_object,
                        parse_constant=lambda v: (_ for _ in ()).throw(ValueError(f'Invalid JSON: {v}')))
    config = cc.resolve(parsed, config_path.resolve().parent)
    # Fail invalid inputs before creating an archive. Preparation failures after
    # this boundary have their own immutable receipt and printed archive path.
    run = Path(config['outputLocation'] if base is None else base) / f'{stamp()}-{config["name"]}-{uuid.uuid4().hex[:8]}'
    run.mkdir(parents=True, exist_ok=False)
    print(f'Run: {run}', flush=True)
    bytes_new(run / 'original-config.json', original)
    write_new(run / 'config.json', config)
    write_new(run / 'blocks.json', cc.expand(config))
    write_new(run / 'estimate.json', cc.estimate(config))
    try:
        frozen = run / 'frozen'
        for name in TOOLKIT:
            bytes_new(frozen / 'toolkit' / name, (Path(__file__).parent / name).read_bytes())
        bytes_new(frozen / 'adapter.jar', checked_artifact(config['adapter']['jar']).read_bytes())
        for dataset in config['datasets']:
            bytes_new(frozen / 'datasets' / f'{dataset["id"]}.json', checked_artifact(dataset['input']).read_bytes())
            if dataset['target'] is not None:
                bytes_new(frozen / 'targets' / f'{dataset["id"]}.json', checked_artifact(dataset['target']).read_bytes())
        cc.check(sha(frozen / 'adapter.jar') == config['adapter']['jar']['sha256'], 'Adapter changed while freezing')
        for dataset in config['datasets']:
            cc.check(sha(frozen / 'datasets' / f'{dataset["id"]}.json') == dataset['input']['sha256'], 'Dataset changed while freezing')
            if dataset['target'] is not None:
                cc.check(sha(frozen / 'targets' / f'{dataset["id"]}.json') == dataset['target']['sha256'], 'Target changed while freezing')
        observed = observe(config)
        write_new(run / 'runtime.json', {'requested': {'runtime': config['runtime'], 'resources': config['resources']},
                   'observed': observed, 'runtimeHash': runtime_identity(config, observed), 'competingActivity': activity(), 'observedAt': stamp()})
        inputs = {name: sha(run / name) for name in ('original-config.json', 'config.json', 'blocks.json', 'estimate.json', 'runtime.json')}
        write_new(run / 'manifest.json', {'version': 2, 'inputs': inputs, 'frozen': tree_hashes(frozen), 'toolkit': toolkit_hashes()})
    except BaseException as error:
        write_new(run / 'preparation-failure.json', {'state': 'INTERRUPTED' if isinstance(error, KeyboardInterrupt) else 'FAILED',
                  'type': type(error).__name__, 'message': str(error), 'at': stamp()})
        raise
    return run


def verify(run):
    manifest = read_json(run / 'manifest.json')
    fields(manifest, ['version', 'inputs', 'frozen', 'toolkit'])
    cc.choice(manifest['version'], 2)
    cc.check(all(isinstance(manifest[k], dict) for k in ('inputs', 'frozen', 'toolkit')), 'Malformed manifest inventories')
    cc.check(set(manifest['inputs']) == {'original-config.json', 'config.json', 'blocks.json', 'estimate.json', 'runtime.json'}, 'Manifest input inventory mismatch')
    for name, expected in manifest['inputs'].items():
        cc.check(sha(run / name) == expected, f'Campaign input hash changed: {name}')
    cc.check(manifest['toolkit'] == toolkit_hashes(), 'Toolkit changed; use the frozen entry point or register a new campaign')
    verify_files(run / 'frozen', manifest['frozen'])
    config = cc.validate(read_json(run / 'config.json'))
    cc.check(read_json(run / 'blocks.json') == cc.expand(config), 'Saved expansion mismatch')
    cc.check(read_json(run / 'estimate.json') == cc.estimate(config), 'Saved estimate mismatch')
    return config, read_json(run / 'blocks.json'), read_json(run / 'runtime.json')


def observations(run, blocks):
    """Every dispatch, including orphaned/failed dispatches, permanently consumes its case ID."""
    cases = {c['id']: c for b in blocks for c in b['cases']}
    result = {}
    for path in sorted((run / 'attempts').glob('*/cases/*/dispatch.json')):
        dispatch = read_json(path)
        fields(dispatch, ['case', 'at', 'requestHash', 'affinityCpus'])
        cc.text(dispatch['at'])
        cc.text(dispatch['requestHash'])
        case = dispatch['case']
        cc.check(isinstance(case, dict) and 'id' in case, 'Malformed dispatched case')
        cc.check(case['id'] in cases and cases[case['id']] == case, 'Unknown or changed dispatched case')
        cc.check(path.parent.name == case['id'] and case['id'] not in result, 'Duplicate dispatched observation')
        terminal = path.parent / 'terminal.json'
        if terminal.is_file():
            receipt = read_json(terminal)
            fields(receipt, ['state', 'at', 'elapsedMs', 'failure', 'files'])
            cc.choice(receipt['state'], 'SUCCEEDED', 'FAILED', 'INTERRUPTED')
            cc.number(receipt['elapsedMs'])
            cc.text(receipt['at'])
            cc.check((receipt['failure'] is None) == (receipt['state'] == 'SUCCEEDED'), 'Failure/state mismatch')
            cc.check(receipt['failure'] is None or isinstance(receipt['failure'], dict), 'Structured failure required')
            actual = case_hashes(path.parent)
            cc.check(actual == receipt['files'], f'Case artifact hash changed: {case["id"]}')
            result[case['id']] = {'case': case, 'state': receipt['state'], 'path': str(path.parent)}
        else:
            result[case['id']] = {'case': case, 'state': 'ABANDONED', 'path': str(path.parent)}
    return result


def case_hashes(directory):
    return {p.relative_to(directory).as_posix(): sha(p) for p in sorted(directory.rglob('*'))
            if p.is_file() and p != directory / 'terminal.json'}


def adapter_receipt(path, request, config):
    cc.check(path.is_file() and path.stat().st_size <= 16 * 1024 * 1024, 'Missing/oversized adapter receipt')
    value = read_json(path)
    fields(value, ['protocol', 'caseId', 'requestHash', 'state', 'evidenceKind', 'warmup', 'runtime', 'instrumentation', 'result'])
    cc.choice(value['protocol'], 'waterflex-campaign-jvm-v1')
    cc.check(value['caseId'] == request['case']['id'] and value['requestHash'] == digest(request), 'Adapter receipt identity mismatch')
    cc.choice(value['state'], 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INVALID')
    cc.choice(value['evidenceKind'], 'benchmark', 'contract-fixture')
    cc.check((value['evidenceKind'] == 'contract-fixture') == (request['dataset']['origin'] == 'contract-fixture'), 'Fixture evidence cannot become benchmark evidence')
    warm = value['warmup']
    fields(warm, ['paths', 'disposableInputs', 'elapsedMsByPath'])
    cc.check(warm['paths'] == config['warmup']['paths'] and warm['disposableInputs'] is True, 'Warmup path/disposable-input mismatch')
    cc.check(isinstance(warm['elapsedMsByPath'], dict) and set(warm['elapsedMsByPath']) == set(warm['paths']), 'Warmup receipts required for every path in each fresh JVM')
    for elapsed in warm['elapsedMsByPath'].values():
        cc.number(elapsed, config['warmup']['millisecondsPerFreshJvm'])
    runtime = value['runtime']
    fields(runtime, ['inputArguments', 'availableProcessors', 'affinityCpus', 'pid'])
    cc.check(runtime['inputArguments'] == request['jvmFlags'], 'Adapter observed JVM flags differ from request')
    cc.integer(runtime['availableProcessors'])
    cc.check(runtime['availableProcessors'] == config['runtime']['activeProcessorCount'], 'Adapter observed CPU count differs')
    cc.check(isinstance(runtime['affinityCpus'], list) and all(type(cpu) is int for cpu in runtime['affinityCpus']), 'Integer affinity CPU receipt required')
    cc.check(runtime['affinityCpus'] == request['affinityCpus'], 'Adapter must observe actual process affinity')
    cc.integer(runtime['pid'])
    inst = value['instrumentation']
    fields(inst, ['enabledStatistics', 'unavailableStatistics', 'internalDiagnosticsEnabled', 'internalDiagnosticsFailureReason'])
    cc.strings(inst['enabledStatistics'], False)
    cc.check(isinstance(inst['unavailableStatistics'], list), 'Unavailable statistics array required')
    unavailable = []
    for item in inst['unavailableStatistics']:
        fields(item, ['name', 'value', 'reason'])
        cc.text(item['name'])
        cc.text(item['reason'])
        cc.check(item['value'] is None, 'Unavailable measurements must remain null')
        unavailable.append(item['name'])
    cc.unique([*inst['enabledStatistics'], *unavailable])
    cc.check(set(inst['enabledStatistics'] + unavailable) == set(config['instrumentation']['statistics']), 'Enabled/unavailable statistic inventory mismatch')
    cc.choice(inst['internalDiagnosticsEnabled'], True, False)
    if inst['internalDiagnosticsFailureReason'] is not None:
        cc.text(inst['internalDiagnosticsFailureReason'])
    cc.check(inst['internalDiagnosticsEnabled'] == config['instrumentation']['internalDiagnostics'] and
             inst['internalDiagnosticsFailureReason'] is None, 'Required diagnostic failure invalidates this experiment')
    cc.check(isinstance(value['result'], dict), 'Structured adapter result required')
    if value['evidenceKind'] == 'benchmark' and value['state'] == 'SUCCEEDED':
        benchmark_result(value['result'], request)
    cc.check(value['state'] == 'SUCCEEDED', f'Adapter outcome: {value["state"]}')
    return value


def benchmark_result(result, request):
    """Validate every field used for phase 11 inventory; raw diagnostics remain evidence."""
    layer = result.get('layer')
    if request['dataset']['cohort'] == 'invalid-input':
        fields(result, ['layer', 'expectedFailure', 'failureType', 'failureMessage'])
        cc.check(layer == 'input-contract' and result['expectedFailure'] is True, 'Invalid inputs must remain contract-only')
        cc.text(result['failureType'])
        cc.text(result['failureMessage'])
        return None
    cc.check(layer == request['layer'], 'Adapter result layer differs')
    repair_fields = ['repair'] if request['dataset']['cohort'] == 'repair' else []
    if repair_fields:
        repair = result.get('repair')
        fields(repair, ['timeToFeasibilityMs', 'unavailableReason', 'unresolvedCandidate', 'unresolvedRetained', 'terminalCandidateEligible', 'terminalRetainedEligible'])
        cc.check(repair['timeToFeasibilityMs'] is None, 'Independent intermediate feasibility is unavailable from final-only proposals')
        cc.text(repair['unavailableReason'])
        cc.strings(repair['unresolvedCandidate'], False)
        cc.strings(repair['unresolvedRetained'], False)
        cc.choice(repair['terminalCandidateEligible'], True, False)
        cc.choice(repair['terminalRetainedEligible'], True, False)
    if layer == 'solver':
        fields(result, ['layer', 'wallMs', 'configurationHash', 'seed', 'phaseCount', 'outcome', 'metrics', 'proposal', 'nativeReport', 'nativeMeasurement'] + repair_fields + (['policyMetrics'] if 'policyMetrics' in result else []))
        cc.integer(result['wallMs'], 0)
        cc.text(result['configurationHash'])
        cc.integer(result['phaseCount'], 1, 2)
        cc.check(result['seed'] == request['case']['solverSeed'], 'Native result seed differs')
        native = result['nativeMeasurement']
        fields(native, ['subSingleCount', 'subSingleIndex', 'seed', 'phaseCount', 'moveThreads', 'spentCapMs', 'solveMs', 'scoreCalculationCount', 'moveEvaluationCount', 'score'])
        for key in ('subSingleCount', 'subSingleIndex', 'seed', 'phaseCount', 'spentCapMs', 'solveMs', 'scoreCalculationCount', 'moveEvaluationCount'):
            cc.integer(native[key], 0)
        cc.check(native['subSingleCount'] == 1 and native['subSingleIndex'] == 0 and native['seed'] == result['seed'] and
                 native['phaseCount'] == result['phaseCount'] and native['moveThreads'] == 'NONE' and
                 native['spentCapMs'] == request['budget']['searchMs'], 'Native effective configuration differs')
        cc.text(native['score'])
        cc.text(result['nativeReport'])
        metrics(result['metrics'])
        if 'policyMetrics' in result:
            policy_metrics(result['policyMetrics'])
        proposal(result['proposal'])
        return outcome(result['outcome'])
    if layer == 'policy':
        fields(result, ['layer', 'wallMs', 'operation', 'baseline', 'reference', 'candidate', 'accepted', 'retained', 'retainedMetrics', 'diagnostics'] + repair_fields + (['policyMetrics'] if 'policyMetrics' in result else []))
        cc.integer(result['wallMs'], 0)
        cc.choice(result['accepted'], True, False)
        baseline, reference, candidate = (outcome(result[key]) for key in ('baseline', 'reference', 'candidate'))
        cc.check(not result['accepted'] or candidate, 'Accepted policy result must be independently eligible')
        proposal(result['retained'])
        metrics(result['retainedMetrics'])
        if 'policyMetrics' in result:
            policy_metrics(result['policyMetrics'])
        cc.check(isinstance(result['operation'], dict) and isinstance(result['diagnostics'], dict), 'Policy receipts required')
        return candidate if result['accepted'] else baseline
    if layer == 'workflow':
        fields(result, ['layer', 'datasetHash', 'operation', 'deployment', 'observations', 'before', 'after', 'verification',
                        'cleanupObservationMs', 'cleanupComplete', 'independentlyValid', 'warmup'])
        cc.check(result['operation'] == request['applicationLoad']['operation'] and result['deployment'] == request['applicationLoad']['deployment'], 'Caller operation/deployment differs')
        cc.choice(result['cleanupComplete'], True, False)
        cc.choice(result['independentlyValid'], True, False)
        cc.check(isinstance(result['observations'], list) and len(result['observations']) == request['applicationLoad']['requestsPerCase'], 'Every paced arrival needs an observation')
        successful = True
        for row in result['observations']:
            fields(row, ['id', 'outcome', 'intendedArrivalNanos', 'startedNanos', 'responseCompletedNanos', 'completedNanos', 'status', 'response', 'failure', 'cancellation', 'cleanup'])
            cc.text(row['id'])
            cc.choice(row['outcome'], 'RESPONSE', 'UNEXPECTED_HTTP_STATUS', 'TRANSPORT_TIMEOUT', 'TRANSPORT_FAILURE', 'GENERATOR_CAPACITY', 'CLEANUP_FAILURE')
            for key in ('intendedArrivalNanos', 'completedNanos'):
                cc.integer(row[key], 0, 2**63 - 1)
            for key in ('startedNanos', 'responseCompletedNanos'):
                if row[key] is not None:
                    cc.integer(row[key], 0, 2**63 - 1)
            cc.check(isinstance(row['cleanup'], list), 'Caller cleanup receipts required')
            successful &= row['outcome'] == 'RESPONSE' and type(row['status']) is int and 200 <= row['status'] < 300
        cc.unique([row['id'] for row in result['observations']])
        cc.check(not result['independentlyValid'] or successful and result['cleanupComplete'], 'Caller failures cannot become valid scheduling evidence')
        return result['independentlyValid']
    raise ValueError('Unknown benchmark result layer')


def policy_metrics(value):
    from campaign_analysis import decimal_number
    fields(value, ['overtimeMinutes', 'costCents', 'fairness'])
    cc.integer(value['overtimeMinutes'], 0, 2**53 - 1)
    cc.integer(value['costCents'], 0, 2**53 - 1)
    fairness = value['fairness']
    fields(fairness, ['variance', 'maximumUtilization', 'workloads'])
    decimal_number(fairness['variance'])
    decimal_number(fairness['maximumUtilization'])
    cc.check(isinstance(fairness['workloads'], list), 'Independent fairness workloads required')
    ids = []
    for row in fairness['workloads']:
        fields(row, ['technicianId', 'paidMinutes', 'regularCapacityMinutes', 'utilization'])
        cc.text(row['technicianId'])
        ids.append(row['technicianId'])
        cc.integer(row['paidMinutes'], 0, 2**53 - 1)
        cc.integer(row['regularCapacityMinutes'], 1, 2**53 - 1)
        decimal_number(row['utilization'])
    cc.unique(ids)


def outcome(value):
    fields(value, ['mode', 'scoreModelVersion', 'assignedVisitIds', 'unassignedVisitIds', 'complete',
                   'assignedWorkFeasible', 'scoringMatchesValidation', 'policyEligible'])
    cc.choice(value['mode'], 'ASSIGNED', 'COLD', 'PARTIAL', 'REPAIR')
    cc.text(value['scoreModelVersion'])
    cc.strings(value['assignedVisitIds'], False)
    cc.strings(value['unassignedVisitIds'], False)
    cc.check(not set(value['assignedVisitIds']) & set(value['unassignedVisitIds']), 'Duplicate demand coverage')
    for key in ('complete', 'assignedWorkFeasible', 'scoringMatchesValidation', 'policyEligible'):
        cc.choice(value[key], True, False)
    cc.check(value['complete'] == (not value['unassignedVisitIds']), 'Incomplete demand cannot claim completeness')
    cc.check(not value['policyEligible'] or value['complete'] and value['assignedWorkFeasible'] and value['scoringMatchesValidation'], 'Eligibility needs independent complete validation')
    return value['policyEligible']


def proposal(value):
    fields(value, ['version', 'factsHash', 'routes', 'unassigned', 'mode', 'score'])
    cc.choice(value['version'], 1)
    cc.text(value['factsHash'])
    cc.check(isinstance(value['routes'], dict), 'Route proposal required')
    for key, visits in value['routes'].items():
        cc.text(key)
        cc.strings(visits, False)
    cc.strings(value['unassigned'], False)
    cc.choice(value['mode'], 'ASSIGNED', 'COLD', 'PARTIAL', 'REPAIR')
    if value['score'] is not None:
        cc.text(value['score'])


def metrics(value):
    fields(value, ['hardPenalty', 'costCents', 'arrivals', 'paidMinutes', 'overtimeMinutes', 'meters', 'driveMinutes', 'waitingMinutes'])
    for key in ('hardPenalty', 'costCents', 'paidMinutes', 'overtimeMinutes', 'meters', 'driveMinutes', 'waitingMinutes'):
        cc.integer(value[key], 0, 2**53 - 1)
    cc.check(isinstance(value['arrivals'], dict), 'Independent arrivals required')
    for key, arrival in value['arrivals'].items():
        cc.text(key)
        cc.text(arrival)


def case_request(config, run, case, cpus, runtime_hash):
    dataset = next(d for d in config['datasets'] if d['id'] == case['datasetId'])
    dataset = {**dataset, 'input': {**dataset['input'], 'path': str(run / 'frozen/datasets' / f'{dataset["id"]}.json')},
               'target': None if dataset['target'] is None else {**dataset['target'], 'path': str(run / 'frozen/targets' / f'{dataset["id"]}.json')}}
    result = {'protocol': config['adapter']['protocol'], 'case': case, 'layer': config['layer'], 'dataset': dataset,
        'configuration': next(c for c in config['configurations'] if c['id'] == case['configurationId']),
        'budget': next(b for b in config['budgets'] if b['id'] == case['budgetId']), 'warmup': config['warmup'],
        'instrumentation': config['instrumentation'], 'applicationLoad': config['applicationLoad'],
        'jvmFlags': java_flags(config), 'affinityCpus': cpus, 'runtimeHash': runtime_hash}
    if 'policy' in config:
        result['policy'] = config['policy']
    return result


def run_case(config, run, directory, case, cpus, runtime_hash, stop):
    directory.mkdir(parents=True, exist_ok=False)
    request = case_request(config, run, case, cpus, runtime_hash)
    if config['instrumentation']['jfr'] != 'disabled':
        request['jvmFlags'].append(f'-XX:StartFlightRecording=settings={config["instrumentation"]["jfr"]},filename={directory / "recording.jfr"},dumponexit=true')
    write_new(directory / 'request.json', request)
    write_new(directory / 'dispatch.json', {'case': case, 'at': stamp(), 'requestHash': digest(request), 'affinityCpus': cpus})
    started = time.monotonic()
    state, failure = 'SUCCEEDED', None
    try:
        java = checked_artifact(config['runtime']['java'])
        args = [str(java), *request['jvmFlags']]
        args += ['-jar', str(run / 'frozen/adapter.jar'), str(directory / 'request.json'), str(directory / 'adapter-receipt.json')]
        if os.name != 'nt':
            cc.check(shutil.which('taskset') is not None, 'taskset required to enforce Linux affinity')
            args = ['taskset', '-c', ','.join(map(str, cpus)), *args]
        allowance = next(b['operationMs'] for b in config['budgets'] if b['id'] == case['budgetId'])
        load = config['applicationLoad']
        if load is not None:
            allowance = (load['requestsPerCase'] - 1) / load['requestsPerSecond'] * 1000 + load['timeoutMs']
        timeout = (allowance + config['warmup']['millisecondsPerFreshJvm'] * len(config['warmup']['paths']) +
                   sum(config['estimation'][k] for k in ('startupMsPerJvm', 'preparationMsPerCase', 'validationMsPerCase', 'reportingMsPerCase')) +
                   config['execution']['processGraceMs']) / 1000
        command(args, directory, environment(config), directory / 'process.log', timeout=timeout,
                affinity=sum(1 << cpu for cpu in cpus) if os.name == 'nt' else None, stop=stop)
        adapter_receipt(directory / 'adapter-receipt.json', request, config)
        if config['instrumentation']['jfr'] != 'disabled':
            recording = directory / 'recording.jfr'
            cc.check(recording.is_file() and recording.stat().st_size > 0, 'Requested JFR recording unavailable')
    except BaseException as error:
        state = 'INTERRUPTED' if isinstance(error, (KeyboardInterrupt, StopRequested)) else 'FAILED'
        failure = {'type': type(error).__name__, 'message': str(error)}
    write_new(directory / 'terminal.json', {'state': state, 'at': stamp(), 'elapsedMs': (time.monotonic() - started) * 1000,
        'failure': failure, 'files': case_hashes(directory)})
    return state


def cutoff_reached(config, now):
    cutoff = config['execution']['campaignCutoffUtc']
    return cutoff is not None and now >= datetime.fromisoformat(cutoff.replace('Z', '+00:00'))


def execute(run, *, now=None, worker=None, observer=None):
    run = run.resolve()
    config, blocks, saved = verify(run)
    prior = observations(run, blocks)
    attempt = run / 'attempts' / f'{stamp()}-{uuid.uuid4().hex[:8]}'
    attempt.mkdir(parents=True, exist_ok=False)
    now = now or (lambda: datetime.now(timezone.utc))
    worker = worker or run_case
    observer = observer or observe
    stop = threading.Event()
    state, failure = 'COMPLETE', None
    started = time.monotonic()
    try:
        current = observer(config)
        write_new(attempt / 'runtime.json', {'observed': current, 'competingActivity': activity(), 'observedAt': stamp()})
        cc.check(runtime_identity(config, current) == saved['runtimeHash'], 'Observed runtime changed; register a separate runtime cohort')
        write_new(attempt / 'plan.json', {'prior': prior, 'missingCaseIds': [c['id'] for b in blocks for c in b['cases'] if c['id'] not in prior],
                   'runtimeHash': saved['runtimeHash'], 'automaticRetries': False})
        slots = current['affinity']['slots']
        for block in blocks:
            pending = [c for c in block['cases'] if c['id'] not in prior]
            if not pending:
                continue
            if cutoff_reached(config, now()):
                state = 'CUTOFF'
                break
            write_new(attempt / 'blocks' / f'{block["id"]}.json', {'id': block['id'], 'startedAt': stamp(), 'caseIds': [c['id'] for c in pending]})
            # An admitted comparison block finishes all of its untouched cases.
            # Failure/cutoff stop the NEXT block and never shorten allowances.
            with ThreadPoolExecutor(max_workers=len(slots)) as pool:
                futures, queue, free = {}, iter(pending), list(range(len(slots)))
                failed = False
                try:
                    while True:
                        while free:
                            case = next(queue, None)
                            if case is None:
                                break
                            slot = free.pop(0)
                            future = pool.submit(worker, config, run, attempt / 'cases' / case['id'], case, slots[slot], saved['runtimeHash'], stop)
                            futures[future] = slot
                        if not futures:
                            break
                        done, _ = wait(futures, timeout=.25, return_when=FIRST_COMPLETED)
                        for future in done:
                            free.append(futures.pop(future))
                            failed = future.result() != 'SUCCEEDED' or failed
                except BaseException:
                    stop.set()
                    for future in futures:
                        future.cancel()
                    raise
            if failed:
                state = 'FAILED'
                break
    except BaseException as error:
        stop.set()
        state = 'INTERRUPTED' if isinstance(error, KeyboardInterrupt) else 'FAILED'
        failure = {'type': type(error).__name__, 'message': str(error)}
        if isinstance(error, KeyboardInterrupt):
            raise
    finally:
        write_new(attempt / 'terminal.json', {'state': state, 'failure': failure, 'at': stamp(), 'elapsedMs': (time.monotonic() - started) * 1000})
    if state == 'FAILED':
        raise RuntimeError(f'Campaign attempt failed; evidence retained at {attempt}. Explicit resume runs only never-dispatched cases.')
    return attempt


def analyze(run):
    config, blocks, runtime = verify(run)
    rows = observations(run, blocks)
    outcomes = {state: sum(r['state'] == state for r in rows.values()) for state in ('SUCCEEDED', 'FAILED', 'INTERRUPTED', 'ABANDONED')}
    complete = sum(all(c['id'] in rows and rows[c['id']]['state'] == 'SUCCEEDED' for c in b['cases']) for b in blocks)
    requested = sum(len(b['cases']) for b in blocks)
    validity, contracts, loaded = [], 0, {}
    for row in rows.values():
        if row['state'] != 'SUCCEEDED':
            continue
        directory = Path(row['path'])
        receipt_path = directory / 'adapter-receipt.json'
        if not receipt_path.is_file():
            continue  # Historical lifecycle-only fixtures do not contain scheduling evidence.
        receipt = read_json(receipt_path)
        if receipt.get('evidenceKind') != 'benchmark':
            continue
        request = read_json(directory / 'request.json')
        validated = adapter_receipt(receipt_path, request, config)
        valid = benchmark_result(validated['result'], request)
        loaded[row['case']['id']] = {'valid': valid, 'result': validated['result']}
        if valid is None:
            contracts += 1
        else:
            validity.append(valid)
    report = {'version': 2, 'requestedCases': requested, 'dispatchedCases': len(rows), 'missingCases': requested - len(rows),
        'outcomes': outcomes, 'requestedBlocks': len(blocks), 'completeSuccessfulBlocks': complete,
        'incompleteBlocks': len(blocks) - complete, 'runtimeHash': runtime['runtimeHash'],
        'independentlyValidCases': sum(validity) if validity else None,
        'validityUnavailableReason': None if validity else 'No successful scheduling benchmark receipts; input contracts and lifecycle fixtures are separate.',
        'expectedInputRejections': contracts,
        'configuredAnalysis': config['analysis'], 'inference': None,
        'inferenceUnavailableReason': 'Inventory-only analysis was registered; acceptance and lifecycle inventories cannot select a winner.',
        'observations': list(rows.values())}
    if config['analysis']['method'] == 'paired-dataset-bootstrap':
        from campaign_analysis import paired
        report['inference'] = paired(config, blocks, rows, loaded, runtime['runtimeHash'])
        report['inferenceUnavailableReason'] = None
    destination = run / 'analysis' / f'{stamp()}-{uuid.uuid4().hex[:8]}.json'
    write_new(destination, report)
    return report
