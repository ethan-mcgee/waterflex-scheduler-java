"""Strict experiment input and reproducible, counterbalanced case expansion."""
import hashlib
import heapq
import itertools
import json
import re

WORKLOADS = ['SPARSE', 'CLUSTERED', 'DISPERSED', 'MIXED_SKILL', 'TIGHT_WINDOW', 'ABSENCE', 'NEAR_CAPACITY']
DAILY = ['CURRENT_CAPPED', 'CURRENT_UNCAPPED', 'LATE_ACCEPTANCE_CHANGE', 'LATE_ACCEPTANCE', 'TABU', 'SUBLIST', 'KOPT', 'RUIN_RECREATE']


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'Duplicate JSON field: {key}')
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f'Invalid JSON: {value}')))


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(required) - set(optional) or set(required) - set(value):
        raise ValueError(f'Expected fields {required}; optional {optional}')


def selection(section, name, predicate):
    values = section[name]
    if not isinstance(values, list) or not values or any(not predicate(x) for x in values):
        raise ValueError(f'Invalid or empty {name}')
    if len(set(values)) != len(values):
        raise ValueError(f'Duplicate {name}')


def validate(config):
    fields(config, ['version', 'name', 'daily'])
    if type(config['version']) is not int or config['version'] != 1:
        raise ValueError('version must be 1')
    if not isinstance(config['name'], str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,59}', config['name']):
        raise ValueError('name must be a short lowercase slug')
    section = config['daily']
    fields(section, ['solvers', 'control', 'seeds', 'fleets', 'workloads', 'budgets_seconds', 'parallel_cases'])
    selection(section, 'solvers', lambda x: isinstance(x, str) and x in DAILY)
    if section['control'] not in section['solvers']:
        raise ValueError('control must be a selected solver')
    selection(section, 'seeds', lambda x: type(x) is int and 0 <= x <= 2147483647)
    selection(section, 'fleets', lambda x: type(x) is int and x in [5, 10, 20, 30, 50])
    selection(section, 'workloads', lambda x: isinstance(x, str) and x in WORKLOADS)
    selection(section, 'budgets_seconds', lambda x: type(x) in (int, float) and .1 <= x <= 240 and x * 1000 == int(x * 1000))
    # Concurrent fresh solver JVMs, one dedicated physical core each. The usable core
    # count is hardware evidence, so the upper bound is checked at run time.
    if type(section['parallel_cases']) is not int or section['parallel_cases'] < 1:
        raise ValueError('parallel_cases must be a positive integer')
    return config


def expand(config):
    validate(config)
    cases = []
    s = config['daily']
    budgets = s['budgets_seconds']
    # Independently rotate solvers and budgets, interleaving solvers at each budget.
    # This balances first/last solver exposure even with fewer blocks than treatments.
    # The saved order is authoritative; never shuffle again on resume.
    for block, (fleet, workload, seed) in enumerate(itertools.product(s['fleets'], s['workloads'], s['seeds'])):
        offset = block % len(budgets)
        ordered_budgets = budgets[offset:] + budgets[:offset]
        offset = block % len(s['solvers'])
        solvers = s['solvers'][offset:] + s['solvers'][:offset]
        for budget in ordered_budgets:
            for solver in solvers:
                row = dict(kind='daily', fleet=fleet, workload=workload, seed=seed, solver=solver, budget_ms=round(budget * 1000))
                row['id'] = digest(row)[:20]
                cases.append(row)
    return cases


CASE_OVERHEAD_SECONDS = 2.5  # Measured JVM start, warmup and fixture build beyond the solver budget.


def estimate_daily_wall_seconds(cases, parallel_cases):
    """Greedy slot simulation in dispatch order."""
    slots = [0.0] * parallel_cases
    heapq.heapify(slots)
    for case in cases:
        heapq.heappush(slots, heapq.heappop(slots) + case['budget_ms'] / 1000 + CASE_OVERHEAD_SECONDS)
    return max(slots)
