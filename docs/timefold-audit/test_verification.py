"""Reject malformed evidence and document drift at the report boundary."""
import tempfile
from pathlib import Path
import unittest
import copy
from docx import Document
from collect_retained_evidence import read
from verify_evidence import verify_hash, verify_parity, verify_campaign
from build_report import inline
class EvidenceBoundaryTest(unittest.TestCase):
    def test_duplicate_fields_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'input.json';path.write_text('{"cost":1,"cost":2}')
            with self.assertRaises(ValueError):read(path)
    def test_nonfinite_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'input.json'
            for value in ('NaN','Infinity','-Infinity'):
                path.write_text('{"cost":'+value+'}')
                with self.assertRaises(ValueError):read(path)
    def test_changed_evidence_rejected(self):
        with self.assertRaises(AssertionError):verify_hash(b'changed',{'path':'input','sha256':'0'*64})
    def test_word_omission_and_reordering_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.docx';doc=Document();doc.add_paragraph('First');doc.add_paragraph('Second');doc.save(path)
            verify_parity('First\n\nSecond',path)
            for source in ('Second\nFirst','First\nSecond\nMissing'):
                with self.assertRaises(AssertionError):verify_parity(source,path)
    def test_word_link_target_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.docx';doc=Document()
            inline(doc.add_paragraph(), '[Guide](https://example.com/wrong)');doc.save(path)
            with self.assertRaises(AssertionError):verify_parity('[Guide](https://example.com/right)',path)
    def test_campaign_arithmetic_rejects_wrong_counts(self):
        root=Path(__file__).resolve().parent
        design=read(root/'evidence/third-review/campaign-design.json')
        report=(root/'Timefold_Audit.md').read_text(encoding='utf-8')
        verify_campaign(design,report)
        design['stages'][0]['expected_cases']+=1
        with self.assertRaises(AssertionError):verify_campaign(design,report)
    def test_campaign_null_and_nonfinite_inputs_rejected(self):
        root=Path(__file__).resolve().parent
        valid=read(root/'evidence/third-review/campaign-design.json')
        report=(root/'Timefold_Audit.md').read_text(encoding='utf-8')
        for field,value in [('factors',None),('factors',[True]),('budgets_seconds',[None]),
                            ('budgets_seconds',[float('nan')]),('warmup_per_observation_seconds',None)]:
            design=copy.deepcopy(valid);design['stages'][0][field]=value
            with self.subTest(field=field,value=value):
                with self.assertRaises(ValueError):verify_campaign(design,report)
if __name__=='__main__':unittest.main()
