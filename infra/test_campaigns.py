"""Campaign contract/lifecycle regressions. Fixtures are not performance evidence."""
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import campaign_config as cc
import campaign_runtime as cr
from experiment_config import digest, read_json
from experiment_runtime import sha, write_new, measurement_lock


def configuration():
    pin = {'path': 'input.json', 'sha256': '1' * 64}
    treatment = {'id': 'tabu', 'acceptor': 'TABU', 'acceptorSize': 7, 'acceptedCountLimit': 1,
        'selectedCountLimit': 100, 'moves': [{'family': 'listChange', 'weight': 1}, {'family': 'listSwap', 'weight': 1}],
        'environmentMode': 'NO_ASSERT', 'moveThreads': 'NONE', 'nativeParallelBenchmarkCount': 1,
        'termination': {'kind': 'fixed', 'scope': 'local-search', 'spentCap': True, 'stepCap': None,
                        'windowMs': None, 'minimumImprovementRatio': None, 'unimprovedMs': None}}
    second = {**copy.deepcopy(treatment), 'id': 'la', 'acceptor': 'LATE_ACCEPTANCE', 'acceptorSize': 400}
    return {'version': 2, 'name': 'contract-campaign', 'purpose': 'Explicit contract fixture, no solver performance claim',
        'edition': 'COMMUNITY', 'layer': 'solver', 'controlId': 'tabu',
        'datasets': [{'id': 'dataset-a', 'input': pin, 'family': 'fixture', 'role': 'tuning', 'cohort': 'assigned',
                      'origin': 'contract-fixture', 'datasetSeed': None, 'scoreVersion': 'fixture', 'modelVersion': 'fixture',
                      'routingIdentity': 'fixture', 'target': None}],
        'configurations': [treatment, second], 'solverSeeds': [17, 23], 'forks': 1,
        'budgets': [{'id': 'reference-test', 'purpose': 'contract-test', 'phase': 'reference', 'operationMs': 100,
                     'searchMs': 80, 'referenceMs': 80, 'fairnessMs': 0, 'repairMs': 0, 'validationReserveMs': 20,
                     'transferUnusedToFairness': False}],
        'warmup': {'millisecondsPerFreshJvm': 10, 'paths': ['reference'], 'disposableInputs': True,
                   'calibrationMs': [200, 30000, 60000], 'calibrationRepetitions': 3, 'stabilityTolerancePercent': 5},
        'runtime': {'java': copy.deepcopy(pin), 'jdkMajor': 25, 'heapMinMiB': 16, 'heapMaxMiB': 32, 'gc': 'SerialGC',
                    'activeProcessorCount': 1, 'flags': ['-Dfile.encoding=UTF-8'], 'environment': {'TZ': 'UTC'},
                    'processLifetime': 'one-case-per-jvm'},
        'resources': {'parallelCases': 1, 'totalCpuAllocation': 1, 'cpuPerCase': 1, 'affinityPolicy': 'disjoint-physical-cores',
                      'concurrencyCalibration': [1, 2], 'memoryLimitMiB': 64},
        'instrumentation': {'cohort': 'quality-basic', 'statistics': [], 'jfr': 'disabled', 'sampleIntervalMs': 100,
                            'internalDiagnostics': False, 'constraintProfiling': False},
        'analysis': {'method': 'inventory-only', 'pairKeys': ['datasetHash', 'targetHash', 'budgetHash', 'solverSeed', 'fork',
                    'scoreVersion', 'modelVersion', 'routingIdentity', 'runtimeHash'], 'draws': 10000, 'seed': 20261006,
                    'confidenceLevel': .95, 'selection': 'tuning-only', 'latencyNoninferiorityPercent': 5,
                    'failureTolerance': 0, 'costDifferenceUpperBoundCents': 0},
        'applicationLoad': None,
        'execution': {'orderSeed': 99, 'failurePolicy': 'stop-new-blocks', 'automaticRetries': False,
                      'resumePolicy': 'hash-verified-missing-cases', 'campaignCutoffUtc': None, 'processGraceMs': 5000},
        'estimation': {'startupMsPerJvm': 1, 'preparationMsPerCase': 2, 'validationMsPerCase': 3,
                       'reportingMsPerCase': 4, 'referenceSetupMsPerDataset': 20, 'serialAnalysisMs': 30},
        'outputLocation': 'runs', 'adapter': {'protocol': 'waterflex-campaign-jvm-v1', 'jar': copy.deepcopy(pin)}}


