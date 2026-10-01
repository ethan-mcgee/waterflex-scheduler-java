"""Regression examples use temporary evidence; they never launch the full study."""
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_experiment as launcher

from experiment_config import digest, expand, read_json, validate
from experiment_analysis import compare, equal_seed_summary, load_raw, normalize, quantile, summary_groups
import experiment_runtime as rt


def config():
    return {'version': 1, 'name': 'test', 'daily': {'solvers': ['TABU', 'KOPT'], 'control': 'TABU',
        'seeds': [17, 23], 'fleets': [5], 'workloads': ['CLUSTERED'], 'budgets_seconds': [15, 90, 120, 240],
        'parallel_cases': 1}}


def raw_daily(case=None):
    case = case or expand(config())[0]
    return {'type': 'result', 'variant': case['solver'], 'technicians': case['fleet'], 'workload': case['workload'],
        'seed': case['seed'], 'budgetMs': case['budget_ms'], 'datasetFingerprint': 'fixture-A', 'violations': 0,
        'reference': {'costCents': 100}, 'after': {'costCents': 102, 'fairness': {'variance': .01}}, 'elapsedMs': 15001}


def raw_booking():
    audit = {'independentlyValidated': True, 'routingIdentity': 'roads-A', 'days': [{'policy': {'costCents': 123}}]}
    return {'type': 'case', 'variant': 'INSERTION', 'size': 5, 'workload': 'DISPERSED', 'datasetFingerprint': 'fixture-B',
        'independentlyValidated': True, 'promiseViolations': 0, 'before': audit, 'after': copy.deepcopy(audit),
        'concurrency': 1, 'cache': 'warm', 'attempts': [
            {'index': 0, 'served': True, 'completed': False, 'outcome': 'AVAILABLE', 'elapsedMs': 10},
            {'index': 1, 'served': False, 'completed': True, 'outcome': 'NO_CANDIDATE_FOUND', 'elapsedMs': 90}]}


PROVENANCE = {'type': 'provenance', 'routingIdentity': 'fixtures-v1', 'budgetMs': 15000,
              'seed': 17, 'requests': 2, 'dates': ['2026-09-30']}


def evidence(path, case):
    path.write_text(json.dumps(PROVENANCE) + '\n' + json.dumps(raw_daily(case)) + '\n', encoding='utf-8')


