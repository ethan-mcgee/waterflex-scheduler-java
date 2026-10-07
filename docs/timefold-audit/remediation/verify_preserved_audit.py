"""Verify the frozen audit after its seven runtime characterizations were replaced.

The original verifier and every report/helper receipt remain immutable. Only the
retired characterization source is read from its pinned reviewed Git revision.
Current desired-behavior regressions must pass the independent Java gate.
"""
import argparse
import hashlib
import sys
from pathlib import Path

AUDIT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AUDIT))
import verify_evidence as historical

CHARACTERIZATION = 'scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/TimefoldAuditEvidenceTest.java'


def verify(external=False):
    original = historical.verify_historical
    checked = []

    def pinned_characterization(content, item, receipts):
        if item['path'] == CHARACTERIZATION:
            content = historical.git_bytes(historical.REVIEWED, CHARACTERIZATION)
            checked.append(hashlib.sha256(content).hexdigest())
        original(content, item, receipts)

    historical.verify_historical = pinned_characterization
    try:
        historical.verify(external)
    finally:
        historical.verify_historical = original
    if not checked or len(set(checked)) != 1:
        raise RuntimeError('Missing or inconsistent pinned characterization verification')
    current = (historical.REPO / CHARACTERIZATION).read_bytes()
    print('Original characterization verified from ' + historical.REVIEWED + ': ' + checked[0])
    print('Current replacement regression source SHA256: ' + hashlib.sha256(current).hexdigest())
    print('Current desired behavior requires separate Java tests; this command certifies historical evidence only.')


if __name__ == '__main__':
    if not __debug__:
        raise RuntimeError('Verification requires assertions enabled')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--external', action='store_true')
    verify(parser.parse_args().external)
