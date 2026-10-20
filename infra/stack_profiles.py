"""Attribute a stack verification's flight recordings and add them to its evidence as a profiles block.

    python infra/stack_profiles.py --evidence docs/evidence/api-load-<date>-<study>.json --profiles .scratch/stack-verification/profiles

With SCHEDULER_STUDY_PROFILES on, the daily replica records each phase of every proposal on the JDK's profile
configuration (StudyProfiles), the recording the workstation harness makes around each solve, and names the files and
the calculation's thread in the study receipt. This reads every receipt's recordings, keeps the samples of that thread
(a recording is JVM-wide, and two queued calculations overlap), attributes them with the harness's frame groups
(representation_study.attribute) and merges them per image and phase, over every run and by step and fleet. A receipt
without a recording for a phase it ran, or a file that is missing, refuses the whole block: profiles are never partly
blank. The evidence is rewritten with the block added; everything else in it is kept as read.
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

from representation_study import attribute, check, jfr_document, merge

PHASE_SPEED_EVENTS = ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample')


def thread_of(event):
    """The Java thread name a sample was taken on: sampledThread for execution samples, eventThread for allocation samples."""
    values = event.get('values') if isinstance(event, dict) else None
    if not isinstance(values, dict):
        return None
    for key in ('sampledThread', 'eventThread'):
        thread = values.get(key)
        if isinstance(thread, dict) and isinstance(thread.get('javaName'), str):
            return thread['javaName']
    return None


def on_thread(document, thread):
    """The recording's sampled events on one thread, and how many sampled events other threads contributed."""
    check(isinstance(document, dict) and isinstance(document.get('recording'), dict), 'JFR recording object required')
    events = document['recording'].get('events')
    check(isinstance(events, list), 'JFR events required')
    kept, other = [], 0
    for event in events:
        if not isinstance(event, dict) or event.get('type') not in PHASE_SPEED_EVENTS:
            continue
        if thread_of(event) == thread:
            kept.append(event)
        else:
            other += 1
    return {'recording': {'events': kept}}, other


def receipt_parts(receipt, profiles, jfr):
    """Each phase of one receipt attributed from its recording: (phase name, attribution with the other threads' count)."""
    body = receipt.get('body') if isinstance(receipt, dict) else None
    check(isinstance(body, dict), 'receipt body required')
    profile = body.get('profile')
    check(isinstance(profile, dict) and 'recordings' in profile, f"receipt {body.get('proposalId')} has no recordings: {profile}")
    recordings, thread = profile['recordings'], profile.get('thread')
    check(isinstance(recordings, dict) and isinstance(thread, str) and thread, f"receipt {body.get('proposalId')}: recordings and a thread required")
    phases = body.get('phases')
    check(isinstance(phases, list) and phases, f"receipt {body.get('proposalId')}: phases required")
    for phase in phases:
        name = phase.get('name') if isinstance(phase, dict) else None
        check(isinstance(name, str) and name, 'phase name required')
        file = recordings.get(name)
        check(isinstance(file, str) and file, f"receipt {body['proposalId']}: no recording for its {name} phase")
        path = profiles / file
        check(path.is_file(), f'missing recording {path}')
        document, other = on_thread(jfr_document(path, jfr), thread)
        part = attribute(document)
        part['otherThreadSamples'] = other
        yield name.lower(), part


def merged(parts):
    total = merge(parts)
    total['otherThreadSamples'] = sum(part['otherThreadSamples'] for part in parts)
    return total


def summarize(evidence, profiles, jfr):
    """The profiles block: per image and phase over every run ('after fairness'), and per image, step, fleet and phase."""
    runs = evidence.get('runs')
    check(isinstance(runs, list) and runs, 'evidence runs required')
    grouped = {}
    for run in runs:
        check(isinstance(run, dict) and isinstance(run.get('report'), dict), 'run report required')
        image, step, fleet = run.get('image'), run.get('step'), run.get('fleet')
        check(isinstance(image, str) and isinstance(step, int) and isinstance(fleet, int), 'run image, step and fleet required')
        study = run['report'].get('study')
        check(isinstance(study, dict) and isinstance(study.get('receipts'), list) and study['receipts'], f'{image} step {step} {fleet}t: receipts required')
        for receipt in study['receipts']:
            for phase, part in receipt_parts(receipt, profiles, jfr):
                grouped.setdefault(f'{image} {phase}', []).append(part)
                grouped.setdefault(f'{image} step{step} {fleet}t {phase}', []).append(part)
    return {name: merged(parts) for name, parts in sorted(grouped.items())}


def main(arguments):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--profiles', required=True, type=Path)
    parser.add_argument('--jfr', default=shutil.which('jfr') or 'jfr')
    options = parser.parse_args(arguments)
    evidence = json.loads(options.evidence.read_text(encoding='utf-8-sig'))
    check(isinstance(evidence, dict) and 'profiles' not in evidence, 'evidence without a profiles block expected')
    evidence['profiles'] = summarize(evidence, options.profiles, options.jfr)
    options.evidence.write_text(json.dumps(evidence, indent=1), encoding='utf-8')
    print(f"profiles: {len(evidence['profiles'])} groups into {options.evidence}")


if __name__ == '__main__':
    main(sys.argv[1:])
