"""Read-only final audit gates. A decision never changes deployment or solver defaults."""
from decimal import Decimal, InvalidOperation, localcontext, MAX_EMAX, MIN_EMIN
from pathlib import Path

import campaign_config as cc
from experiment_config import read_json
from experiment_runtime import sha


def decimal(value):
    cc.check(type(value) in (str, int), 'Exact decimal string or integer required')
    try:
        result = Decimal(value)
    except InvalidOperation as error:
        raise ValueError('Invalid decimal') from error
    cc.check(result.is_finite(), 'Finite decimal required')
    return result


def artifact(value, root):
    cc.artifact(value)
    path = Path(value['path'])
    if not path.is_absolute():
        path = root / path
    cc.check(path.is_file() and sha(path) == value['sha256'], 'Changed or missing promotion evidence')
    return read_json(path)


def workload(value):
    cc.fields(value, ['requested', 'completed', 'validAccepted', 'invalidAccepted', 'failures',
                      'timeouts', 'incomplete', 'overtimeIncreases', 'p50Ms', 'p95Ms', 'cleanupP95Ms'])
    for name in ('requested', 'completed', 'validAccepted', 'invalidAccepted', 'failures',
                 'timeouts', 'incomplete', 'overtimeIncreases'):
        cc.integer(value[name], 0)
    cc.check(value['requested'] > 0 and value['completed'] <= value['requested'], 'Invalid workload denominator')
    cc.check(value['validAccepted'] + value['invalidAccepted'] <= value['completed'], 'Invalid accepted count')
    cc.check(all(value[key] <= value['completed'] for key in ('failures', 'timeouts', 'incomplete', 'overtimeIncreases')),
             'Outcome count exceeds completed denominator')
    for name in ('p50Ms', 'p95Ms', 'cleanupP95Ms'):
        cc.check(decimal(value[name]) >= 0, 'Negative latency')
    cc.check(decimal(value['p95Ms']) >= decimal(value['p50Ms']), 'Invalid latency quantiles')
    return value


def latency_gate(control, candidate):
    """Multiplication must not round a long decimal control onto the acceptance boundary."""
    first, second = decimal(control), decimal(candidate)
    with localcontext() as context:
        context.prec = max(28, len(first.as_tuple().digits) + 3)
        context.Emax, context.Emin = MAX_EMAX, MIN_EMIN
        return first > 0 and second <= first * Decimal('1.05')


def evaluate(receipt):
    """Fail closed on incomplete confirmation, family reversals, and unequal demand/resources."""
    cc.fields(receipt, ['version', 'kind', 'candidateConfigurationHash', 'frozenConfigurationHash',
                        'tuningDatasets', 'confirmationDatasets', 'productionBudgetMs', 'searchBudgetMs',
                        'warmupDisposition', 'concurrencyDisposition', 'origin', 'costUpper95Cents',
                        'families', 'control', 'candidate', 'matchedLoadHash', 'candidateLoadHash',
                        'matchedResourcesHash', 'candidateResourcesHash', 'servedControl', 'servedCandidate'])
    cc.check(receipt['version'] == 1 and type(receipt['version']) is int and receipt['kind'] == 'application-confirmation',
             'Unsupported confirmation receipt')
    for name in ('candidateConfigurationHash', 'frozenConfigurationHash', 'matchedLoadHash',
                 'candidateLoadHash', 'matchedResourcesHash', 'candidateResourcesHash'):
        cc.check(type(receipt[name]) is str and len(receipt[name]) == 64
                 and all(char in '0123456789abcdef' for char in receipt[name]), 'Invalid confirmation hash')
    for name in ('tuningDatasets', 'confirmationDatasets', 'servedControl', 'servedCandidate'):
        cc.check(type(receipt[name]) is list and bool(receipt[name]), 'Explicit nonempty identities required')
        cc.unique(receipt[name])
        for identity in receipt[name]:
            cc.text(identity)
    cc.choice(receipt['warmupDisposition'], 'stable', 'ambiguous')
    cc.choice(receipt['concurrencyDisposition'], 'stable', 'ambiguous')
    cc.choice(receipt['origin'], 'historical', 'synthetic')
    cc.integer(receipt['productionBudgetMs'])
    cc.integer(receipt['searchBudgetMs'])
    control, candidate = workload(receipt['control']), workload(receipt['candidate'])
    cc.check(type(receipt['families']) is list and bool(receipt['families']), 'Scenario families required')
    names = []
    family_pass = True
    for family in receipt['families']:
        cc.fields(family, ['id', 'costDifferenceCents', 'fairnessDifference', 'pairedDatasets'])
        cc.text(family['id']); names.append(family['id'])
        cc.integer(family['pairedDatasets'])
        family_pass &= decimal(family['costDifferenceCents']) <= 0 and decimal(family['fairnessDifference']) <= 0
    cc.unique(names)
    gates = {
        'frozenFinalist': receipt['candidateConfigurationHash'] == receipt['frozenConfigurationHash'],
        'untouchedConfirmation': not set(receipt['tuningDatasets']) & set(receipt['confirmationDatasets'])
                                and len(receipt['confirmationDatasets']) >= 2,
        'productionAllowances': receipt['productionBudgetMs'] == 20000 and receipt['searchBudgetMs'] == 15000,
        'calibrated': receipt['warmupDisposition'] == receipt['concurrencyDisposition'] == 'stable',
        'matchedLoad': receipt['matchedLoadHash'] == receipt['candidateLoadHash'],
        'matchedResources': receipt['matchedResourcesHash'] == receipt['candidateResourcesHash'],
        'sameServedDemand': set(receipt['servedControl']) == set(receipt['servedCandidate']),
        'completeDenominators': control['requested'] == control['completed'] == candidate['requested'] == candidate['completed'],
        'noInvalidAccepted': control['invalidAccepted'] == candidate['invalidAccepted'] == 0,
        'noAdditionalAdverseOutcomes': all(candidate[key] <= control[key] for key in ('failures', 'timeouts', 'incomplete')),
        'noOvertimeIncrease': candidate['overtimeIncreases'] == 0,
        'usefulOutcomes': candidate['validAccepted'] >= control['validAccepted'] > 0,
        'p95WithinFivePercent': latency_gate(control['p95Ms'], candidate['p95Ms']),
        'costUpperBoundBelowZero': decimal(receipt['costUpper95Cents']) < 0,
        'noFamilyRegression': family_pass,
        'historicalEvidence': receipt['origin'] == 'historical',
    }
    return {'version': 1, 'eligibleForOwnerReview': all(gates.values()), 'gates': gates,
            'failedGates': [key for key, passed in gates.items() if not passed],
            'deploymentAuthorized': False,
            'limitation': 'Receipt evaluation checks declared measurements, not their experimental provenance; owner must review pinned source archives.'}


def review(path):
    """Pin the supplied receipt; never infer evidence from a missing file or execute a campaign."""
    path = path.resolve()
    spec = read_json(path)
    cc.fields(spec, ['version', 'confirmation'])
    cc.check(type(spec['version']) is int and spec['version'] == 1, 'Unsupported review version')
    result = evaluate(artifact(spec['confirmation'], path.parent))
    result['confirmation'] = spec['confirmation']
    result['reviewSpecificationHash'] = sha(path)
    return result
