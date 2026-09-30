"""Regression examples use temporary evidence; they never launch the full study."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from experiment_config import digest, expand, read_json, validate
from experiment_analysis import compare, equal_seed_summary, load_raw, normalize, quantile, summary_groups
import experiment_runtime as rt


def config():
    return {'version': 1, 'name': 'test', 'daily': {'solvers': ['TABU', 'KOPT'], 'control': 'TABU',
        'seeds': [17, 23], 'fleets': [5], 'workloads': ['CLUSTERED'], 'budgets_seconds': [15, 90, 120, 240]}}


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
        daily = expand(read_json(root / 'daily-budget.json'))
        self.assertEqual(len(daily), 480)
        self.assertEqual(sum(r['budget_ms'] for r in daily), 44400000)
        self.assertEqual(len(expand(read_json(root / 'booking-comparison.json'))), 240)

    def test_reject_bad_configuration(self):
        modifications = [lambda c: c.update(unknown=True), lambda c: c.update(version=True),
            lambda c: c.update(daily=None), lambda c: c['daily'].update(count=4),
            lambda c: c['daily'].update(solvers=[]), lambda c: c['daily'].update(solvers=['NOPE']),
            lambda c: c['daily'].update(seeds=[17, 17]), lambda c: c['daily'].update(seeds=[True]),
            lambda c: c['daily'].update(fleets=[7]), lambda c: c['daily'].update(control='SUBLIST'),
            lambda c: c['daily'].update(budgets_seconds=[241]), lambda c: c['daily'].pop('workloads')]
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

    def test_missing_latency_and_historical_mixed_latency_excluded(self):
        raw = raw_booking()
        raw['attempts'][0]['elapsedMs'] = None
        raw['attempts'][1]['outcome'] = 'SELECTION_CONFLICT'
        row = normalize(raw, PROVENANCE, 'current')
        self.assertIsNone(row['p95_ms'])
        self.assertEqual(row['unknown_completion'], 1)

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
            'toolkit_hashes': rt.toolkit_hashes()})
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

    def test_interruption_resume_keeps_prior_attempts_and_completed_evidence(self):
        run = self.make_run()
        cases = read_json(run / 'cases.json')
        def interrupted(args, cwd, env, log, timeout):
            path = Path(next(a.split('=', 1)[1] for a in args if str(a).startswith('-Dbenchmark.output=')))
            path.write_text('partial evidence')
            raise KeyboardInterrupt()
        with patch.object(rt, 'runtime', return_value={'java': 'java'}), patch.object(rt, 'command', side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                rt.execute(run)
        partial = next((run / 'attempts').rglob('raw.jsonl'))
        def success(args, cwd, env, log, timeout):
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


if __name__ == '__main__':
    unittest.main()