class ConfigurationTests(unittest.TestCase):
    def test_initial_matrices(self):
        root = Path(__file__).resolve().parents[1] / 'experiments/configs'
        shipped = read_json(root / 'daily-budget.json')['daily']
        daily = expand(read_json(root / 'daily-budget.json'))
        treatments = len(shipped['solvers']) * len(shipped['fleets']) * len(shipped['workloads']) * len(shipped['seeds'])
        self.assertEqual(len(daily), treatments * len(shipped['budgets_seconds']))
        self.assertEqual(sum(r['budget_ms'] for r in daily), treatments * round(sum(shipped['budgets_seconds']) * 1000))
        self.assertEqual((len(shipped['seeds']), shipped['parallel_cases']), (10, 6))
        self.assertEqual(len(expand(read_json(root / 'booking-comparison.json'))), 360)

    def test_planned_three_workload_matrices(self):
        root = Path(__file__).resolve().parents[1] / 'experiments/configs'
        daily = read_json(root / 'daily-budget.json')
        daily['daily'].update(workloads=['CLUSTERED', 'DISPERSED', 'SPARSE'], budgets_seconds=[15, 30, 60, 90, 120, 240])
        shipped = daily['daily']
        treatments = len(shipped['solvers']) * len(shipped['fleets']) * 3 * len(shipped['seeds'])
        self.assertEqual(len(expand(daily)), treatments * 6)
        self.assertEqual(sum(c['budget_ms'] for c in expand(daily)), treatments * 555000)
        booking = read_json(root / 'booking-comparison.json')
        booking['booking']['caches'] = ['cold', 'warm']
        self.assertEqual(len(expand(booking)), 720)

    def test_reject_bad_configuration(self):
        modifications = [lambda c: c.update(unknown=True), lambda c: c.update(version=True),
            lambda c: c.update(daily=None), lambda c: c['daily'].update(count=4),
            lambda c: c['daily'].update(solvers=[]), lambda c: c['daily'].update(solvers=['NOPE']),
            lambda c: c['daily'].update(seeds=[17, 17]), lambda c: c['daily'].update(seeds=[True]),
            lambda c: c['daily'].update(fleets=[7]), lambda c: c['daily'].update(control='SUBLIST'),
            lambda c: c['daily'].update(budgets_seconds=[241]), lambda c: c['daily'].pop('workloads'),
            lambda c: c['daily'].pop('parallel_cases'), lambda c: c['daily'].update(parallel_cases=0),
            lambda c: c['daily'].update(parallel_cases=True), lambda c: c['daily'].update(parallel_cases=2.0),
            lambda c: c['daily'].update(parallel_cases=None), lambda c: c['daily'].update(parallel_cases='6')]
        modifications.append(lambda c: c['daily'].update(budgets_seconds=[15, 15.0]))
        for modify in modifications:
            with self.subTest(modify=modify):
                c = config()
                modify(c)
                with self.assertRaises(ValueError):
                    validate(c)

    def test_duplicate_json_fields(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'bad.json'
            path.write_text('{"version":1,"version":1}')
            with self.assertRaises(ValueError):
                read_json(path)

    def test_reproducible_unique_counterbalanced_cases_and_budget_propagation(self):
        cases = expand(config())
        self.assertEqual(cases, expand(config()))
        self.assertEqual(len(cases), len({c['id'] for c in cases}))
        self.assertNotEqual(cases[0]['budget_ms'], cases[8]['budget_ms'])
        for budget in (90000, 120000, 240000):
            case = next(c for c in cases if c['budget_ms'] == budget)
            self.assertIn(f'-Dbenchmark.durationMs={budget}', rt.daily_command(Path('frozen'), case, 'rev', 'raw', 'java'))

    def test_parallel_cases_rejected_in_booking_and_shipped_configs_valid(self):
        root = Path(__file__).resolve().parents[1] / 'experiments/configs'
        booking = {'version': 1, 'name': 'b', 'booking': {'solvers': ['INSERTION'], 'control': 'INSERTION', 'seeds': [17],
            'fleets': [5], 'workloads': ['DISPERSED'], 'concurrency': [1], 'caches': ['warm'], 'requests': 2}}
        validate(copy.deepcopy(booking))
        booking['booking']['parallel_cases'] = 2
        with self.assertRaises(ValueError):
            validate(booking)
        for name, parallel in [('daily-smoke', 1), ('daily-budget', 6), ('daily-contention-1', 1), ('daily-contention-6', 6)]:
            self.assertEqual(validate(read_json(root / f'{name}.json'))['daily']['parallel_cases'], parallel)
        one, six = (read_json(root / f'daily-contention-{n}.json') for n in (1, 6))
        for c in (one, six):
            c.pop('name')
            c['daily'].pop('parallel_cases')
        self.assertEqual(one, six)

    def test_daily_flags_are_identical_for_every_case_and_gc_log_stays_outside_frozen_tree(self):
        case = expand(config())[0]
        command = rt.daily_command(Path('frozen'), case, 'rev', str(Path('attempt') / 'raw.jsonl'), 'java')
        self.assertEqual(command[2:4], ['-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC'])
        self.assertIn('-Xlog:gc:file=' + str(Path('attempt') / 'gc.log'), command)

    def test_dry_run_wall_time_simulates_slots_in_dispatch_order(self):
        from experiment_config import estimate_daily_wall_seconds
        cases = [{'kind': 'daily', 'budget_ms': 10000}] * 4 + [{'kind': 'daily', 'budget_ms': 20000}] * 2
        self.assertEqual(estimate_daily_wall_seconds(cases, 1), 80 + 6 * 2.5)
        self.assertEqual(estimate_daily_wall_seconds(cases, 2), 2 * 12.5 + 22.5)
        self.assertEqual(estimate_daily_wall_seconds(cases, 6), 22.5)


class LauncherTests(unittest.TestCase):
    def test_modes_select_expected_config_and_skip_setup_for_dry_run(self):
        for experiment, mode, name in [('daily', 'dry-run', 'daily-budget.json'),
                                       ('daily', 'smoke', 'daily-smoke.json'),
                                       ('booking', 'full', 'booking-comparison.json')]:
            with self.subTest(experiment=experiment, mode=mode), \
                 patch.object(launcher, 'setup') as setup, patch.object(launcher.subprocess, 'run') as run:
                run.return_value.returncode = 0
                self.assertEqual(launcher.main([experiment, mode]), 0)
                argv = run.call_args.args[0]
                self.assertEqual(Path(argv[3]).name, name)
                self.assertEqual('--dry-run' in argv, mode == 'dry-run')
                self.assertEqual(setup.call_count, 0 if mode == 'dry-run' else 1)

    def test_custom_config_cannot_silently_launch_other_kind(self):
        with patch.object(launcher, 'setup') as setup:
            with self.assertRaisesRegex(ValueError, 'selected daily'):
                launcher.main(['daily', 'full', '--config', str(launcher.ROOT / 'experiments/configs/booking-smoke.json')])
            setup.assert_not_called()

    def test_booking_requires_explicit_test_database_before_setup(self):
        booking = read_json(launcher.ROOT / 'experiments/configs/booking-smoke.json')
        with patch.object(launcher, 'check_tools'), patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, 'DATABASE_URL'):
                launcher.setup(booking)

    def test_booking_setup_installs_missing_node_dependencies(self):
        booking = read_json(launcher.ROOT / 'experiments/configs/booking-smoke.json')
        with tempfile.TemporaryDirectory() as folder, patch.object(launcher, 'ROOT', Path(folder)), \
             patch.object(launcher, 'check_tools'), patch.object(launcher, 'database_env'), \
             patch.object(launcher, 'routing_identity'), patch.object(launcher.subprocess, 'run') as run:
            launcher.setup(booking)
        self.assertEqual([call.args[0][-2:] for call in run.call_args_list],
                         [['web', 'ci'], ['run', 'prisma:generate']])

    def test_launcher_finds_jdk_home_when_java_is_on_path(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder) / 'jdk-25'
            (home / 'bin').mkdir(parents=True)
            java = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            javac = home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
            java.touch()
            javac.touch()
            version = SimpleNamespace(stderr='java version "25.0.1"\n', stdout='')
            settings = SimpleNamespace(stderr=f'    java.home = {home}\n', stdout='')
            with patch.dict(os.environ, {}, clear=True), patch.object(launcher.shutil, 'which', side_effect=[str(java), 'node']), \
                 patch.object(launcher.subprocess, 'run', side_effect=[version, settings]):
                launcher.check_tools()
                self.assertEqual(os.environ['JAVA_HOME'], str(home))

    def test_progress_counts_completed_cases_and_elapsed_time(self):
        output = io.StringIO()
        tick = [0]
        with rt.Progress(4, stream=output, clock=lambda: tick[0]) as progress:
            tick[0] = 61
            progress.update('Resume', completed=2)
            tick[0] = 63
            progress.update('Case 3/4', active=True)
            tick[0] = 68
            self.assertIn('case 00:00:05', progress.line())
            progress.update('Finished case 3/4', completed=3)
        self.assertIn('[######......] 2/4 elapsed 00:01:01', output.getvalue())
        self.assertIn('3/4 elapsed 00:01:08', output.getvalue())