class CampaignConfigurationTests(unittest.TestCase):
    def test_shipped_template_is_explicit_and_schema_valid_but_not_executable(self):
        config = read_json(Path(__file__).resolve().parents[1] / 'experiments/configs/campaign-v2-template.json')
        cc.validate(config)
        self.assertEqual(config['adapter']['jar']['sha256'], '0' * 64)
        self.assertEqual(config['runtime']['java']['sha256'], '0' * 64)
        self.assertEqual(cc.estimate(config)['freshJvms'], 4)

    def test_reject_each_unknown_and_missing_top_level_field(self):
        for key in configuration():
            config = configuration()
            del config[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                cc.validate(config)
        config = configuration()
        config['unknown'] = 1
        with self.assertRaises(ValueError):
            cc.validate(config)

    def test_nested_unknown_null_boolean_nonfinite_and_unsupported_settings(self):
        edits = [lambda c: c['warmup'].update(extra=True), lambda c: c.update(version=True),
            lambda c: c.update(version=3), lambda c: c.update(datasets=None), lambda c: c.update(forks=True),
            lambda c: c.update(solverSeeds=[None]), lambda c: c.update(solverSeeds=[17, 17]),
            lambda c: c['runtime'].update(heapMinMiB=64), lambda c: c['runtime'].update(flags=['-Xmx4g']),
            lambda c: c['runtime'].update(environment={'PASSWORD': 'secret'}),
            lambda c: c['resources'].update(parallelCases=2), lambda c: c['resources'].update(memoryLimitMiB=16),
            lambda c: c['configurations'][0].update(moveThreads='AUTO'),
            lambda c: c['configurations'][0].update(acceptor='SIMULATED_ANNEALING'),
            lambda c: c['configurations'][0]['termination'].update(unimprovedMs=2),
            lambda c: c['configurations'][0]['moves'][0].update(weight=float('inf')),
            lambda c: c['budgets'][0].update(searchMs=81), lambda c: c['budgets'][0].update(operationMs=99),
            lambda c: c['execution'].update(campaignCutoffUtc='2026-10-06T06:00:00'),
            lambda c: c['execution'].update(campaignCutoffUtc='2026-10-06T06:00:00-05:00'),
            lambda c: c['execution'].update(automaticRetries=True), lambda c: c['analysis'].update(pairKeys=['datasetHash']),
            lambda c: c['datasets'][0].update(datasetSeed=17), lambda c: c.update(applicationLoad={}),
            lambda c: c['instrumentation'].update(jfr='profile'), lambda c: c['adapter'].update(protocol='shell')]
        for edit in edits:
            config = configuration()
            edit(config)
            with self.subTest(config=config), self.assertRaises(ValueError):
                cc.validate(config)

    def test_semantically_duplicate_treatments_and_datasets_rejected(self):
        config = configuration()
        config['configurations'][1] = {**copy.deepcopy(config['configurations'][0]), 'id': 'alias'}
        with self.assertRaises(ValueError):
            cc.validate(config)
        config = configuration()
        config['datasets'].append({**copy.deepcopy(config['datasets'][0]), 'id': 'alias'})
        with self.assertRaises(ValueError):
            cc.validate(config)

    def test_duplicate_json_keys_and_nonfinite_parsed_before_dispatch(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'config.json'
            for content in ('{"version":2,"version":2}', '{"nested":{"a":1,"a":2}}', '{"value":NaN}'):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    read_json(path)

    def test_deterministic_expansion_rotation_and_uneven_estimate(self):
        config = configuration()
        config['forks'] = 2
        config['resources'].update(parallelCases=2, totalCpuAllocation=2)
        config['configurations'].append({**copy.deepcopy(config['configurations'][0]), 'id': 'tabu-long', 'acceptorSize': 9})
        config['budgets'].append({**config['budgets'][0], 'id': 'long', 'operationMs': 200, 'searchMs': 180, 'referenceMs': 180})
        blocks = cc.expand(config)
        self.assertEqual(blocks, cc.expand(config))
        self.assertEqual(len(blocks), 8)
        self.assertEqual(len({c['id'] for b in blocks for c in b['cases']}), 24)
        self.assertGreater(len({b['cases'][0]['configurationId'] for b in blocks}), 1)
        estimate = cc.estimate(config)
        self.assertEqual(estimate['freshJvms'], 24)
        self.assertEqual(estimate['operationAllowanceMs'], 3600)
        self.assertEqual(estimate['serialEstimateMs'], 4130)
        self.assertEqual(estimate['optimisticParallelEstimateMs'], 2770)
        self.assertIsNone(estimate['observedRuntimeMs'])

    def test_policy_pipeline_counted_once_and_no_frozen_reference(self):
        config = configuration()
        config['layer'] = 'policy'
        config['warmup']['paths'] = ['daily-policy']
        config['budgets'][0].update(purpose='production', phase='pipeline', operationMs=20000,
            searchMs=15000, referenceMs=10000, fairnessMs=5000, validationReserveMs=1000, transferUnusedToFairness=True)
        self.assertEqual(cc.estimate(config)['operationAllowanceMs'], 80000)
        config['datasets'][0]['target'] = copy.deepcopy(config['datasets'][0]['input'])
        with self.assertRaises(ValueError):
            cc.validate(config)

    def test_fairness_requires_target_and_separate_invalid_and_repair_cohorts(self):
        config = configuration()
        config['budgets'][0].update(phase='fairness', referenceMs=0, fairnessMs=80)
        with self.assertRaises(ValueError):
            cc.validate(config)
        config['datasets'][0]['target'] = copy.deepcopy(config['datasets'][0]['input'])
        config['warmup']['paths'] = ['fairness']
        cc.validate(config)
        config = configuration()
        second = {**copy.deepcopy(config['datasets'][0]), 'id': 'dataset-b', 'input': {'path': 'b.json', 'sha256': '2' * 64}, 'cohort': 'invalid-input'}
        config['datasets'].append(second)
        with self.assertRaises(ValueError):
            cc.validate(config)

    def test_workflow_paced_arrival_estimate_and_explicit_load(self):
        config = configuration()
        config['layer'] = 'workflow'
        config['warmup']['paths'] = ['daily-preview']
        config['budgets'][0].update(phase='pipeline', referenceMs=40, fairnessMs=40)
        config['applicationLoad'] = {'operation': 'daily-preview', 'mode': 'paced-arrival', 'requestsPerCase': 3,
            'requestsPerSecond': 2, 'concurrency': 2, 'schedulerCache': 'warm', 'providerCache': 'cold',
            'timeoutMs': 20000, 'observeCancellation': True, 'deployment': 'embedded', 'endpointIdentity': 'fixture'}
        self.assertEqual(cc.estimate(config)['operationAllowanceMs'], 84000)
        config['applicationLoad']['requestsPerSecond'] = 0
        with self.assertRaises(ValueError):
            cc.validate(config)


OBSERVED = {'affinity': {'slots': [[1]]}, 'hardware': {'memoryBytes': 64 * 1048576}, 'fixture': True}


def mock_worker(state='SUCCEEDED', interrupt=False):
    def worker(config, run, directory, case, cpus, runtime_hash, stop):
        directory.mkdir(parents=True)
        write_new(directory / 'dispatch.json', {'case': case, 'at': 'fixture', 'requestHash': 'fixture', 'affinityCpus': cpus})
        if interrupt:
            raise KeyboardInterrupt()
        write_new(directory / 'raw.json', {'evidenceKind': 'contract-fixture'})
        write_new(directory / 'terminal.json', {'state': state, 'at': 'fixture', 'elapsedMs': 1,
            'failure': None if state == 'SUCCEEDED' else {'type': 'FixtureFailure'},
            'files': {p.name: sha(p) for p in directory.iterdir()}})
        return state
    return worker


class CampaignLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.config = configuration()
        for filename, content in [('input.json', b'{}'), ('adapter.jar', b'fixture'), ('java', b'fixture')]:
            (self.base / filename).write_bytes(content)
        self.config['datasets'][0]['input'] = {'path': 'input.json', 'sha256': sha(self.base / 'input.json')}
        self.config['adapter']['jar'] = {'path': 'adapter.jar', 'sha256': sha(self.base / 'adapter.jar')}
        self.config['runtime']['java'] = {'path': 'java', 'sha256': sha(self.base / 'java')}

    def archive(self, changes=None):
        if changes:
            changes(self.config)
        path = self.base / 'campaign.json'
        path.write_text(json.dumps(self.config), encoding='utf-8')
        with patch.object(cr, 'observe', return_value=OBSERVED), patch.object(cr, 'activity', return_value={'fixture': True}):
            return cr.new_run(path)

    def execute(self, run, **kwargs):
        return cr.execute(run, observer=lambda config: OBSERVED, worker=kwargs.pop('worker', mock_worker()), **kwargs)

    def test_provenance_complete_before_first_dispatch_and_original_bytes(self):
        run = self.archive()
        config, blocks, runtime = cr.verify(run)
        self.assertEqual((run / 'original-config.json').read_bytes(), (self.base / 'campaign.json').read_bytes())
        self.assertEqual(runtime['runtimeHash'], cr.runtime_identity(config, OBSERVED))
        def worker(*args):
            cr.verify(run)
            self.assertTrue((run / 'runtime.json').is_file())
            return mock_worker()(*args)
        self.execute(run, worker=worker)
        report = cr.analyze(run)
        self.assertEqual(report['outcomes']['SUCCEEDED'], 4)
        self.assertEqual(report['completeSuccessfulBlocks'], 2)
        self.assertIsNone(report['inference'])

    def test_changed_config_blocks_runtime_frozen_and_toolkit_rejected(self):
        for filename in ('config.json', 'blocks.json', 'runtime.json', 'frozen/adapter.jar', 'frozen/toolkit/campaign_config.py'):
            with self.subTest(filename=filename):
                run = self.archive()
                with (run / filename).open('ab') as stream:
                    stream.write(b' ')
                with self.assertRaises(ValueError):
                    cr.verify(run)
        run = self.archive()
        with patch.object(cr, 'toolkit_hashes', return_value={}):
            with self.assertRaises(ValueError):
                cr.verify(run)

    def test_invalid_pin_retains_preparation_failure_and_no_dispatch(self):
        self.config['adapter']['jar']['sha256'] = '0' * 64
        with self.assertRaises(ValueError):
            self.archive()
        run = next((self.base / 'runs').iterdir())
        self.assertEqual(read_json(run / 'preparation-failure.json')['state'], 'FAILED')
        self.assertFalse((run / 'attempts').exists())

    def test_failure_finishes_admitted_block_stops_new_blocks_and_resume_never_retries(self):
        run = self.archive()
        with self.assertRaises(RuntimeError):
            self.execute(run, worker=mock_worker('FAILED'))
        report = cr.analyze(run)
        self.assertEqual(report['outcomes']['FAILED'], 2)
        self.assertEqual(report['missingCases'], 2)
        self.execute(run)
        self.execute(run)
        report = cr.analyze(run)
        self.assertEqual(report['dispatchedCases'], 4)
        self.assertEqual(report['outcomes']['FAILED'], 2)
        self.assertEqual(report['outcomes']['SUCCEEDED'], 2)
        self.assertEqual(report['incompleteBlocks'], 1)

    def test_cutoff_before_block_and_crossing_inside_block_preserves_full_allowances(self):
        cutoff = '2026-10-06T11:00:00Z'  # 6 a.m. America/Chicago on this date.
        run = self.archive(lambda c: c['execution'].update(campaignCutoffUtc=cutoff))
        after = datetime(2026, 10, 6, 11, tzinfo=timezone.utc)
        attempt = self.execute(run, now=lambda: after)
        self.assertEqual(read_json(attempt / 'terminal.json')['state'], 'CUTOFF')
        self.assertEqual(cr.analyze(run)['dispatchedCases'], 0)
        checks = iter([datetime(2026, 10, 6, 10, 59, tzinfo=timezone.utc), after])
        allowances = []
        def worker(config, *args):
            allowances.append(config['budgets'][0]['operationMs'])
            return mock_worker()(config, *args)
        self.execute(run, now=lambda: next(checks), worker=worker)
        self.assertEqual(allowances, [100, 100])
        self.assertEqual(cr.analyze(run)['missingCases'], 2)

    def test_interrupted_and_orphan_dispatch_consumed_missing_block_resumes(self):
        run = self.archive()
        with self.assertRaises(KeyboardInterrupt):
            self.execute(run, worker=mock_worker(interrupt=True))
        report = cr.analyze(run)
        self.assertEqual(report['outcomes']['ABANDONED'], 1)
        self.assertEqual(report['missingCases'], 3)
        self.execute(run)
        self.assertEqual(cr.analyze(run)['outcomes']['ABANDONED'], 1)
        self.assertEqual(cr.analyze(run)['dispatchedCases'], 4)

    def test_runtime_drift_has_failed_attempt_receipt_before_dispatch(self):
        run = self.archive()
        with self.assertRaises(RuntimeError):
            cr.execute(run, observer=lambda c: {**OBSERVED, 'changed': True}, worker=mock_worker())
        attempt = next((run / 'attempts').iterdir())
        self.assertEqual(read_json(attempt / 'terminal.json')['state'], 'FAILED')
        self.assertEqual(cr.analyze(run)['dispatchedCases'], 0)

    def test_completed_case_tampering_and_duplicate_observation_rejected(self):
        run = self.archive()
        self.execute(run)
        case = next((run / 'attempts').glob('*/cases/*'))
        (case / 'raw.json').write_text('{}')
        with self.assertRaises(ValueError):
            cr.analyze(run)
        run = self.archive()
        self.execute(run)
        case = next((run / 'attempts').glob('*/cases/*'))
        shutil.copytree(case, run / 'attempts/duplicate/cases' / case.name)
        with self.assertRaises(ValueError):
            cr.analyze(run)

    def test_machine_measurement_lock_shared_with_legacy_runner(self):
        with measurement_lock():
            with self.assertRaises(RuntimeError):
                with measurement_lock():
                    self.fail('second runner acquired measurement lock')

    def test_parallel_cases_have_disjoint_slots_and_next_block_waits(self):
        run = self.archive(lambda c: c['resources'].update(parallelCases=2, totalCpuAllocation=2))
        observed = {**OBSERVED, 'affinity': {'slots': [[1], [2]]}}
        # Runtime identity is immutable, so construct this campaign with the
        # requested two-slot receipt rather than silently changing it on resume.
        with patch.object(cr, 'observe', return_value=observed):
            run = cr.new_run(self.base / 'campaign.json')
        live, completed, peak = {}, [], []
        lock = threading.Lock()
        def worker(config, run, directory, case, cpus, runtime_hash, stop):
            with lock:
                self.assertNotIn(tuple(cpus), live)
                self.assertTrue(not live or set(live.values()) == {case['blockId']})
                live[tuple(cpus)] = case['blockId']
                peak.append(len(live))
            time.sleep(.03)
            result = mock_worker()(config, run, directory, case, cpus, runtime_hash, stop)
            with lock:
                del live[tuple(cpus)]
                completed.append(case['id'])
            return result
        cr.execute(run, observer=lambda c: observed, worker=worker)
        self.assertEqual(max(peak), 2)
        self.assertEqual(len(set(completed)), 4)
        self.assertEqual(cr.analyze(run)['completeSuccessfulBlocks'], 2)

    def test_malformed_persisted_terminal_and_nested_raw_artifact_drift_fail(self):
        run = self.archive()
        def nested(*args):
            config, run, directory, case, cpus, runtime_hash, stop = args
            directory.mkdir(parents=True)
            write_new(directory / 'dispatch.json', {'case': case, 'at': 'fixture', 'requestHash': 'fixture', 'affinityCpus': cpus})
            write_new(directory / 'native/results/raw.json', {'fixtureOnly': True})
            write_new(directory / 'terminal.json', {'state': 'SUCCEEDED', 'at': 'fixture', 'elapsedMs': 1,
                'failure': None, 'files': cr.case_hashes(directory)})
            return 'SUCCEEDED'
        self.execute(run, worker=nested)
        raw = next((run / 'attempts').glob('*/cases/*/native/results/raw.json'))
        raw.write_text('{"tampered":true}')
        with self.assertRaises(ValueError):
            cr.analyze(run)
        run = self.archive()
        self.execute(run)
        terminal = next((run / 'attempts').glob('*/cases/*/terminal.json'))
        value = read_json(terminal)
        value['elapsedMs'] = None
        terminal.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            cr.analyze(run)

    def test_warmup_instrumentation_and_cache_cohorts_have_distinct_runtime_identity(self):
        config = configuration()
        control = cr.runtime_identity(config, OBSERVED)
        for mutate in (lambda c: c['warmup'].update(millisecondsPerFreshJvm=30),
                       lambda c: c['instrumentation'].update(cohort='diagnostic', jfr='profile'),
                       lambda c: c.update(applicationLoad={'schedulerCache': 'cold'})):
            changed = configuration()
            mutate(changed)
            self.assertNotEqual(control, cr.runtime_identity(changed, OBSERVED))

    def test_cli_v2_validate_dry_run_no_overrides_and_v1_still_works(self):
        path = self.base / 'campaign.json'
        path.write_text(json.dumps(self.config))
        entry = Path(__file__).with_name('experiments.py')
        for command in ('validate', 'dry-run'):
            result = subprocess.run([sys.executable, str(entry), command, str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout).get('version', 2), 2)
        result = subprocess.run([sys.executable, str(entry), 'run', str(path), '--seed', '99'], capture_output=True)
        self.assertEqual(result.returncode, 2)
        legacy = Path(__file__).resolve().parents[1] / 'experiments/configs/daily-smoke.json'
        result = subprocess.run([sys.executable, str(entry), 'run', str(legacy), '--dry-run'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('daily_search_seconds', json.loads(result.stdout))


class AdapterContractTests(unittest.TestCase):
    def test_real_results_reject_null_metrics_native_caps_and_false_eligibility(self):
        config = configuration()
        request = {'layer': 'solver', 'dataset': {'cohort': 'assigned'}, 'case': {'solverSeed': 17}, 'budget': {'searchMs': 80}}
        valid = {'mode': 'ASSIGNED', 'scoreModelVersion': 'model', 'assignedVisitIds': ['visit'], 'unassignedVisitIds': [],
                 'complete': True, 'assignedWorkFeasible': True, 'scoringMatchesValidation': True, 'policyEligible': True}
        result = {'layer': 'solver', 'wallMs': 12, 'configurationHash': 'hash', 'seed': 17, 'phaseCount': 1,
                  'outcome': valid, 'metrics': {'hardPenalty': 0, 'costCents': 20, 'arrivals': {'visit': '2026-10-26T10:00:00Z'},
                  'paidMinutes': 2, 'overtimeMinutes': 0, 'meters': 1, 'driveMinutes': 1, 'waitingMinutes': 0},
                  'proposal': {'version': 1, 'factsHash': 'facts', 'routes': {'tech': ['visit']}, 'unassigned': [], 'mode': 'ASSIGNED', 'score': None},
                  'nativeReport': 'native/report', 'nativeMeasurement': {'subSingleCount': 1, 'subSingleIndex': 0, 'seed': 17,
                  'phaseCount': 1, 'moveThreads': 'NONE', 'spentCapMs': 80, 'solveMs': 10, 'scoreCalculationCount': 3, 'moveEvaluationCount': 2, 'score': 'score'}}
        self.assertTrue(cr.benchmark_result(result, request))
        edits = [lambda r: r['metrics'].update(costCents=None), lambda r: r['nativeMeasurement'].update(spentCapMs=81),
                 lambda r: r['nativeMeasurement'].update(moveEvaluationCount=-1), lambda r: r['nativeMeasurement'].update(seed=18),
                 lambda r: r['outcome'].update(assignedWorkFeasible=False), lambda r: r['outcome'].update(unassignedVisitIds=['visit'])]
        for edit in edits:
            malformed = copy.deepcopy(result)
            edit(malformed)
            with self.subTest(edit=edit), self.assertRaises(ValueError):
                cr.benchmark_result(malformed, request)
        request['dataset']['cohort'] = 'repair'
        with self.assertRaises(ValueError):
            cr.benchmark_result(result, request)
        result['repair'] = {'timeToFeasibilityMs': None, 'unavailableReason': 'Final proposals only',
            'unresolvedCandidate': [], 'unresolvedRetained': [], 'terminalCandidateEligible': True, 'terminalRetainedEligible': True}
        self.assertTrue(cr.benchmark_result(result, request))
        result['repair']['timeToFeasibilityMs'] = 0
        with self.assertRaises(ValueError):
            cr.benchmark_result(result, request)
        request['dataset']['cohort'] = 'invalid-input'
        rejection = {'layer': 'input-contract', 'expectedFailure': True, 'failureType': 'IllegalArgumentException', 'failureMessage': 'missing rates'}
        self.assertIsNone(cr.benchmark_result(rejection, request))
        rejection['expectedFailure'] = False
        with self.assertRaises(ValueError):
            cr.benchmark_result(rejection, request)

    def test_real_policy_is_explicit_and_booking_has_its_own_allowance(self):
        config = configuration()
        config['datasets'][0].update(origin='historical', datasetSeed=None)
        with self.assertRaises(ValueError):
            cc.validate(config)
        config['policy'] = {'regularWindowThreshold': 2, 'utilizationThreshold': '0.9', 'fairnessAllowance': '0.02', 'bookingDeadlineMs': 5000}
        cc.validate(config)
        config['policy']['fairnessAllowance'] = None
        with self.assertRaises(ValueError):
            cc.validate(config)
        config['policy']['fairnessAllowance'] = '0.02'
        config['layer'] = 'workflow'
        config['applicationLoad'] = {'operation': 'booking-offer', 'mode': 'paced-arrival', 'requestsPerCase': 2, 'requestsPerSecond': 1,
            'concurrency': 1, 'schedulerCache': 'cold', 'providerCache': 'cold', 'timeoutMs': 10000, 'observeCancellation': True,
            'deployment': 'embedded', 'endpointIdentity': 'fixture'}
        config['warmup']['paths'] = ['booking-offer']
        config['budgets'] = [{'id': 'booking', 'purpose': 'production', 'phase': 'booking', 'operationMs': 5000,
            'searchMs': 4000, 'referenceMs': 0, 'fairnessMs': 0, 'repairMs': 0, 'validationReserveMs': 1000, 'transferUnusedToFairness': False}]
        cc.validate(config)
        config['budgets'][0]['operationMs'] = 6000
        with self.assertRaises(ValueError):
            cc.validate(config)
        config['policy']['bookingDeadlineMs'] = 3000
        config['budgets'][0].update(operationMs=3000,searchMs=2000)
        with self.assertRaises(ValueError):
            cc.validate(config)

    def fixture(self, directory):
        config = configuration()
        config = cc.resolve(config, directory)
        case = cc.expand(config)[0]['cases'][0]
        request = cr.case_request(config, directory, case, [1], 'runtime')
        receipt = {'protocol': config['adapter']['protocol'], 'caseId': case['id'], 'requestHash': digest(request),
            'state': 'SUCCEEDED', 'evidenceKind': 'contract-fixture',
            'warmup': {'paths': ['reference'], 'disposableInputs': True, 'elapsedMsByPath': {'reference': 10}},
            'runtime': {'inputArguments': request['jvmFlags'], 'availableProcessors': 1, 'affinityCpus': [1], 'pid': 123},
            'instrumentation': {'enabledStatistics': [], 'unavailableStatistics': [], 'internalDiagnosticsEnabled': False, 'internalDiagnosticsFailureReason': None},
            'result': {'fixtureOnly': True}}
        return config, request, receipt

    def test_missing_null_wrong_identity_runtime_and_warmup_rejected(self):
        edits = [lambda r: r.update(requestHash='wrong'), lambda r: r.update(caseId='wrong'),
            lambda r: r.update(evidenceKind='benchmark'), lambda r: r.update(state='FAILED'),
            lambda r: r.update(result=None), lambda r: r.update(unknown=True),
            lambda r: r['warmup'].update(elapsedMsByPath={'reference': 0}),
            lambda r: r['warmup'].update(elapsedMsByPath={'reference': None}),
            lambda r: r['warmup'].update(elapsedMsByPath={}), lambda r: r['runtime'].update(affinityCpus=[2]),
            lambda r: r['instrumentation'].update(internalDiagnosticsFailureReason='required probe failed'),
            lambda r: r['instrumentation'].update(enabledStatistics=['BEST_SCORE']),
            lambda r: r['runtime'].update(inputArguments=[]), lambda r: r['runtime'].update(availableProcessors=2)]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'receipt.json'
            for edit in edits:
                config, request, receipt = self.fixture(Path(folder))
                edit(receipt)
                path.write_text(json.dumps(receipt))
                with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                    cr.adapter_receipt(path, request, config)

    def test_unavailable_statistic_is_explicit_null_with_reason_and_required_diagnostics_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'receipt.json'
            config, request, receipt = self.fixture(Path(folder))
            config['instrumentation']['statistics'] = ['BEST_SCORE']
            receipt['requestHash'] = digest(request)
            receipt['instrumentation']['unavailableStatistics'] = [{'name': 'BEST_SCORE', 'value': None, 'reason': 'Not available in this fixture'}]
            path.write_text(json.dumps(receipt))
            cr.adapter_receipt(path, request, config)
            receipt['instrumentation']['unavailableStatistics'][0]['value'] = 0
            path.write_text(json.dumps(receipt))
            with self.assertRaises(ValueError):
                cr.adapter_receipt(path, request, config)
            receipt['instrumentation']['unavailableStatistics'][0]['value'] = None
            config['instrumentation']['internalDiagnostics'] = True
            receipt['requestHash'] = digest(request)
            path.write_text(json.dumps(receipt))
            with self.assertRaises(ValueError):
                cr.adapter_receipt(path, request, config)

    def test_fresh_jvm_warmup_on_explicit_resume_with_real_java25_processes(self):
        java = Path(os.environ.get('JAVA_HOME', '')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if not java.is_file():
            java = Path(shutil.which('java') or '')
        self.assertTrue(java.is_file(), 'Java 25 is required for the campaign lifecycle gate')
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            # This test-only JVM exercises launch ownership, actual flags/CPU,
            # actual affinity, and fresh-process warmup. It never runs a solver.
            source = Path(__file__).with_name('test-fixtures') / 'CampaignFixture.java'
            javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
            jar = java.with_name('jar.exe' if os.name == 'nt' else 'jar')
            subprocess.run([str(javac), '-d', str(base), str(source)], check=True, capture_output=True)
            subprocess.run([str(jar), '--create', '--file', str(base / 'adapter.jar'), '--main-class', 'CampaignFixture',
                            '-C', str(base), 'CampaignFixture.class'], check=True, capture_output=True)
            (base / 'input.json').write_text('{"fixtureOnly":true}')
            config = configuration()
            config['runtime']['java'] = {'path': str(java.resolve()), 'sha256': sha(java)}
            config['adapter']['jar'] = {'path': 'adapter.jar', 'sha256': sha(base / 'adapter.jar')}
            config['datasets'][0]['input'] = {'path': 'input.json', 'sha256': sha(base / 'input.json')}
            config_path = base / 'campaign.json'
            config_path.write_text(json.dumps(config))
            entry = Path(__file__).with_name('experiments.py')
            first = subprocess.run([sys.executable, str(entry), 'run', str(config_path)], capture_output=True, text=True)
            self.assertEqual(first.returncode, 1, first.stderr)
            run = Path(first.stdout.split('Run: ', 1)[1].splitlines()[0])
            resumed = subprocess.run([sys.executable, str(entry), 'resume', str(run)], capture_output=True, text=True)
            self.assertEqual(resumed.returncode, 1, resumed.stderr)
            finished = subprocess.run([sys.executable, str(entry), 'resume', str(run)], capture_output=True, text=True)
            self.assertEqual(finished.returncode, 0, finished.stderr)
            analysis = subprocess.run([sys.executable, str(entry), 'analyze', str(run)], capture_output=True, text=True)
            self.assertEqual(analysis.returncode, 0, analysis.stderr)
            rows = json.loads(analysis.stdout)
            self.assertEqual(rows['outcomes'], {'SUCCEEDED': 2, 'FAILED': 2, 'INTERRUPTED': 0, 'ABANDONED': 0})
            receipts = [read_json(p) for p in (run / 'attempts').glob('*/cases/*/adapter-receipt.json')]
            self.assertEqual(len({r['runtime']['pid'] for r in receipts}), 4)
            self.assertTrue(all(r['warmup']['elapsedMsByPath']['reference'] >= 10 for r in receipts))
            self.assertEqual(rows['dispatchedCases'], 4)
            config['configurations'] = config['configurations'][:1]
            config['solverSeeds'] = [17]
            config['instrumentation'].update(cohort='diagnostic', jfr='profile')
            config_path.write_text(json.dumps(config))
            diagnostic = subprocess.run([sys.executable, str(entry), 'run', str(config_path)], capture_output=True, text=True)
            self.assertEqual(diagnostic.returncode, 0, diagnostic.stderr)
            diagnostic_run = Path(diagnostic.stdout.split('Run: ', 1)[1].splitlines()[0])
            recording = next((diagnostic_run / 'attempts').glob('*/cases/*/recording.jfr'))
            self.assertGreater(recording.stat().st_size, 0)
            self.assertEqual(cr.analyze(diagnostic_run)['outcomes']['SUCCEEDED'], 1)



if __name__ == '__main__':
    unittest.main()
