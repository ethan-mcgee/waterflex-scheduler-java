"""Regression checks for real missing-data and pairing hazards in this report."""
from copy import deepcopy
import json
from pathlib import Path
import unittest
from extract_evidence import audit, complete_sum, unique_object
from report_data import average, quantile, difference, pairs

class ReportDataTests(unittest.TestCase):
    def setUp(self):
        self.day=dict(policy=dict(costCents=0,overtimeMinutes=0,fairness=dict(workloads=[])),
                      confirmedAppointments=0,reservedStops=0,roadSeconds=0,waitingMinutes=0,
                      configuredBufferSeconds=0,roundingSeconds=0)

    def test_explicit_empty_schedule_is_derived_zero(self):
        self.assertEqual(audit({'days':[self.day]})['paid_min'],0)

    def test_absent_workloads_are_not_empty_schedule(self):
        del self.day['policy']['fairness']['workloads']
        self.assertIsNone(audit({'days':[self.day]})['paid_min'])

    def test_null_workloads_remain_unavailable(self):
        self.day['policy']['fairness']['workloads']=None
        self.assertIsNone(audit({'days':[self.day]})['paid_min'])

    def test_empty_list_does_not_hide_confirmed_work(self):
        self.day['confirmedAppointments']=1
        self.assertIsNone(audit({'days':[self.day]})['paid_min'])

    def test_one_missing_day_metric_invalidates_whole_total(self):
        other=deepcopy(self.day);other['roadSeconds']=None
        self.assertIsNone(audit({'days':[self.day,other]})['road_sec'])
        self.assertIsNone(audit({'days':[self.day,other]})['paid_min'])

    def test_null_paid_entry_is_not_zero(self):
        self.day['policy']['fairness']['workloads']=[dict(paidMinutes=None)]
        self.assertIsNone(audit({'days':[self.day]})['paid_min'])

    def test_duplicate_json_key_rejected(self):
        with self.assertRaises(ValueError):unique_object([('served',True),('served',False)])

    def test_invalid_numeric_measurement_rejected(self):
        for value in (True,-1,'0',float('nan')):
            self.day['roadSeconds']=value
            with self.assertRaises(ValueError):audit({'days':[self.day]})

    def test_no_partial_average_or_saving(self):
        self.assertIsNone(average([10,None]))
        self.assertIsNone(difference(None,10))
        self.assertIsNone(complete_sum([None,0]))

    def test_percentiles_pool_requests_and_keep_missing_unavailable(self):
        self.assertEqual(quantile(list(range(1,601)),.95),570)
        self.assertEqual(quantile(list(range(1,51)),.95),48)
        self.assertIsNone(quantile([None,None],.5))

    def test_same_booking_count_does_not_authorize_efficiency_comparison(self):
        rows=json.loads((Path(__file__).parent/'evidence.json').read_text(encoding='utf-8'))['cases']
        examples=[p for p in pairs(rows) if p['insertion']['served']==p['bounded']['served'] and not p['comparable']]
        self.assertTrue(examples)
        self.assertTrue(all(p['savings']['cost_cents'] is None for p in examples))

    def test_different_runtime_setting_blocks_pairing(self):
        rows=json.loads((Path(__file__).parent/'evidence.json').read_text(encoding='utf-8'))['cases'][:2]
        rows[1]['configuration']['booking.search.refinement-ms']='999'
        with self.assertRaisesRegex(ValueError,'Runtime settings differ'):pairs(rows)

if __name__=='__main__':unittest.main()
