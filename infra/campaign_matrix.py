"""Register separate algorithm, neighborhood, and termination treatments from pinned JSON."""
import copy
import uuid
from pathlib import Path

import campaign_config as cc
import campaign_runtime as cr
from experiment_config import read_json
from experiment_runtime import measurement_lock, sha, stamp, write_new


def factor(control, candidate, kind):
    cc.choice(kind, 'algorithm', 'one-move-family', 'termination')
    names = ['id', 'acceptor', 'acceptorSize', 'acceptedCountLimit', 'selectedCountLimit', 'moves',
             'environmentMode', 'moveThreads', 'nativeParallelBenchmarkCount', 'termination']
    for value in (control, candidate):
        cc.fields(value, names)
        cc.fields(value['termination'], ['kind', 'scope', 'spentCap', 'stepCap', 'windowMs', 'minimumImprovementRatio', 'unimprovedMs'])
        cc.check(type(value['moves']) is list and bool(value['moves']), 'Explicit moves required')
    first, second = copy.deepcopy(control), copy.deepcopy(candidate)
    first.pop('id'); second.pop('id')
    if kind == 'algorithm':
        cc.check(first['acceptor'] != second['acceptor'], 'Algorithm treatment must change acceptor')
        for key in ('acceptor', 'acceptorSize', 'acceptedCountLimit'):
            first.pop(key); second.pop(key)
    elif kind == 'one-move-family':
        existing = first.pop('moves'); proposed = second.pop('moves')
        cc.check(len(proposed) == len(existing) + 1 and proposed[:len(existing)] == existing,
                 'Add exactly one move family with unchanged control weights and order')
    else:
        original, changed = first.pop('termination'), second.pop('termination')
        cc.check(original['kind'] == 'fixed' and changed['kind'] != 'fixed', 'Fixed control and early-stop candidate required')
        cc.check(original['spentCap'] == changed['spentCap'] is True
                 and original['stepCap'] == changed['stepCap'], 'Enclosing spent/step caps must survive')
    cc.check(first == second, 'Unregistered factors changed inside a treatment')


def register(path):
    path = path.resolve(); spec = read_json(path)
    cc.fields(spec, ['version', 'name', 'purpose', 'baseCampaign', 'studies', 'outputLocation'])
    cc.check(type(spec['version']) is int and spec['version'] == 1, 'Unsupported matrix version')
    cc.slug(spec['name']); cc.text(spec['purpose']); cc.artifact(spec['baseCampaign'])
    cc.text(spec['outputLocation'])
    base_path = Path(spec['baseCampaign']['path'])
    if not base_path.is_absolute(): base_path = path.parent / base_path
    cc.check(base_path.is_file() and sha(base_path) == spec['baseCampaign']['sha256'], 'Changed base campaign')
    base = cc.resolve(read_json(base_path), base_path.parent)
    cc.check(type(spec['studies']) is list and bool(spec['studies']), 'Explicit studies required')
    for study in spec['studies']: cc.fields(study, ['id', 'factor', 'control', 'candidates'])
    cc.unique([study['id'] for study in spec['studies']])
    expanded = []
    for study in spec['studies']:
        cc.fields(study, ['id', 'factor', 'control', 'candidates'])
        cc.slug(study['id'])
        cc.check(type(study['candidates']) is list and bool(study['candidates']), 'Candidates required')
        for candidate in study['candidates']: factor(study['control'], candidate, study['factor'])
        config = copy.deepcopy(base)
        config.update(name=study['id'], purpose=spec['purpose'], controlId=study['control']['id'],
                      configurations=[study['control'], *study['candidates']])
        cc.validate(config)
        expanded.append((study, config))
    output = Path(spec['outputLocation'])
    if not output.is_absolute(): output = path.parent / output
    output.mkdir(parents=True, exist_ok=False)
    (output / 'original-specification.json').write_bytes(path.read_bytes())
    entries = []
    for study, config in expanded:
        destination = output / (study['id'] + '.json'); write_new(destination, config)
        entries.append({'id': study['id'], 'factor': study['factor'],
                        'configuration': {'path': destination.name, 'sha256': sha(destination)}})
    registration = {'version': 1, 'name': spec['name'], 'purpose': spec['purpose'],
                    'originalSpecificationHash': sha(path), 'baseCampaign': spec['baseCampaign'],
                    'toolkit': cr.toolkit_hashes(), 'campaigns': entries}
    write_new(output / 'registration.json', registration)
    return {'directory': str(output.resolve()), 'registrationHash': sha(output / 'registration.json'), **registration}


