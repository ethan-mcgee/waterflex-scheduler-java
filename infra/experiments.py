"""One-command local experiments. Full matrices are never run by ordinary CI."""
import argparse
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True  # Archived entry points must not modify their own frozen tree.

from experiment_config import estimate_daily_wall_seconds, expand, read_json, validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    run = commands.add_parser('run')
    run.add_argument('config', type=Path)
    run.add_argument('--dry-run', action='store_true')
    for name in ('resume', 'analyze'):
        commands.add_parser(name).add_argument('run', type=Path)
    contention = commands.add_parser('compare-contention')
    contention.add_argument('sequential', type=Path)
    contention.add_argument('parallel', type=Path)
    imp = commands.add_parser('import-history')
    imp.add_argument('archive', type=Path)
    args = parser.parse_args()
    from experiment_runtime import ROOT, execute, new_run, measurement_lock, import_history
    from experiment_analysis import analyze
    if args.command == 'run':
        config = validate(read_json(args.config))
        cases = expand(config)
        if args.dry_run:
            seconds = sum(c.get('budget_ms', 0) for c in cases) / 1000
            parallel = config.get('daily', {}).get('parallel_cases')
            wall = estimate_daily_wall_seconds(cases, parallel) if parallel else None
            print(json.dumps({'cases': cases, 'case_count': len(cases), 'daily_search_seconds': seconds,
                'daily_search_hours': seconds / 3600, 'parallel_cases': parallel,
                'estimated_daily_wall_seconds': wall, 'estimated_daily_wall_time': None if wall is None else
                    f'{int(wall // 3600)}h{int(wall // 60 % 60):02d}m',
                'prerequisites': ['Python 3.10+ and requirements-experiments.txt', 'Java 25 and Maven wrapper',
                    'Committed experiment sources; free disk for frozen artifacts',
                    'Booking: npm ci + Prisma generate, DATABASE_URL for local waterflex_test, ROUTING_URL for ready road graph'],
                'measurement': 'Fresh process per case; up to parallel_cases daily cases at once on dedicated physical cores; booking is sequential; per-case warmup; booking concurrency is within a case'}, indent=2))
            return
        with measurement_lock():
            run = new_run(ROOT / 'experiments/runs', config, args.config.read_bytes())
            print(f'Run: {run}', flush=True)
            execute(run, prepare=True)
        print('Generating graphs and summaries...', flush=True)
        print(analyze(run))
    elif args.command == 'resume':
        with measurement_lock():
            execute(args.run.resolve())
        print('Generating graphs and summaries...', flush=True)
        print(analyze(args.run.resolve()))
    elif args.command == 'compare-contention':
        from experiment_analysis import contention_report
        from experiment_runtime import stamp, write_new
        import uuid
        report = contention_report(args.sequential.resolve(), args.parallel.resolve())
        destination = args.parallel.resolve() / 'contention' / f'{stamp()}-{uuid.uuid4().hex[:8]}.json'
        write_new(destination, report)
        print(json.dumps(report, indent=2))
        print(f'Saved {destination}')
    elif args.command == 'import-history':
        print(analyze(import_history(args.archive.resolve())))
    else:
        print(analyze(args.run.resolve()))


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Interrupted. Evidence retained; resume the printed run directory.', file=sys.stderr)
        sys.exit(130)
    except (ValueError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
