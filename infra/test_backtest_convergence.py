import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import backtest_convergence as convergence


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding='utf-8')


def screen(directory, rows):
    path = Path(directory) / 'screen.json'
    write(path, {'version': 1, 'kind': 'backtest-screen', 'rows': rows})
    return path


def screen_row(number, feasible=True, visits=None):
    return {'day': f'2024-{1 + number // 28:02d}-{1 + number % 28:02d}', 'feasible': feasible, 'visits': visits if visits is not None else 5 + number % 20,
            'technicians': 3, 'costCents': 10000}


def plan_row(cost, feasible=True, **extra):
    row = {'costCents': cost, 'driveMinutes': 100, 'paidMinutes': 400, 'feasible': feasible, 'unassigned': 0, 'overtimeMinutes': 0,
           'latenessSeconds': 0, 'pastShiftMinutes': 0}
    row.update(extra)
    return row


def arm_case(directory, accepted, cost, budget_ms=15000, solver_extra=None, planned_feasible=True):
    write(directory / 'receipt.json', {'kind': 'backtest-day'})
    write(directory / 'solve.json', {'accepted': accepted, 'reason': 'COST_REDUCTION' if accepted else 'NO_IMPROVEMENT', 'solveMs': budget_ms,
                                      'diagnostics': {'phases': [{'statistics': {'budgetMs': budget_ms * 2 // 3}}, {'statistics': {'budgetMs': budget_ms // 3}}]}})
    write(directory / 'as-planned.row.json', plan_row(10000, feasible=planned_feasible))
    write(directory / 'solver.row.json', plan_row(cost, **(solver_extra or {})))


def run_dir(directory, days):
    """An importer run with the files a start copies, for the given days."""
    run = Path(directory) / 'run'
    for day in days:
        for name in ('facts.json', 'facts-actual-durations.json', 'as-executed.plan.json'):
            write(run / 'days' / day / name, {'file': name, 'day': day})
        write(run / 'days' / day / 'as-planned.plan.json', {'version': 1, 'routes': {'t01': ['booked']}, 'unassigned': []})
    return run


def start_solve(conv, day, accepted, solver_feasible=True, planned_feasible=False):
    """A finished start solve for the day, as backtest-day leaves it."""
    seed = conv / 'seed' / day
    arm_case(seed, accepted, 9000, solver_extra={'feasible': solver_feasible}, planned_feasible=planned_feasible)
    write(seed / 'solver.plan.json', {'version': 1, 'routes': {'t01': ['repaired']}, 'unassigned': []})


class SelectionTest(unittest.TestCase):
    def test_candidates_cover_every_working_day_in_terciles_and_never_change(self):
        rows = [screen_row(n, feasible=n % 5 == 0) for n in range(90)] + [screen_row(95, visits=0)]
        with tempfile.TemporaryDirectory() as directory:
            path = screen(directory, rows)
            first, again = convergence.candidates(path), convergence.candidates(path)
        self.assertEqual(first, again)
        self.assertEqual(91, first['screenRows'])
        self.assertEqual(19, first['holding'], 'every screened row that holds, the day without work included')
        terciles = first['terciles']
        self.assertEqual({'busiest': 24, 'middle': 10, 'lightest': 6}, {name: value['quota'] for name, value in terciles.items()})
        days = [day for value in terciles.values() for day in value['order']]
        self.assertEqual(90, len(days), 'the day without work is no candidate')
        self.assertEqual(len(days), len(set(days)))
        self.assertLessEqual(terciles['lightest']['visits'][1], terciles['middle']['visits'][0])
        self.assertLessEqual(terciles['middle']['visits'][1], terciles['busiest']['visits'][0])
        self.assertNotEqual(sorted(terciles['busiest']['order']), terciles['busiest']['order'], 'each tercile is shuffled')

    def test_starts_are_solved_in_order_until_each_tercile_has_its_quota(self):
        rows = [screen_row(n) for n in range(9)]
        with tempfile.TemporaryDirectory() as directory:
            conv = Path(directory) / 'conv'
            chosen = convergence.candidates(screen(directory, rows), count=3)
            write(conv / 'candidates.json', chosen)
            run = run_dir(directory, [row['day'] for row in rows])
            busiest = chosen['terciles']['busiest']
            self.assertEqual(2, busiest['quota'])
            order = busiest['order']
            self.assertEqual(order[0], convergence.next_seed(run, conv))
            start_solve(conv, order[0], accepted=True)
            self.assertEqual(order[1], convergence.next_seed(run, conv))
            # Neither repaired nor holding as booked: passed over for the next candidate of the tercile.
            start_solve(conv, order[1], accepted=False)
            self.assertEqual(order[2], convergence.next_seed(run, conv))
            # Not improved, but the booked plan holds: the booked plan is the start.
            start_solve(conv, order[2], accepted=False, planned_feasible=True)
            with self.assertRaises(convergence.ConvergenceError):
                convergence.plan(run, conv)
            for name in ('middle', 'lightest'):
                tercile = chosen['terciles'][name]
                for day in tercile['order'][:tercile['quota']]:
                    self.assertEqual(day, convergence.next_seed(run, conv))
                    start_solve(conv, day, accepted=True)
            self.assertIsNone(convergence.next_seed(run, conv))
            selection = convergence.plan(run, conv)
            self.assertEqual(3, selection['count'])
            self.assertEqual({'proposal': 2, 'as-planned': 1}, selection['sources'])
            self.assertEqual(1, selection['unusable'])
            self.assertEqual({'busiest': 0, 'middle': 0, 'lightest': 0}, selection['shortfall'])
            days = [entry['day'] for entry in selection['plan']]
            self.assertEqual(sorted(days), days)
            self.assertNotIn(order[1], days)
            repaired = json.loads((conv / 'starts' / order[0] / 'as-planned.plan.json').read_text(encoding='utf-8'))
            booked = json.loads((conv / 'starts' / order[2] / 'as-planned.plan.json').read_text(encoding='utf-8'))
            self.assertEqual(['repaired'], repaired['routes']['t01'])
            self.assertEqual(['booked'], booked['routes']['t01'])
            self.assertTrue((conv / 'starts' / order[0] / 'facts-actual-durations.json').exists())
            self.assertFalse((conv / 'starts' / order[1]).exists())
            self.assertEqual(str(conv / 'starts' / days[0]), selection['plan'][0]['directory'])

    def test_a_tercile_without_enough_usable_starts_records_its_shortfall(self):
        rows = [screen_row(n) for n in range(6)]
        with tempfile.TemporaryDirectory() as directory:
            conv = Path(directory) / 'conv'
            chosen = convergence.candidates(screen(directory, rows), count=3)
            write(conv / 'candidates.json', chosen)
            run = run_dir(directory, [row['day'] for row in rows])
            while (day := convergence.next_seed(run, conv)) is not None:
                start_solve(conv, day, accepted=False)
            selection = convergence.plan(run, conv)
        self.assertEqual(0, selection['count'])
        self.assertEqual(4, selection['unusable'], 'the lightest quota rounds to 0, so its candidates are never tried')
        self.assertEqual(sum(value['quota'] for value in chosen['terciles'].values()), sum(selection['shortfall'].values()))

    def test_refuses_a_start_solve_without_a_boolean_decision(self):
        rows = [screen_row(n) for n in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            conv = Path(directory) / 'conv'
            write(conv / 'candidates.json', convergence.candidates(screen(directory, rows), count=3))
            run = run_dir(directory, [row['day'] for row in rows])
            day = convergence.next_seed(run, conv)
            start_solve(conv, day, accepted='yes')
            with self.assertRaises(convergence.ConvergenceError):
                convergence.next_seed(run, conv)

    def test_rotates_the_arm_order_so_each_arm_leads_equally(self):
        names = [entry['name'] for entry in convergence.ARMS]
        leads = Counter(convergence.order(n)[0] for n in range(40))
        self.assertEqual(set(names), set(leads))
        self.assertEqual({8}, set(leads.values()))
        for n in range(10):
            self.assertEqual(sorted(names), sorted(convergence.order(n)))

    def test_refuses_a_screen_row_without_a_boolean_verdict(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = screen(directory, [{'day': '2024-01-02', 'visits': 4, 'technicians': 2, 'costCents': 1}])
            with self.assertRaises(convergence.ConvergenceError):
                convergence.candidates(missing)
            text = screen(directory, [dict(screen_row(1), feasible='true')])
            with self.assertRaises(convergence.ConvergenceError):
                convergence.candidates(text)

    def test_screen_job_names_every_day_of_the_split(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            write(run / 'index.json', {'days': [{'day': '2024-01-02', 'split': 'calibration'}, {'day': '2025-01-02', 'split': 'test'}]})
            job = convergence.screen_job(run, 'calibration', run / 'screen.json')
        self.assertEqual('backtest-screen', job['kind'])
        self.assertEqual(['2024-01-02'], [entry['day'] for entry in job['days']])
        self.assertTrue(job['days'][0]['directory'].endswith(str(Path('days') / '2024-01-02')))


class SummaryTest(unittest.TestCase):
    def run_check(self, costs, accepted=True, solver_extra=None, planned_feasible=True):
        """costs: {arm: cost per day}; three days."""
        directory = tempfile.mkdtemp()
        conv = Path(directory) / 'conv'
        plan = []
        for number, day in enumerate(('2024-02-01', '2024-02-02', '2024-02-05')):
            arms = convergence.order(number)
            plan.append({'day': day, 'visits': 10, 'technicians': 3, 'arms': arms})
            for name in arms:
                extra = solver_extra.get(name) if solver_extra else None
                arm_case(conv / 'rows' / day / name, accepted, costs[name], solver_extra=extra, planned_feasible=planned_feasible)
        selection = Path(directory) / 'selection.json'
        write(selection, {'screenRows': 10, 'holding': 3, 'count': 3, 'seed': 3, 'terciles': {}, 'sources': {'proposal': 3}, 'unusable': 0,
                           'shortfall': {}, 'start': convergence.START, 'plan': plan})
        return convergence.summarize(conv, selection, Path(directory) / 'out'), Path(directory) / 'out'

    def test_a_small_saving_keeps_fifteen_seconds(self):
        result, out = self.run_check({'size3-15s': 9000, 'size3-15s-seed18': 9010, 'size3-30s': 8990, 'size3-60s': 8960, 'kopt-15s': 9020})
        self.assertEqual('KEEP_15S', result['decision'])
        self.assertTrue(result['valid'])
        self.assertAlmostEqual(40 / 9000, result['savingsOverBase']['size3-60s']['median'], places=5)
        self.assertEqual(3, result['arms']['size3-15s']['accepted'])
        report = (out / 'report.md').read_text(encoding='utf-8')
        self.assertTrue(report.startswith('# Overnight budget convergence check\n'))
        self.assertNotIn('—', report)
        self.assertTrue((out / 'evidence.json').exists())

    def test_a_large_saving_raises_the_budget(self):
        result, _ = self.run_check({'size3-15s': 9000, 'size3-15s-seed18': 9000, 'size3-30s': 8950, 'size3-60s': 8700, 'kopt-15s': 9000})
        self.assertEqual('RAISE_TO_60S', result['decision'])
        result, _ = self.run_check({'size3-15s': 9000, 'size3-15s-seed18': 9000, 'size3-30s': 8800, 'size3-60s': 8750, 'kopt-15s': 9000})
        self.assertEqual('RAISE_TO_30S', result['decision'])

    def test_a_rejected_proposal_delivers_the_dispatchers_plan(self):
        result, _ = self.run_check({'size3-15s': 9000, 'size3-15s-seed18': 9000, 'size3-30s': 1, 'size3-60s': 1, 'kopt-15s': 9000}, accepted=False)
        self.assertEqual(0, result['savingsOverBase']['size3-60s']['median'], 'every arm delivered the same as-planned cost')
        self.assertEqual(0, result['arms']['size3-60s']['accepted'])

    def test_an_accepted_plan_with_violations_invalidates_the_run(self):
        result, _ = self.run_check({'size3-15s': 9000, 'size3-15s-seed18': 9000, 'size3-30s': 9000, 'size3-60s': 8000, 'kopt-15s': 9000},
                                   solver_extra={'size3-60s': {'latenessSeconds': 60}})
        self.assertFalse(result['valid'])
        self.assertEqual('INVALID_RUN', result['decision'])

    def test_refuses_a_day_whose_booked_plan_does_not_hold(self):
        with self.assertRaises(convergence.ConvergenceError):
            self.run_check({name['name']: 9000 for name in convergence.ARMS}, planned_feasible=False)

    def test_refuses_a_missing_file_and_a_non_boolean_decision(self):
        directory = Path(tempfile.mkdtemp())
        arm_case(directory, True, 9000)
        (directory / 'solver.row.json').unlink()
        with self.assertRaises(convergence.ConvergenceError):
            convergence.case(directory)
        arm_case(directory, 'yes', 9000)
        with self.assertRaises(convergence.ConvergenceError):
            convergence.case(directory)
        arm_case(directory, True, 9000, solver_extra={'costCents': None})
        with self.assertRaises(convergence.ConvergenceError):
            convergence.case(directory)


if __name__ == '__main__':
    unittest.main()
