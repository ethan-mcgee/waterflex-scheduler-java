"""Set up and launch a daily solver experiment from one command.

Run without arguments for a short terminal menu, or pass EXPERIMENT MODE.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parent
INFRA = ROOT / 'infra'
sys.path.insert(0, str(INFRA))

from experiment_config import expand, read_json, validate  # noqa: E402
from experiment_runtime import Progress  # noqa: E402


DEFAULTS = {
    'daily': {'smoke': 'daily-smoke.json', 'dry-run': 'daily-budget.json', 'full': 'daily-budget.json'},
}


def choice(label, values):
    print(f'{label}: {", ".join(values)}')
    while True:
        answer = input('> ').strip().lower()
        if answer in values:
            return answer
        print(f'Choose one of: {", ".join(values)}')


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('experiment', nargs='?', choices=DEFAULTS)
    parser.add_argument('mode', nargs='?', choices=('smoke', 'dry-run', 'full'))
    parser.add_argument('--config', type=Path, help='Use your own validated JSON configuration')
    args = parser.parse_args(argv)
    if args.experiment is None and args.mode is None and sys.stdin.isatty():
        args.experiment = choice('Experiment', tuple(DEFAULTS))
        args.mode = choice('Mode', ('smoke', 'dry-run', 'full'))
    elif args.experiment is None or args.mode is None:
        parser.error('provide EXPERIMENT and MODE, or run interactively without arguments')
    return args


def check_tools():
    if sys.version_info < (3, 10):
        raise RuntimeError('Python 3.10 or later is required')
    java = (Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            if os.environ.get('JAVA_HOME') else shutil.which('java'))
    if java is None or not Path(java).is_file():
        raise RuntimeError('Java 25 is required; set JAVA_HOME to its installation directory')
    result = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True)
    version = result.stderr or result.stdout
    if not (version.startswith('openjdk version "25') or version.startswith('java version "25')):
        raise RuntimeError(f'Java 25 is required; selected Java reports: {version.splitlines()[0] if version else "unknown"}')
    if not os.environ.get('JAVA_HOME'):
        settings = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                                  capture_output=True, text=True, check=True)
        home = re.search(r'^\s*java\.home\s*=\s*(.+?)\s*$', settings.stderr or settings.stdout, re.MULTILINE)
        if home is None:
            raise RuntimeError('Could not identify Java 25 home; set JAVA_HOME to its JDK directory')
        os.environ['JAVA_HOME'] = home.group(1)
    javac = Path(os.environ['JAVA_HOME']) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    if not javac.is_file():
        raise RuntimeError('JAVA_HOME must point to a Java 25 JDK with javac')


def setup(config):
    check_tools()
    try:
        import matplotlib
        match = re.match(r'^(\d+)\.(\d+)', matplotlib.__version__)
        installed = match is not None and (3, 9) <= tuple(map(int, match.groups())) < (4, 0)
    except ImportError:
        installed = False
    if not installed:
        print('Installing Python graph dependencies...', flush=True)
        subprocess.run([sys.executable, '-m', 'pip', 'install', '-r', str(INFRA / 'requirements-experiments.txt')],
                       cwd=ROOT, check=True)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    path = (args.config if args.config is not None else
            ROOT / 'experiments/configs' / DEFAULTS[args.experiment][args.mode]).resolve()
    config = validate(read_json(path))
    cases = expand(config)
    print(f'{args.experiment} {args.mode}: {len(cases)} cases from {path}', flush=True)
    started = time.monotonic()
    if args.mode != 'dry-run':
        setup(config)
    command = [sys.executable, str(INFRA / 'experiments.py'), 'run', str(path)]
    if args.mode == 'dry-run':
        command.append('--dry-run')
    result = subprocess.run(command, cwd=ROOT, check=False)
    print(f'Total elapsed: {Progress.duration(time.monotonic() - started)}', flush=True)
    return result.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