class AnalysisTests(unittest.TestCase):
    def daily(self, solver='TABU', cost=100, fixture='fixture-A', seed=17, budget=15000):
        r = normalize(raw_daily(), PROVENANCE, 'current')
        r.update(solver=solver, reference_cost=cost, fixture=fixture, seed=seed, budget_ms=budget, source='test', line=1)
        return r

    def test_correct_pairing_and_signed_costs(self):
        rows, issues = compare([self.daily(), self.daily('KOPT', 90), self.daily('KOPT', 95, budget=30000), self.daily(cost=110, budget=30000)], {'daily': 'TABU'})
        self.assertFalse(issues)
        self.assertEqual(rows[1]['control_savings_cents'], 10)
        self.assertEqual(rows[2]['control_savings_cents'], 15)
        self.assertEqual(rows[2]['own_15s_improvement_cents'], -5)  # Never force monotonic curves.

    def test_missing_control_or_fixture_mismatch_is_unavailable(self):
        rows, _ = compare([self.daily(), self.daily('KOPT', 90, 'different')], {'daily': 'TABU'})
        self.assertIsNone(rows[1]['control_savings_cents'])
        self.assertEqual(rows[1]['pair_status'], 'unpaired_control')

    def test_duplicates_all_excluded(self):
        rows, issues = compare([self.daily(), self.daily(), self.daily('KOPT')], {'daily': 'TABU'})
        self.assertEqual(len(issues), 2)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['pair_status'], 'unpaired_control')

    def test_conflicting_fixture_duplicate_not_averaged(self):
        rows, issues = compare([self.daily(), self.daily(fixture='other')], {'daily': 'TABU'})
        self.assertEqual(rows, [])
        self.assertEqual(len(issues), 2)

    def test_missing_null_metrics_preserved(self):
        raw = raw_daily()
        raw['reference'] = None
        raw['after']['fairness'] = None
        row = normalize(raw, PROVENANCE, 'current')
        self.assertIsNone(row['reference_cost'])
        self.assertIsNone(row['fairness'])
        self.assertIsNone(equal_seed_summary([row], 'reference_cost')['mean'])
        raw['after']['costCents'] = False
        with self.assertRaises(ValueError):
            normalize(raw, PROVENANCE, 'current')

    def test_equal_seed_weighting(self):
        rows = [self.daily(cost=100), self.daily(cost=200), self.daily(cost=300, seed=23)]
        self.assertEqual(equal_seed_summary(rows, 'reference_cost')['mean'], 225)

    def test_overlapping_served_incomplete_and_pooled_percentiles(self):
        row = normalize(raw_booking(), PROVENANCE, 'current')
        self.assertEqual((row['served'], row['incomplete']), (1, 1))
        self.assertEqual(row['served_rate'], .5)
        row2 = copy.deepcopy(row)
        row2['seed'] = 23
        row2['attempts'][0]['elapsed_ms'] = 20
        row2['attempts'][1]['elapsed_ms'] = 100
        summary = summary_groups([row, row2])[0]
        self.assertEqual(summary['latency']['p50_ms'], 20)
        self.assertEqual(summary['latency']['p95_ms'], 100)
        self.assertIsNone(quantile([], .95))

    def test_booking_cost_requires_same_customers(self):
        row = normalize(raw_booking(), PROVENANCE, 'current')
        row.update(source='test', line=1)
        other = dict(row, solver='BOUNDED', served_indices=[1])
        rows, _ = compare([row, other], {'booking': 'INSERTION'})
        self.assertEqual(rows[1]['pair_status'], 'different_served_customers')
        self.assertIsNone(rows[1]['control_savings_cents'])

    def test_booking_nulls_missing_indices_and_routing_rejected(self):
        for mutate in [lambda r: r['attempts'][0].update(served=None), lambda r: r['attempts'][0].update(index=1),
                       lambda r: r['after'].update(routingIdentity='other'), lambda r: r.update(before=None)]:
            r = raw_booking()
            mutate(r)
            with self.assertRaises(ValueError):
                normalize(r, PROVENANCE, 'current')

    def test_null_request_observation_rejected(self):
        raw = raw_booking()
        raw['attempts'][0] = None
        with self.assertRaises(ValueError):
            normalize(raw, PROVENANCE, 'current')

    def test_missing_latency_and_historical_mixed_latency_excluded(self):
        raw = raw_booking()
        raw['attempts'][0]['elapsedMs'] = None
        raw['attempts'][1]['outcome'] = 'SELECTION_CONFLICT'
        row = normalize(raw, PROVENANCE, 'current')
        self.assertIsNone(row['p95_ms'])
        self.assertEqual(row['unknown_completion'], 1)

    def test_frozen_calendar_evidence_requires_both_isolation_observations(self):
        provenance = {**PROVENANCE, 'calendarReference': '2026-09-30T04:59:59.000Z'}
        raw = raw_booking()
        settings = {'scheduler.optimizer.cron': '-', 'routing.cache.cleanup-cron': '-',
                    'routing.prewarm.enabled': 'false', 'time-off.analysis.enabled': 'false',
                    'benchmark.calendar-reference': '2026-09-30T04:59:59Z'}
        raw.update(processBefore={'configuration': settings}, processAfter={'configuration': settings})
        frozen = normalize(raw, provenance, 'current')
        historical = normalize(raw_booking(), PROVENANCE, 'current')
        from experiment_analysis import pair_key
        self.assertNotEqual(pair_key(frozen), pair_key(historical))
        for phase in ('processBefore', 'processAfter'):
            for key in settings:
                altered = copy.deepcopy(raw)
                altered[phase]['configuration'][key] = 'invalid'
                with self.assertRaises(ValueError):
                    normalize(altered, provenance, 'current')
        with self.assertRaises(ValueError):
            normalize(raw, {**provenance, 'calendarReference': '2026-09-30T04:59:59'}, 'current')

    def test_partial_raw_kept_and_reported(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'raw.jsonl'
            evidence(path, expand(config())[0])
            with path.open('a') as stream:
                stream.write('{"truncated":')
            rows, issues = load_raw(path)
            self.assertEqual(len(rows), 1)
            self.assertEqual(len(issues), 1)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)

    def make_run(self, seed=17):
        c = config()
        c['daily'].update(seeds=[seed], budgets_seconds=[.1])
        run = rt.new_run(self.base, c, json.dumps(c).encode())
        (run / 'frozen').mkdir()
        (run / 'frozen/artifact').write_text('frozen')
        rt.write_new(run / 'manifest.json', {'config_hash': digest(c), 'cases_hash': digest(expand(c)),
            'files': rt.tree_hashes(run / 'frozen'), 'runtime': {'java': 'java'}, 'revision': 'a' * 40,
            'parallelism': {'parallel_cases': 1, 'slot_masks': [None]}, 'toolkit_hashes': rt.toolkit_hashes()})
        return run

    def test_unique_runs_and_immutable_receipts(self):
        a, b = self.make_run(), self.make_run()
        self.assertNotEqual(a, b)
        with self.assertRaises(FileExistsError):
            rt.write_new(a / 'config.json', {})

    def test_lock_excludes_competing_runs_and_releases(self):
        import socket
        with socket.socket() as candidate:
            candidate.bind(('127.0.0.1', 0))
            port = candidate.getsockname()[1]
        with rt.measurement_lock(port):
            with self.assertRaises(RuntimeError):
                with rt.measurement_lock(port):
                    pass
        with rt.measurement_lock(port):
            pass

    def test_frozen_mismatch(self):
        run = self.make_run()
        (run / 'frozen/artifact').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            rt.execute(run)

    def test_config_mismatch(self):
        run = self.make_run()
        c = read_json(run / 'config.json')
        c['daily']['seeds'] = [23]
        (run / 'config.json').write_text(json.dumps(c))
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            rt.execute(run)

    def test_duplicate_completion_and_changed_raw_rejected(self):
        run = self.make_run()
        case = read_json(run / 'cases.json')[0]
        for name in ('one', 'two'):
            attempt = run / 'attempts' / case['id'] / name
            attempt.mkdir(parents=True)
            evidence(attempt / 'raw.jsonl', case)
            rt.write_new(attempt / 'completed.json', {'case': case, 'raw_sha256': rt.sha(attempt / 'raw.jsonl')})
        with self.assertRaisesRegex(ValueError, 'Duplicate completed'):
            rt.completed_attempt(run, case)
        (attempt / 'raw.jsonl').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'Completed evidence changed'):
            rt.completed_attempt(run, case)

    def test_changed_booking_identity_rejected_before_launch(self):
        c = {'version': 1, 'name': 'booking', 'booking': {'solvers': ['INSERTION'], 'control': 'INSERTION',
            'seeds': [17], 'fleets': [5], 'workloads': ['DISPERSED'], 'concurrency': [1], 'caches': ['warm'], 'requests': 2}}
        run = rt.new_run(self.base, c, json.dumps(c).encode())
        (run / 'frozen').mkdir()
        rt.write_new(run / 'manifest.json', {'config_hash': digest(c), 'cases_hash': digest(expand(c)),
            'files': {}, 'runtime': {'java': 'java'}, 'toolkit_hashes': rt.toolkit_hashes(), 'booking': {'dates': ['old'], 'calendar_reference': '2026-09-30T04:59:59.000Z'}})
        with patch.object(rt, 'runtime', return_value={'java': 'java'}), patch.object(rt, 'booking_identity', return_value={'dates': ['new']}):
            with self.assertRaisesRegex(ValueError, 'no longer valid'):
                rt.execute(run)

    def test_booking_identity_reuses_saved_reference_on_resume(self):
        reference = '2026-09-30T04:59:59.000Z'
        env = {'DATABASE_URL': 'postgresql://user:secret@localhost/waterflex_test'}
        with patch.object(rt, 'horizon', return_value=['saved']) as horizon, patch.object(rt, 'routing_identity', return_value={'identity': 'roads'}):
            first = rt.booking_identity(Path('frozen'), env, reference)
            second = rt.booking_identity(Path('frozen'), env, first['calendar_reference'])
            self.assertEqual(first, second)
            horizon.assert_called_with(Path('frozen'), reference)

    def test_environment_blocks_case_insensitive_isolation_overrides(self):
        with patch.dict(os.environ, {'routing_prewarm_enabled': 'true', 'TIME_OFF_ANALYSIS_ENABLED': 'true',
                                    'ROUTING_CACHE_CLEANUP_CRON': '* * * * * *', 'spring_profiles_active': 'production'}, clear=True):
            self.assertEqual(rt.environment(), {})

    def test_database_scope_and_credential_metadata(self):
        for url in ('postgresql://user:secret@localhost/waterflex', 'postgresql://user:secret@remote/waterflex_test'):
            with self.assertRaises(ValueError):
                rt.database_env({'DATABASE_URL': url}, 'benchmark_test')
        env = rt.database_env({'DATABASE_URL': 'postgresql://user:secret@localhost/waterflex_test'}, 'benchmark_test')
        self.assertNotIn('secret', env['JDBC_DATABASE_URL'])
        self.assertIn('connection_limit=4', env['DATABASE_URL'])

    def test_interruption_resume_keeps_prior_attempts_and_completed_evidence(self):
        run = self.make_run()
        cases = read_json(run / 'cases.json')
        def interrupted(args, cwd, env, log, timeout, **_):
            path = Path(next(a.split('=', 1)[1] for a in args if str(a).startswith('-Dbenchmark.output=')))
            path.write_text('partial evidence')
            raise KeyboardInterrupt()
        with patch.object(rt, 'runtime', return_value={'java': 'java'}), patch.object(rt, 'command', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                rt.execute(run)
        partial = next((run / 'attempts').rglob('raw.jsonl'))
        def success(args, cwd, env, log, timeout, **_):
            path = Path(next(a.split('=', 1)[1] for a in args if str(a).startswith('-Dbenchmark.output=')))
            case = next(c for c in cases if c['id'] == path.parent.parent.name)
            evidence(path, case)
        with patch.object(rt, 'runtime', return_value={'java': 'java'}), patch.object(rt, 'command', side_effect=success) as mocked:
            rt.execute(run)
            self.assertEqual(mocked.call_count, 2)
            hashes = rt.tree_hashes(run / 'attempts')
            rt.execute(run)
            self.assertEqual(mocked.call_count, 2)
            self.assertEqual(hashes, rt.tree_hashes(run / 'attempts'))
        self.assertEqual(partial.read_text(), 'partial evidence')

    def test_child_cleanup_only_owned_process(self):
        log = self.base / 'child.log'
        with rt.launch([sys.executable, '-c', 'import time; time.sleep(60)'], self.base, os.environ.copy(), log) as process:
            self.assertIsNone(process.poll())
        self.assertIsNotNone(process.poll())

    def test_timeout_and_nonzero_exit_do_not_create_success(self):
        with self.assertRaisesRegex(RuntimeError, 'timed out'):
            rt.command([sys.executable, '-c', 'import time; time.sleep(60)'], self.base,
                       os.environ.copy(), self.base / 'timeout.log', timeout=.1)
        with self.assertRaisesRegex(RuntimeError, 'exited 7'):
            rt.command([sys.executable, '-c', 'raise SystemExit(7)'], self.base,
                       os.environ.copy(), self.base / 'failed.log')

    @unittest.skipIf(os.name == 'nt', 'POSIX process group cleanup')
    def test_exited_parent_still_terminates_owned_descendants(self):
        import signal
        process = SimpleNamespace(pid=123456, poll=lambda: 0, wait=lambda timeout=None: 0)
        with patch.object(rt.subprocess, 'Popen', return_value=process), patch.object(rt.os, 'killpg') as kill:
            with rt.launch(['parent'], self.base, {}, self.base / 'group.log'):
                pass
        self.assertEqual(kill.call_args_list, [unittest.mock.call(123456, signal.SIGTERM), unittest.mock.call(123456, signal.SIGKILL)])

    def test_second_config_and_analysis_preserve_first_artifacts(self):
        from experiment_analysis import analyze
        run = self.make_run()
        for case in read_json(run / 'cases.json'):
            attempt = run / 'attempts' / case['id'] / 'first'
            attempt.mkdir(parents=True)
            evidence(attempt / 'raw.jsonl', case)
            rt.write_new(attempt / 'completed.json', {'case': case, 'raw_sha256': rt.sha(attempt / 'raw.jsonl')})
        first = analyze(run)
        initial = rt.tree_hashes(run)
        second = self.make_run(seed=23)
        self.assertNotEqual(second, run)
        again = analyze(run)
        self.assertNotEqual(first, again)
        current = rt.tree_hashes(run)
        self.assertTrue(all(current[k] == value for k, value in initial.items()))

    def test_historical_import_is_read_only(self):
        import gzip
        archive = self.base / 'historical'
        archive.mkdir()
        rt.write_new(archive / 'experiment-manifest.json', {'dailyFrozenRevision': 'r', 'bookingArtifactSha256': 'h'})
        with gzip.open(archive / 'screen-15.jsonl.gz', 'wt') as stream:
            stream.write(json.dumps(PROVENANCE) + '\n' + json.dumps(raw_daily()) + '\n')
        before = rt.tree_hashes(archive)
        with patch.object(rt, 'ROOT', self.base):
            run = rt.import_history(archive)
        self.assertTrue((run / 'import.json').exists())
        self.assertEqual(before, rt.tree_hashes(archive))


TOPOLOGY = {'cores': [0x3, 0xc, 0x30, 0xc0, 0x300, 0xc00], 'l3': [0x3f, 0xfc0]}
SLOTS = [0xc, 0xc0, 0x30, 0x300, 0xc00]


class TopologyTests(unittest.TestCase):
    def test_slots_skip_core_zero_and_alternate_between_l3_groups(self):
        self.assertEqual(rt.slot_masks(5, TOPOLOGY), SLOTS)
        self.assertEqual(rt.slot_masks(3, TOPOLOGY), SLOTS[:3])
        self.assertTrue(all(not mask & 1 for mask in rt.slot_masks(5, TOPOLOGY)))

    def test_too_many_cases_or_unsupported_platform_fail_clearly(self):
        with self.assertRaisesRegex(ValueError, 'exceeds the 5 usable physical cores'):
            rt.slot_masks(6, TOPOLOGY)
        with self.assertRaisesRegex(ValueError, 'needs Windows'):
            rt.slot_masks(2, None)
        self.assertEqual(rt.slot_masks(1, None), [None])

    def test_cores_that_do_not_partition_into_l3_groups_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'partition'):
            rt.slot_masks(1, {'cores': [0x3, 0xc], 'l3': [0x3]})

    @unittest.skipUnless(os.name == 'nt', 'Windows processor topology')
    def test_real_topology_has_distinct_physical_cores_inside_l3_groups(self):
        topology = rt.topology()
        self.assertEqual(len(set(topology['cores'])), len(topology['cores']))
        self.assertTrue(all(any(core & cache == core for cache in topology['l3']) for core in topology['cores']))
        self.assertEqual(sum(1 for core in topology['cores'] if core & 1), 1)

    @unittest.skipUnless(os.name == 'nt', 'Windows job object affinity')
    def test_job_object_confines_child_to_requested_physical_core(self):
        mask = rt.slot_masks(1, rt.topology())[0]
        script = ('import ctypes,time\nk=ctypes.windll.kernel32\nk.GetCurrentProcess.restype=ctypes.c_void_p\n'
                  'k.GetProcessAffinityMask.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_size_t),ctypes.POINTER(ctypes.c_size_t)]\ntime.sleep(1)\n'
                  'p=ctypes.c_size_t();s=ctypes.c_size_t()\nk.GetProcessAffinityMask(k.GetCurrentProcess(),ctypes.byref(p),ctypes.byref(s))\n'
                  'print(p.value)')
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'child.log'
            with rt.launch([sys.executable, '-c', script], folder, os.environ.copy(), log, affinity=mask) as process:
                process.wait(timeout=30)
            self.assertEqual(int(log.read_text().strip()), mask)

    def test_launch_passes_affinity_to_child_job(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(rt, 'child_job', return_value=lambda: None) as job:
            with rt.launch([sys.executable, '-c', 'pass'], folder, os.environ.copy(), Path(folder) / 'a.log', affinity=0xc) as process:
                process.wait(timeout=30)
            self.assertEqual(job.call_args.args[1], 0xc)
            with rt.launch([sys.executable, '-c', 'pass'], folder, os.environ.copy(), Path(folder) / 'b.log') as process:
                process.wait(timeout=30)
            self.assertIsNone(job.call_args.args[1])

    def test_occupancy_records_fewest_neighbors(self):
        occupancy = rt.Occupancy()
        self.assertEqual([occupancy.start(k) for k in 'abc'], [0, 1, 2])
        self.assertEqual(occupancy.finish('b'), 1)  # c started after b; a still runs beside it.
        self.assertEqual(occupancy.finish('c'), 1)
        self.assertEqual(occupancy.finish('a'), 0)
        self.assertIsNone(occupancy.finish('a'))

    def test_progress_counts_concurrent_completions_exactly(self):
        with rt.Progress(400, stream=io.StringIO()) as progress:
            for key in range(400):
                progress.start_case(key, 'x')
            threads = [threading.Thread(target=lambda keys=range(i, 400, 8): [progress.finish_case(k, 'done') for k in keys]) for i in range(8)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            self.assertEqual((progress.completed, progress.running), (400, {}))


class ParallelDispatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        c = config()
        c['daily'].update(seeds=[17, 23, 41, 59], budgets_seconds=[.1], parallel_cases=3)
        self.run = rt.new_run(self.base, c, json.dumps(c).encode())
        self.cases = read_json(self.run / 'cases.json')
        self.info = {'java': 'java', 'topology': TOPOLOGY}
        (self.run / 'frozen').mkdir()
        (self.run / 'frozen/artifact').write_text('frozen')
        rt.write_new(self.run / 'manifest.json', {'config_hash': digest(c), 'cases_hash': digest(expand(c)),
            'files': rt.tree_hashes(self.run / 'frozen'), 'runtime': self.info, 'revision': 'a' * 40,
            'parallelism': {'parallel_cases': 3, 'slot_masks': SLOTS[:3]}, 'toolkit_hashes': rt.toolkit_hashes()})

    def case_for(self, args):
        path = Path(next(a.split('=', 1)[1] for a in args if str(a).startswith('-Dbenchmark.output=')))
        return path, next(c for c in self.cases if c['id'] == path.parent.parent.name)

    def test_every_case_runs_once_within_slots_and_no_mask_is_shared(self):
        lock, active, seen = threading.Lock(), set(), {'peak': 0, 'calls': []}
        def fake(args, cwd, env, log, timeout, affinity=None, stop=None):
            path, case = self.case_for(args)
            with lock:
                self.assertNotIn(affinity, active)
                active.add(affinity)
                seen['peak'] = max(seen['peak'], len(active))
                seen['calls'].append(case['id'])
            time.sleep(.05)
            evidence(path, case)
            with lock:
                active.remove(affinity)
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command', side_effect=fake):
            rt.execute(self.run)
        self.assertEqual(sorted(seen['calls']), sorted(c['id'] for c in self.cases))
        self.assertGreater(seen['peak'], 1)
        self.assertLessEqual(seen['peak'], 3)
        for case in self.cases:
            attempts = list((self.run / 'attempts' / case['id']).glob('*'))
            self.assertEqual(len(attempts), 1)
            started, completed = read_json(attempts[0] / 'started.json'), read_json(attempts[0] / 'completed.json')
            self.assertIn(started['mask'], SLOTS[:3])
            self.assertEqual(completed['mask'], started['mask'])
            self.assertTrue(0 <= completed['in_flight_min'] <= 2)

    def test_dispatch_order_follows_saved_cases(self):
        order = []
        def fake(args, cwd, env, log, timeout, **_):
            path, case = self.case_for(args)
            order.append(case['id'])
            evidence(path, case)
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command', side_effect=fake):
            rt.execute(self.run)
        started = sorted(order, key=[c['id'] for c in self.cases].index)
        self.assertEqual(sorted(order[:3]), sorted(started[:3]))  # The first wave is the first three saved cases.

    def test_failure_stops_dispatch_but_keeps_in_flight_receipts(self):
        lock, calls = threading.Lock(), []
        def fake(args, cwd, env, log, timeout, **_):
            path, case = self.case_for(args)
            with lock:
                calls.append(case['id'])
                first = len(calls) == 1
            if first:
                raise RuntimeError('solver crashed')
            time.sleep(.3)
            evidence(path, case)
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command', side_effect=fake):
            with self.assertRaisesRegex(RuntimeError, 'solver crashed'):
                rt.execute(self.run)
        self.assertEqual(len(calls), 3)
        receipts = [sorted(p.name for p in a.iterdir() if p.name.endswith('.json')) for a in (self.run / 'attempts').rglob('20*')]
        self.assertEqual(sum('failed.json' in r for r in receipts), 1)
        self.assertEqual(sum('completed.json' in r for r in receipts), 2)
        self.assertEqual(len(receipts), 3)

    def test_interrupt_marks_in_flight_attempts_and_resume_retries_only_unfinished(self):
        lock, ready, calls = threading.Lock(), threading.Event(), []
        def blocking(args, cwd, env, log, timeout, stop=None, **_):
            path, case = self.case_for(args)
            with lock:
                calls.append(case['id'])
                finishes = len(calls) == 1
                if len(calls) == 3:
                    ready.set()
            if finishes:
                evidence(path, case)
                return
            self.assertTrue(stop.wait(10))
            raise rt.StopRequested('stop')
        def interrupting_wait(*_, **__):
            ready.wait(10)
            raise KeyboardInterrupt()
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command', side_effect=blocking), \
             patch.object(rt.concurrent.futures, 'wait', side_effect=interrupting_wait):
            with self.assertRaises(KeyboardInterrupt):
                rt.execute(self.run)
        names = [sorted(p.name for p in a.iterdir() if p.name.endswith('.json')) for a in (self.run / 'attempts').rglob('20*')]
        self.assertEqual(sum('interrupted.json' in n for n in names), 2)
        self.assertEqual(sum('completed.json' in n for n in names), 1)
        resumed = []
        def success(args, cwd, env, log, timeout, **_):
            path, case = self.case_for(args)
            resumed.append(case['id'])
            evidence(path, case)
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command', side_effect=success):
            rt.execute(self.run)
        self.assertEqual(len(resumed), len(self.cases) - 1)
        self.assertNotIn(calls[0], resumed)

    def test_resume_rejected_after_topology_change(self):
        changed = {**self.info, 'topology': {'cores': TOPOLOGY['cores'][:-1], 'l3': TOPOLOGY['l3']}}
        with patch.object(rt, 'runtime', return_value=changed), patch.object(rt, 'command') as mocked:
            with self.assertRaisesRegex(ValueError, 'Runtime/hardware provenance mismatch'):
                rt.execute(self.run)
        mocked.assert_not_called()

    def test_resume_rejected_when_saved_slot_masks_do_not_match(self):
        c = read_json(self.run / 'config.json')
        other = rt.new_run(self.base, c, json.dumps(c).encode())
        (other / 'frozen').mkdir()
        rt.write_new(other / 'manifest.json', {'config_hash': digest(c), 'cases_hash': digest(expand(c)), 'files': {},
            'runtime': self.info, 'revision': 'a' * 40, 'parallelism': {'parallel_cases': 3, 'slot_masks': SLOTS[1:4]},
            'toolkit_hashes': rt.toolkit_hashes()})
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command') as mocked:
            with self.assertRaisesRegex(ValueError, 'parallelism provenance mismatch'):
                rt.execute(other)
        mocked.assert_not_called()

    def test_parallel_cases_beyond_saved_topology_is_rejected_before_launch(self):
        c = read_json(self.run / 'config.json')
        c['daily']['parallel_cases'] = 6
        other = rt.new_run(self.base, c, json.dumps(c).encode())
        (other / 'frozen').mkdir()
        rt.write_new(other / 'manifest.json', {'config_hash': digest(c), 'cases_hash': digest(expand(c)), 'files': {},
            'runtime': self.info, 'revision': 'a' * 40, 'parallelism': {'parallel_cases': 6, 'slot_masks': SLOTS},
            'toolkit_hashes': rt.toolkit_hashes()})
        with patch.object(rt, 'runtime', return_value=self.info), patch.object(rt, 'command') as mocked:
            with self.assertRaisesRegex(ValueError, 'exceeds the 5 usable'):
                rt.execute(other)
        mocked.assert_not_called()

    def test_command_polls_and_honors_stop_event(self):
        stop = threading.Event()
        threading.Timer(.3, stop.set).start()
        started = time.monotonic()
        with self.assertRaises(rt.StopRequested):
            rt.command([sys.executable, '-c', 'import time; time.sleep(60)'], self.base, os.environ.copy(),
                       self.base / 'stop.log', timeout=60, stop=stop)
        self.assertLess(time.monotonic() - started, 10)


class ThroughputTests(unittest.TestCase):
    def raw(self, reference=None, fairness=None):
        row = raw_daily()
        if reference is not None:
            row['referencePhase'] = reference
        if fairness is not None:
            row['fairnessPhase'] = fairness
        return normalize(row, PROVENANCE, 'current')

    def test_rates_are_moves_per_second_and_missing_values_stay_null(self):
        row = self.raw({'moveEvaluations': 500, 'solveMs': 100}, {'moveEvaluations': None, 'solveMs': 100})
        self.assertEqual(row['reference_move_rate'], 5000)
        self.assertIsNone(row['fairness_move_rate'])
        for phase in (None, {'solveMs': 100}, {'moveEvaluations': 5}, {'moveEvaluations': 5, 'solveMs': 0}):
            self.assertIsNone(self.raw(phase)['reference_move_rate'])
        self.assertIsNone(self.raw({'moveEvaluations': 0, 'solveMs': 0})['reference_move_rate'])
        self.assertEqual(self.raw({'moveEvaluations': 0, 'solveMs': 100})['reference_move_rate'], 0)

    def test_invalid_move_statistics_rejected(self):
        for bad in (True, -1, float('nan'), '5'):
            with self.assertRaises(ValueError):
                self.raw({'moveEvaluations': bad, 'solveMs': 100})

    def test_solver_summary_never_zero_fills(self):
        from experiment_analysis import throughput_summary
        rows = [self.raw({'moveEvaluations': 1000, 'solveMs': 100}), self.raw({'solveMs': 100}), self.raw()]
        for r in rows:
            r['kind'] = 'daily'
        summary = throughput_summary(rows)
        self.assertEqual(len(summary), 1)
        self.assertEqual(summary[0]['reference_move_rate'], {'observed': 1, 'mean': 10000, 'min': 10000, 'max': 10000})
        self.assertEqual(summary[0]['fairness_move_rate'], {'observed': 0, 'mean': None, 'min': None, 'max': None})
        self.assertEqual(summary[0]['total_cases'], 3)


class ContentionTests(unittest.TestCase):
    def row(self, rate, in_flight, solver='TABU', fleet=20, seed=17):
        return dict(solver=solver, seed=seed, fleet=fleet, workload='CLUSTERED', budget_ms=120000, fixture='f', kind='daily',
                    reference_move_rate=rate, fairness_move_rate=rate, in_flight_min=in_flight)

    def report(self, sequential, parallel):
        import experiment_analysis as analysis
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            (run / 'config.json').write_text(json.dumps({'daily': {'parallel_cases': 6}}))
            with patch.object(analysis, 'run_rows', side_effect=[(sequential, []), (parallel, [])]):
                return analysis.contention_report(run, run)

    def test_median_ratio_excludes_partially_loaded_and_missing_pairs(self):
        seq = [self.row(1000, 0, seed=s) for s in (17, 23, 41, 59)]
        par = [self.row(980, 5, seed=17), self.row(960, 5, seed=23), self.row(500, 2, seed=41), self.row(None, 5, seed=59)]
        report = self.report(seq, par)
        self.assertEqual(report['excluded_pairs'], {'not_fully_loaded': 1, 'throughput_unavailable': 1})
        self.assertAlmostEqual(report['groups'][0]['reference_median_ratio'], .97)
        self.assertEqual(report['groups'][0]['pairs'], 2)
        self.assertTrue(report['accepted'])

    def test_slowdown_beyond_threshold_is_not_accepted_and_empty_is_never_accepted(self):
        self.assertFalse(self.report([self.row(1000, 0)], [self.row(900, 5)])['accepted'])
        empty = self.report([self.row(1000, 0)], [self.row(900, 1)])
        self.assertFalse(empty['accepted'])
        self.assertEqual(empty['groups'], [])


if __name__ == '__main__':
    unittest.main()
