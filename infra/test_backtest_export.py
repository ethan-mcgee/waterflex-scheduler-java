"""Regression coverage for infra/backtest_export.py: a fabricated history imported with the stub road model, simulated
with the fake scheduler, and exported. No real row appears here."""
import json
from collections import Counter
import subprocess
import tempfile
import unittest
from pathlib import Path

import backtest_export as be
import backtest_host as bh
import backtest_import as bi
import backtest_simulate as bs
from test_backtest_import import EXTENSION, JAR, two_depots, write_history
from test_backtest_simulate import FakeScheduler


def simulate(directory, until=None):
    """The import of the fabricated history (two depots, the tail repeated), one segment simulated to its end or until."""
    rows = bi.parse_rows(write_history(directory))
    run = Path(directory) / 'run'
    bi.build(rows, two_depots(availabilityExtension=EXTENSION), run, bi.StubRoadClient())
    sim = Path(directory) / 'sim'
    bs.plan(run, sim, 1, 3, today='2026-11-05', until=until)
    bs.run(sim / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
    return run, sim


class ExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.run_dir, cls.sim = simulate(cls.directory.name)
        cls.summary = be.export(cls.sim, bi.StubRoadClient())
        cls.out = cls.sim / 'export'

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_every_request_has_one_outcome_and_every_booked_job_one_scored_day(self):
        requests = bh.load_json(self.run_dir / 'part2' / 'requests.json')['requests']
        self.assertEqual({r['id'] for r in requests}, set(self.summary['outcomes']))
        self.assertEqual([], self.summary['undecided'])
        booked = {i for i, o in self.summary['outcomes'].items() if o['outcome'] == 'BOOKED'}
        index = bh.load_json(self.out / 'part2' / 'index.json')
        scored = set()
        for entry in index['days']:
            plan = bh.load_json(self.out / 'part2' / 'days' / entry['day'] / 'plan.json')
            ids = sum(plan['routes'].values(), [])
            self.assertEqual(entry['jobs'], len(ids))
            scored |= set(ids)
        self.assertEqual(booked, scored, 'one segment: every booked job is on exactly one scored day')
        self.assertEqual({'scoredTwice': [], 'bookedNotScored': [], 'bookedOutsideTheStudy': []}, self.summary['boundaries'])

    def test_a_part2_day_is_the_books_routes_on_the_studys_own_date(self):
        index = bh.load_json(self.out / 'part2' / 'index.json')
        entry = index['days'][0]
        facts = bh.load_json(self.out / 'part2' / 'days' / entry['day'] / 'facts.json')
        plan = bh.load_json(self.out / 'part2' / 'days' / entry['day'] / 'plan.json')
        self.assertTrue(facts['visits'][0]['windowStart'].startswith(entry['day']), 'the study date, not the shifted API date')
        self.assertEqual(len(facts['locations']) * (len(facts['locations']) - 1), len(facts['roads']['entries']))
        self.assertTrue(all(t['maxOvertimeMinutes'] == 0 and t['shiftEnd'].endswith('01:00:00Z') for t in facts['technicians']), '17:00 PST')
        ids = [v['id'] for v in facts['visits']]
        self.assertEqual({t['id']: [ids[i] for i in t['assigned']] for t in facts['technicians']}, plan['routes'])
        self.assertEqual('part2', index['ruleSet'])
        observed = bh.load_json(self.out / 'part2' / 'days' / entry['day'] / 'observed.json')
        self.assertTrue(all(v['requestLeadDays'] >= 1 for v in observed['visits'].values()), 'booked from R+1')

    def test_the_jobs_point_at_every_day_of_both_parts(self):
        for part, plan in (('part1', 'as-planned.plan.json'), ('part2', 'plan.json')):
            job = bh.load_json(self.out / f'{part}-job.json')
            self.assertEqual(('backtest-score-days', plan), (job['kind'], job['plan']))
            for day in job['days']:
                self.assertTrue((Path(day['directory']) / 'facts.json').exists(), day)
        part1 = bh.load_json(self.out / 'part1-job.json')['days']
        as_run = bh.load_json(self.run_dir / 'variants' / 'as-run' / 'index.json')['days']
        self.assertEqual([e['day'] for e in as_run], [d['day'] for d in part1], 'nothing turned away: every dispatcher day, unchanged')
        self.assertTrue(all('variants' in d['directory'] for d in part1))

    @unittest.skipUnless(JAR.exists(), 'campaign-benchmark jar not built')
    def test_exported_days_seal_in_the_engine(self):
        for entry in bh.load_json(self.out / 'part2' / 'index.json')['days']:
            source = self.out / 'part2' / 'days' / entry['day'] / 'facts.json'
            request, receipt = source.with_suffix('.seal.json'), source.with_suffix('.receipt.json')
            request.write_text(json.dumps({'version': 1, 'kind': 'seal', 'input': str(source), 'output': str(source.with_suffix('.sealed.json'))}), encoding='utf-8')
            result = subprocess.run(['java', '-jar', str(JAR), str(request), str(receipt)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, f'{entry["day"]}: {result.stderr[-2000:]}')


class SameJobsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        rows = bi.parse_rows(write_history(self.directory.name))
        self.run_dir = Path(self.directory.name) / 'run'
        bi.build(rows, two_depots(), self.run_dir, bi.StubRoadClient())
        index = bh.load_json(self.run_dir / 'variants' / 'as-run' / 'index.json')
        self.day = self.run_dir / 'variants' / 'as-run' / 'days' / max(index['days'], key=lambda e: e['jobs'])['day']

    def tearDown(self):
        self.directory.cleanup()

    def test_a_dropped_job_leaves_the_dataset_with_every_index_renumbered(self):
        facts, plan, observed = (bh.load_json(self.day / f) for f in ('facts.json', 'as-planned.plan.json', 'observed.json'))
        drop = facts['visits'][1]['id']
        kept, kept_plan, kept_observed = be.without_visits(facts, plan, observed, {drop})
        self.assertEqual(len(facts['visits']) - 1, len(kept['visits']))
        self.assertEqual(len(facts['locations']) - 1, len(kept['locations']))
        self.assertNotIn(drop, [l['id'] for l in kept['locations']])
        n = len(kept['locations'])
        self.assertEqual((n, n * (n - 1)), (kept['roads']['size'], len(kept['roads']['entries'])))
        ids = [v['id'] for v in kept['visits']]
        self.assertEqual({t['id']: [ids[i] for i in t['assigned']] for t in kept['technicians']}, kept_plan['routes'])
        self.assertTrue(all(kept['locations'][v['location']]['id'] == v['id'] for v in kept['visits']))
        self.assertNotIn(drop, kept_observed['visits'])
        # A road entry keeps its seconds: the leg between the same two places.
        by_place = lambda d: {(d['locations'][e['from']]['id'], d['locations'][e['to']]['id']): e['seconds'] for e in d['roads']['entries']}
        before, after = by_place(facts), by_place(kept)
        self.assertTrue(all(before[pair] == seconds for pair, seconds in after.items()))
        self.assertIs(facts, be.without_visits(facts, plan, observed, {'wo-nobody'})[0], 'nothing to drop, nothing copied')
        if JAR.exists():
            source = Path(self.directory.name) / 'kept.json'
            bi.write_json(source, kept)
            request = source.with_suffix('.seal.json')
            request.write_text(json.dumps({'version': 1, 'kind': 'seal', 'input': str(source), 'output': str(source.with_suffix('.sealed.json'))}), encoding='utf-8')
            result = subprocess.run(['java', '-jar', str(JAR), str(request), str(source.with_suffix('.receipt.json'))], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr[-2000:])


class RefusalTest(unittest.TestCase):
    def test_an_unfinished_simulation_is_refused_and_a_partial_one_exports_through_its_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            run, sim = simulate(directory, until='2024-01-12')
            with self.assertRaisesRegex(be.ExportError, 'partial'):
                be.export(sim, bi.StubRoadClient())
            summary = be.export(sim, bi.StubRoadClient(), partial=True)
            days = [e['day'] for e in bh.load_json(sim / 'export' / 'part2' / 'index.json')['days']]
            self.assertTrue(days and max(days) <= '2024-01-12')
            self.assertTrue(all(o['serviceDate'] is None or o['outcome'] == 'BOOKED' for o in summary['outcomes'].values()))
            other = bi.StubRoadClient()
            other.identity = 'another-map'
            with self.assertRaisesRegex(be.ExportError, 'identity'):
                be.export(sim, other, partial=True)


class WindowExportTest(unittest.TestCase):
    def test_a_window_is_exported_one_copy_at_a_time(self):
        with tempfile.TemporaryDirectory() as directory:
            rows = bi.parse_rows(write_history(directory))
            run = Path(directory) / 'run'
            bi.build(rows, two_depots(availabilityExtension=EXTENSION), run, bi.StubRoadClient())
            sim = Path(directory) / 'cal'
            bs.window(run, sim, '2024-01-15', '2024-01-20', [('b1', 1), ('b2', 1)], '2026-11-05')
            bs.run(sim / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
            with self.assertRaisesRegex(be.ExportError, '--segment'):
                be.export(sim, bi.StubRoadClient())
            be.export(sim, bi.StubRoadClient(), only='b1')
            be.export(sim, bi.StubRoadClient(), only='b2')
            days = lambda name: [e['day'] for e in bh.load_json(sim / f'export-{name}' / 'part2' / 'index.json')['days']]
            self.assertEqual(days('b1'), days('b2'))
            self.assertTrue(days('b1') and all('2024-01-15' <= d < '2024-01-20' for d in days('b1')))


class OwnerTest(unittest.TestCase):
    def test_a_request_belongs_to_the_segment_holding_its_date_and_falls_to_the_next_when_left_waiting(self):
        def segment(name, first, end, outcomes, waiting=(), booked=()):
            book = bh.Book(time_zone='America/Los_Angeles')
            for identifier, date in booked:
                book.appointments[identifier] = bh.Appointment(identifier, 'R15', 60, 35.6, -120.7, date, 't01', 0, 540, 660, 540, 600)
            desk = bh.Desk(seed=1, outcomes=dict(outcomes), waiting={i: {'next': end, 'retries': 1, 'first': end} for i in waiting})
            return ({'name': name, 'first': first, 'end': end, 'stop': bh.add_days(end, -1)}, book, desk, Counter())
        segments = {'s1': segment('s1', '2024-01-01', '2024-02-05', {'wo-1': 'BOOKED', 'wo-3': 'TURNED_AWAY'}, waiting=['wo-2'], booked=[('wo-1', '2024-01-10')]),
                    's2': segment('s2', '2024-02-05', '2024-03-04', {'wo-2': 'BOOKED', 'wo-3': 'BOOKED'}, booked=[('wo-2', '2024-02-06'), ('wo-3', '2024-02-07')])}
        requests = {'requests': [{'id': 'wo-1', 'requestDate': '2023-12-28'}, {'id': 'wo-2', 'requestDate': '2024-02-02'},
                                 {'id': 'wo-3', 'requestDate': '2024-01-20'}]}
        decided, undecided = be.outcomes(requests, segments, partial=False)
        self.assertEqual(('BOOKED', 's1'), (decided['wo-1']['outcome'], decided['wo-1']['segment']), 'the backlog belongs to the first segment')
        self.assertEqual(('BOOKED', 's2', '2024-02-06'), (decided['wo-2']['outcome'], decided['wo-2']['segment'], decided['wo-2']['serviceDate']))
        self.assertEqual(('TURNED_AWAY', 's1'), (decided['wo-3']['outcome'], decided['wo-3']['segment']), 'the owner decides, not the warm-up')
        self.assertEqual([], undecided)


if __name__ == '__main__':
    unittest.main()