def execute(directory):
    """Exactly one dispatch per registration. New attempts need a new registration identity."""
    directory = directory.resolve(); registration = read_json(directory / 'registration.json')
    cc.fields(registration, ['version', 'name', 'purpose', 'originalSpecificationHash', 'baseCampaign', 'toolkit', 'campaigns'])
    cc.check(type(registration['version']) is int and registration['version'] == 1, 'Unsupported registration version')
    cc.check(type(registration['campaigns']) is list and bool(registration['campaigns']), 'Registered campaigns required')
    cc.check(sha(directory / 'original-specification.json') == registration['originalSpecificationHash'], 'Changed matrix specification')
    cc.check(registration['toolkit'] == cr.toolkit_hashes(), 'Changed matrix toolkit')
    cc.check(not (directory / 'dispatch.json').exists(), 'Matrix already dispatched; no automatic retry')
    paths = []
    for entry in registration['campaigns']:
        cc.fields(entry, ['id', 'factor', 'configuration']); cc.slug(entry['id']); cc.artifact(entry['configuration'])
        cc.choice(entry['factor'], 'algorithm', 'one-move-family', 'termination')
        name = entry['configuration']['path']
        cc.check(Path(name).name == name, 'Configuration pointer must remain in registration')
        path = directory / name
        cc.check(sha(path) == entry['configuration']['sha256'], 'Changed registered configuration')
        cc.validate(read_json(path)); paths.append((entry, path))
    cc.unique([entry['id'] for entry, _ in paths])
    results = []; failure = None; state = 'COMPLETE'
    with measurement_lock():
        write_new(directory / 'dispatch.json', {'registrationHash': sha(directory / 'registration.json'),
                                               'toolkit': cr.toolkit_hashes()})
        try:
            for entry, path in paths:
                run = cr.new_run(path)
                write_new(directory / (entry['id'] + '-archive.json'),
                          {'run': str(run), 'manifestHash': sha(run / 'manifest.json')})
                cr.execute(run)
                results.append({'id': entry['id'], 'run': str(run), 'analysis': cr.analyze(run)})
        except BaseException as error:
            state = 'INTERRUPTED' if isinstance(error, KeyboardInterrupt) else 'FAILED'
            failure = {'type': type(error).__name__, 'message': str(error)}
            raise
        finally:
            write_new(directory / 'terminal.json', {'state': state, 'failure': failure,
                                                    'completedCampaigns': [row['id'] for row in results]})
    write_new(directory / 'analysis.json', {'version': 1, 'campaigns': results,
                                         'promotionAuthorized': False})
    return {'directory': str(directory), 'state': state, 'campaignCount': len(results)}


def analyze(directory):
    """Revalidate sealed cases, including failures, without dispatch or rewriting old analyses."""
    directory = directory.resolve()
    registration = read_json(directory / 'registration.json')
    cc.fields(registration, ['version', 'name', 'purpose', 'originalSpecificationHash', 'baseCampaign', 'toolkit', 'campaigns'])
    cc.check(type(registration['version']) is int and registration['version'] == 1, 'Unsupported registration')
    dispatch = read_json(directory / 'dispatch.json')
    cc.fields(dispatch, ['registrationHash', 'toolkit'])
    cc.check(sha(directory / 'registration.json') == dispatch['registrationHash'], 'Changed dispatched registration')
    cc.check(dispatch['toolkit'] == registration['toolkit'], 'Dispatch toolkit differs from registration')
    cc.check(sha(directory / 'original-specification.json') == registration['originalSpecificationHash'], 'Changed original specification')
    cc.check(type(registration['campaigns']) is list and bool(registration['campaigns']), 'Registered campaigns required')
    rows = []
    for entry in registration['campaigns']:
        cc.fields(entry, ['id', 'factor', 'configuration']); cc.slug(entry['id']); cc.artifact(entry['configuration'])
        name = entry['configuration']['path']; cc.check(Path(name).name == name, 'Invalid configuration pointer')
        config_path = directory / name
        cc.check(sha(config_path) == entry['configuration']['sha256'], 'Changed registered configuration')
        config = cc.validate(read_json(config_path))
        pointer_path = directory / (entry['id'] + '-archive.json')
        if not pointer_path.exists():
            rows.append({'id': entry['id'], 'analysis': None, 'reason': 'Not dispatched after retained registration stop',
                         'requestedCases': sum(len(block['cases']) for block in cc.expand(config))})
            continue
        pointer = read_json(pointer_path); cc.fields(pointer, ['run', 'manifestHash']); cc.text(pointer['run'])
        run = Path(pointer['run'])
        cc.check(sha(run / 'manifest.json') == pointer['manifestHash'], 'Changed matrix archive manifest')
        cc.check(sha(run / 'original-config.json') == entry['configuration']['sha256'], 'Archive differs from registered configuration')
        cc.check(read_json(run / 'manifest.json')['toolkit'] == registration['toolkit'], 'Archived toolkit differs from matrix registration')
        rows.append({'id': entry['id'], 'analysis': cr.analyze(run), 'reason': None})
    cc.unique([row['id'] for row in rows])
    report = {'version': 1, 'registrationHash': sha(directory / 'registration.json'), 'analyzerToolkit': cr.toolkit_hashes(),
              'campaigns': rows, 'promotionAuthorized': False}
    write_new(directory / 'analyses' / (stamp() + '-' + uuid.uuid4().hex[:8] + '.json'), report)
    return report
