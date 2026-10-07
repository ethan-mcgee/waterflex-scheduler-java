"""Summarize preserved JDK JFR samples separately from timed comparison evidence."""
from collections import Counter
from pathlib import Path
import json
import re
import subprocess

import campaign_config as cc
import campaign_runtime as cr
from experiment_config import read_json
from experiment_runtime import sha, write_new


def summarize(document):
    cc.check(isinstance(document, dict) and isinstance(document.get('recording'), dict), 'JFR recording object required')
    events = document['recording'].get('events')
    cc.check(isinstance(events, list), 'JFR events required')
    counts, stacks = Counter(), Counter()
    allocation = 0
    gc_seconds = []
    for event in events:
        cc.check(isinstance(event, dict) and isinstance(event.get('type'), str) and isinstance(event.get('values'), dict), 'Malformed JFR event')
        kind, values = event['type'], event['values']
        counts[kind] += 1
        if kind == 'jdk.ObjectAllocationSample':
            weight = values.get('weight')
            cc.integer(weight, 0, 2**63 - 1)
            allocation += weight
        elif kind == 'jdk.GarbageCollection':
            duration = values.get('duration')
            cc.check(isinstance(duration, str) and re.fullmatch(r'PT[0-9]+(?:\.[0-9]+)?S', duration), 'JFR GC duration must be explicit ISO seconds')
            gc_seconds.append(float(duration[2:-1]))
        if kind == 'jdk.ExecutionSample':
            stack = values.get('stackTrace')
            if stack is None:
                stacks['unavailable-stack'] += 1
                continue
            cc.check(isinstance(stack, dict) and isinstance(stack.get('frames'), list), 'JFR stack frames required')
            frames = stack['frames']
            if not frames:
                stacks['empty-stack'] += 1
                continue
            method = frames[0].get('method')
            cc.check(isinstance(method, dict) and isinstance(method.get('type'), dict), 'JFR frame method required')
            name, owner = method.get('name'), method['type'].get('name')
            cc.text(name)
            cc.text(owner)
            stacks[owner + '.' + name] += 1
    return {'eventCounts': dict(counts), 'allocationSampleWeightBytes': allocation if counts['jdk.ObjectAllocationSample'] else None,
        'allocationUnavailableReason': None if counts['jdk.ObjectAllocationSample'] else 'No allocation samples observed',
        'gcDurationSeconds': sum(gc_seconds) if gc_seconds else None,
        'gcUnavailableReason': None if gc_seconds else 'No GC duration events observed',
        'executionSampleLeafFrames': dict(stacks.most_common(25)),
        'scope': 'Whole fresh JVM includes warmup, solving, validation and native report generation; sampled weights are estimates, not exact allocation totals'}


def extract(study, output):
    from campaign_study import analyze
    cc.check(analyze(study)['kind'] == 'profile', 'Only completed separate profile studies can be exported')
    cc.check(not output.exists(), 'Profile output must be create-new')
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for probe in sorted((study / 'probes').glob('*.json')):
        run = Path(read_json(probe)['run'])
        config, blocks, _ = cr.verify(run)
        if config['instrumentation']['jfr'] == 'disabled':
            continue
        java = cr.checked_artifact(config['runtime']['java'])
        tool = java.with_name('jfr.exe' if java.suffix == '.exe' else 'jfr')
        cc.check(tool.is_file(), 'Pinned JDK JFR tool unavailable')
        for case_id, row in cr.observations(run, blocks).items():
            recording = Path(row['path']) / 'recording.jfr'
            cc.check(recording.is_file() and recording.stat().st_size > 0, 'Requested recording missing')
            destination = output / f'{case_id}.json'
            # The derived JSON lives outside the sealed case directory; original hashes remain intact.
            with destination.open('xb') as stream:
                result = subprocess.run([str(tool), 'print', '--json', '--events', 'jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection', str(recording)],
                    stdout=stream, stderr=subprocess.PIPE, timeout=60, check=False)
            with (output / f'{case_id}-stderr.log').open('xb') as stream:
                stream.write(result.stderr)
            cc.check(result.returncode == 0, 'JFR export failed; preserve raw output without retry')
            records.append({'caseId': case_id, 'recording': str(recording), 'recordingHash': sha(recording),
                'jdkJfrToolHash': sha(tool), 'exportHash': sha(destination), 'summary': summarize(read_json(destination))})
    cc.check(bool(records), 'No separately instrumented recordings available')
    report = {'version': 1, 'study': str(study), 'records': records, 'performanceComparison': False}
    write_new(output / 'summary.json', report)
    return report
