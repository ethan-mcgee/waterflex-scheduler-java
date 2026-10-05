"""Reject malformed evidence and document drift at the report boundary."""
import tempfile
from pathlib import Path
import unittest
from docx import Document
from collect_retained_evidence import read
from verify_evidence import verify_hash, verify_parity
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
if __name__=='__main__':unittest.main()
