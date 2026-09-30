"""Append-only evidence, isolated child processes, and frozen executable artifacts."""
from contextlib import contextmanager
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit, urlunsplit, urlencode, unquote
from urllib.request import urlopen
import uuid

from experiment_config import digest, expand, read_json, validate

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATHS = ['pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', '.settings', 'scheduler-service', 'routing-service', 'web', 'infra', 'experiments/configs']


class Progress:
    """Terminal progress for preparation and sequential measured cases."""

    def __init__(self, total, stream=None, clock=None):
        self.total = total
        self.stream = stream if stream is not None else sys.stderr
        self.clock = clock if clock is not None else time.monotonic
        self.started = self.clock()
        self.completed = 0
        self.stage = 'Preparing run'
        self.case_started = None
        self.interactive = self.stream.isatty()
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.ticker = None
        self.width = 0

    @staticmethod
    def duration(seconds):
        elapsed = max(0, int(seconds))
        return f'{elapsed // 3600:02d}:{elapsed // 60 % 60:02d}:{elapsed % 60:02d}'

    def line(self):
        elapsed = self.duration(self.clock() - self.started)
        filled = round(12 * self.completed / self.total) if self.total else 12
        bar = '#' * filled + '.' * (12 - filled)
        case_time = (f' case {self.duration(self.clock() - self.case_started)}'
                     if self.case_started is not None else '')
        prefix = f'[{bar}] {self.completed}/{self.total} elapsed {elapsed}{case_time} | '
        stage = self.stage
        if self.interactive:
            available = max(0, shutil.get_terminal_size(fallback=(120, 20)).columns - len(prefix) - 1)
            if len(stage) > available:
                stage = stage[:available - 3] + '...' if available >= 3 else stage[:available]
        return prefix + stage

    def emit(self, force=False):
        if not self.interactive and not force:
            return
        with self.lock:
            line = self.line()
            if self.interactive:
                self.stream.write('\r' + line.ljust(self.width))
                self.width = len(line)
            else:
                self.stream.write(line + '\n')
            self.stream.flush()

    def _tick(self):
        while not self.stop.wait(1):
            self.emit()

    def __enter__(self):
        self.emit(force=True)
        if self.interactive:
            self.ticker = threading.Thread(target=self._tick, daemon=True)
            self.ticker.start()
        return self

    def update(self, stage, *, completed=None, active=False):
        with self.lock:
            self.stage = stage
            if completed is not None:
                self.completed = completed
            self.case_started = self.clock() if active else None
        self.emit(force=True)

    def __exit__(self, error_type, _error, _traceback):
        self.stop.set()
        if self.ticker is not None:
            self.ticker.join()
        self.update('Measurements complete' if error_type is None else 'Stopped')
        if self.interactive:
            self.stream.write('\n')
            self.stream.flush()


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.pending-' + uuid.uuid4().hex)
    try:
        with temporary.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(value, stream, indent=2, allow_nan=False)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        # Atomic publication with create-new semantics, including on NTFS.
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def stamp():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')


def new_run(base, config, original):
    validate(config)
    run = base / f'{stamp()}-{config["name"]}-{uuid.uuid4().hex[:8]}'
    run.mkdir(parents=True, exist_ok=False)
    (run / 'original-config.json').write_bytes(original)
    write_new(run / 'config.json', config)
    write_new(run / 'cases.json', expand(config))
    return run


@contextmanager
def measurement_lock(port=47983):
    # Kernel-owned, machine-wide even across checkouts. No stale lockfile deletion.
    with socket.socket() as lock:
        if os.name == 'nt':
            lock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            lock.bind(('127.0.0.1', port))
            lock.listen(1)
        except OSError as error:
            raise RuntimeError(f'Another toolkit measurement run holds port {port} on this PC') from error
        yield


def child_job(process):
    """Windows kills this owned process tree if the orchestrator disappears."""
    if os.name != 'nt':
        return None
    import ctypes
    from ctypes import wintypes as w
    class Basic(ctypes.Structure):
        _fields_ = [('process_time', ctypes.c_int64), ('job_time', ctypes.c_int64), ('flags', w.DWORD),
                    ('min_working', ctypes.c_size_t), ('max_working', ctypes.c_size_t), ('active', w.DWORD),
                    ('affinity', ctypes.c_size_t), ('priority', w.DWORD), ('scheduling', w.DWORD)]
    class IO(ctypes.Structure):
        _fields_ = [(name, ctypes.c_uint64) for name in ('read_ops', 'write_ops', 'other_ops', 'read_bytes', 'write_bytes', 'other_bytes')]
    class Extended(ctypes.Structure):
        _fields_ = [('basic', Basic), ('io', IO), ('process_memory', ctypes.c_size_t), ('job_memory', ctypes.c_size_t),
                    ('peak_process', ctypes.c_size_t), ('peak_job', ctypes.c_size_t)]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
    kernel.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
    kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.CreateJobObjectW(None, None)
    info = Extended()
    info.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not handle or not kernel.SetInformationJobObject(handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
        if handle:
            kernel.CloseHandle(handle)
        raise OSError('Cannot establish owned child job')
    if not kernel.AssignProcessToJobObject(handle, int(process._handle)):
        kernel.CloseHandle(handle)
        raise OSError('Cannot attach owned process to child job')
    return lambda: kernel.CloseHandle(handle)


@contextmanager
def launch(command, cwd, env, log):
    with log.open('xb') as output:
        process = subprocess.Popen([str(x) for x in command], cwd=cwd, env=env, stdout=output, stderr=output,
            stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
            start_new_session=os.name != 'nt')
        close_job = None
        try:
            close_job = child_job(process)
            yield process
        finally:
            if close_job:
                close_job()
            elif os.name == 'nt':
                # Assignment failure must also dispose of descendants already started by wrappers.
                if process.poll() is None:
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            else:
                import signal
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            finally:
                if os.name != 'nt':
                    # The group can outlive the direct child, including after successful exit.
                    import signal
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass



def command(args, cwd, env, log, timeout=1200):
    with launch(args, cwd, env, log) as process:
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f'Process timed out; inspect {log}') from error
        if code:
            raise RuntimeError(f'Process exited {code}; inspect {log}')


def environment():
    # Do not inherit ad hoc benchmark controls or JVM/Spring overrides.
    blocked = ('BENCHMARK_', 'FIELD_', 'SPRING_', 'BOOKING_', 'SCHEDULER_', 'TIME_OFF_', 'ROUTING_PREWARM_', 'ROUTING_CACHE_')
    return {k: v for k, v in os.environ.items() if not k.upper().startswith(blocked) and k.upper() not in
            ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'NODE_OPTIONS', 'CLASSPATH']}


def capture(args, cwd=ROOT):
    return subprocess.check_output([str(a) for a in args], cwd=cwd, env=environment(), text=True, stderr=subprocess.STDOUT).strip()


def runtime():
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or '')
    if not java.is_file():
        raise ValueError('Set JAVA_HOME to Java 25')
    hardware = json.loads(capture(['node', '-e', 'const o=require("node:os");console.log(JSON.stringify({cpuModel:o.cpus()[0].model,memoryBytes:o.totalmem()}))']))
    return {'java': str(java.resolve()), 'java_version': capture([java, '-version']), 'java_sha256': sha(java),
            'python': sys.version, 'node': shutil.which('node'), 'node_version': capture(['node', '--version']),
            'platform': platform.platform(), 'machine': platform.machine(), 'hardware': hardware, 'logical_cpus': os.cpu_count()}


def tree_hashes(root):
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def verify_files(root, hashes):
    if tree_hashes(root) != hashes:
        raise ValueError(f'Frozen artifact provenance mismatch: {root}')


def prepare_run(run, config):
    import matplotlib  # Fail prerequisites before starting any measurement.
    info = runtime()
    if 'booking' in config:
        database_env(environment(), 'benchmark_preflight')
        routing_identity(environment())
    source_names = capture(['git', 'ls-files', '--', *SOURCE_PATHS]).splitlines()
    if capture(['git', 'status', '--porcelain', '--', *SOURCE_PATHS]):
        raise ValueError('Commit experiment source changes before measurement (AGENTS.md and docs are not harness inputs)')
    revision = capture(['git', 'rev-parse', 'HEAD'])
    artifacts = run / 'frozen'
    source = artifacts / 'source'
    source.mkdir(parents=True)
    for name in source_names:
        destination = source / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, destination)
    if capture(['git', 'rev-parse', 'HEAD']) != revision or capture(['git', 'status', '--porcelain', '--', *SOURCE_PATHS]):
        raise ValueError('Source changed while freezing; start a new run')
    env = environment()
    wrapper = source / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
    command([wrapper, '-B', '-pl', 'scheduler-service', '-Pnullability', '-DskipTests', 'package',
             'dependency:copy-dependencies', '-DincludeScope=test'], source, env, run / 'build.log')
    target = source / 'scheduler-service/target'
    for name in ('classes', 'test-classes', 'dependency'):
        shutil.copytree(target / name, artifacts / name)
    shutil.copy2(target / 'scheduler-service-0.1.0-SNAPSHOT.jar', artifacts / 'scheduler.jar')
    # Build products stay in the build log and canonical executable copies, not duplicated source.
    resolved_target = target.resolve()
    if not resolved_target.is_relative_to(run.resolve()):
        raise ValueError('Build output escaped run archive')
    shutil.rmtree(resolved_target)
    booking = None
    if 'booking' in config:
        if not (ROOT / 'web/node_modules/.prisma/client').is_dir():
            raise ValueError('Run npm ci and npm run prisma:generate in web first')
        print('Freezing booking Node dependencies...', flush=True)
        shutil.copytree(ROOT / 'web/node_modules', source / 'web/node_modules')
        web_sources = {name[4:]: sha(source / name) for name in source_names if name.startswith('web/')}
        write_new(artifacts / 'web-manifest.json', {'revision': revision, 'sources': web_sources})
        booking = booking_identity(artifacts, env)
    hashes = tree_hashes(artifacts)
    write_new(run / 'manifest.json', {'format': 1, 'revision': revision, 'config_hash': digest(config),
        'cases_hash': digest(read_json(run / 'cases.json')), 'files': hashes, 'runtime': info,
        'matplotlib': matplotlib.__version__, 'booking': booking,
        'source_hashes': {name: sha(source / name) for name in source_names},
        'warmup': 'Daily: 200 ms SPARSE/20 for the selected solver in each fresh JVM; booking: fresh JVM, migration, health, independent audit and specified cache preparation',
        'toolkit_hashes': {name: sha(source / 'infra' / name) for name in toolkit_hashes()},
        'order': 'Independent cyclic rotations of solvers and budget/concurrency/cache settings across fixture/seed blocks',
        'limitations': 'Local shared workstation; toolkit lock excludes other toolkit runs, not unrelated PC activity'})


def database_env(env, schema):
    raw = env.get('DATABASE_URL')
    if not raw:
        raise ValueError('DATABASE_URL must identify local waterflex_test; credentials are never archived')
    db = urlsplit(raw)
    if db.scheme not in ('postgres', 'postgresql') or db.path != '/waterflex_test' or db.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('Booking requires a local waterflex_test database')
    result = dict(env)
    result['DATABASE_URL'] = urlunsplit((db.scheme, db.netloc, db.path, urlencode({'schema': schema, 'connection_limit': 4}), ''))
    host = f'[{db.hostname}]' if ':' in db.hostname else db.hostname
    result.update(JDBC_DATABASE_URL=f'jdbc:postgresql://{host}:{db.port or 5432}/waterflex_test?currentSchema={schema}',
                  DATABASE_USER=unquote(db.username or ''), DATABASE_PASSWORD=unquote(db.password or ''))
    if not result['DATABASE_USER']:
        raise ValueError('DATABASE_URL requires an explicit user')
    return result


def routing_identity(env):
    url = env.get('ROUTING_URL')
    if not url:
        raise ValueError('ROUTING_URL is required for booking road evidence')
    parsed = urlsplit(url)
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('ROUTING_URL must be an HTTP provider URL without credentials/query')
    with urlopen(url.rstrip('/') + '/health', timeout=5) as response:
        health = json.load(response)
    if health.get('ready') is not True or not isinstance(health.get('routingIdentity'), str) or not health['routingIdentity']:
        raise ValueError('Road provider must be ready with a routingIdentity')
    return {'url': url, 'identity': health['routingIdentity']}


def horizon(artifacts, reference):
    web = artifacts / 'source/web'
    value = capture(['node', web / 'node_modules/tsx/dist/cli.mjs', '-e',
        "import {bookingHorizon} from './lib/bookingTestCore'; console.log(JSON.stringify(bookingHorizon(new Date(process.argv[1]))))", reference], web)
    dates = json.loads(value)
    if not isinstance(dates, list) or len(dates) != 10 or any(not isinstance(d, str) for d in dates):
        raise ValueError('Invalid booking horizon')
    return dates


def booking_identity(artifacts, env, reference=None):
    database_env(env, 'benchmark_preflight')
    reference = reference or datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    return {'calendar_reference': reference, 'dates': horizon(artifacts, reference), 'routing': routing_identity(env), 'transport': 'direct-durable-client'}


def daily_command(artifacts, case, revision, output, java):
    cp = os.pathsep.join(str(artifacts / p) for p in ('test-classes', 'classes', 'dependency/*'))
    return [java, '-Xmx1536m', f'-Dbenchmark.revision={revision}', f'-Dbenchmark.output={output}',
        f'-Dbenchmark.durationMs={case["budget_ms"]}', f'-Dbenchmark.sizes={case["fleet"]}',
        f'-Dbenchmark.workloads={case["workload"]}', f'-Dbenchmark.variants={case["solver"]}',
        f'-Dbenchmark.seeds={case["seed"]}', '-cp', cp, 'dev.waterflex.scheduler.optimizer.SolverBenchmark']


def booking_case(run, attempt, case, manifest, env):
    artifacts = run / 'frozen'
    web = artifacts / 'source/web'
    saved = manifest['booking']
    if booking_identity(artifacts, env, saved['calendar_reference']) != saved:
        raise ValueError('Booking horizon or routing identity changed; start a new run')
    schema = 'benchmark_exp_' + uuid.uuid4().hex
    env = database_env(env, schema)
    # Reserve an unused local port, then verify only our child reaches startup.
    with socket.socket() as candidate:
        candidate.bind(('127.0.0.1', 0))
        port = candidate.getsockname()[1]
    server_log = attempt / 'server.log'
    env.update(ENGINE_URL=f'http://127.0.0.1:{port}', BENCHMARK_REVISION=manifest['revision'],
        BENCHMARK_VARIANT=case['solver'], BENCHMARK_EXPECT_VARIANT=case['solver'],
        BENCHMARK_ARTIFACT_SHA256=sha(artifacts / 'scheduler.jar'), BENCHMARK_OUTPUT=str(attempt / 'raw.jsonl'),
        BENCHMARK_LOG_PATH=str(server_log), BENCHMARK_SIZES=str(case['fleet']), BENCHMARK_WORKLOADS=case['workload'],
        BENCHMARK_SEED=str(case['seed']), BENCHMARK_CONCURRENCY=str(case['concurrency']), BENCHMARK_CACHES=case['cache'],
        BENCHMARK_CALENDAR_REFERENCE=saved['calendar_reference'], BENCHMARK_REQUESTS=str(case['requests']), BENCHMARK_DURABLE='true', BENCHMARK_DATES=json.dumps(saved['dates']),
        BENCHMARK_ROUTING_IDENTITY=saved['routing']['identity'], BENCHMARK_FROZEN_MANIFEST=str(artifacts / 'web-manifest.json'))
    write_new(attempt / 'booking.json', {'schema': schema, 'port': port, 'dates': saved['dates'], 'calendar_reference': saved['calendar_reference'], 'routing': saved['routing']})
    command(['node', web / 'node_modules/prisma/build/index.js', 'migrate', 'deploy'], web, env, attempt / 'migrate.log')
    args = [manifest['runtime']['java'], '-Xmx1536m', '-jar', artifacts / 'scheduler.jar',
        f'--server.port={port}', '--server.address=127.0.0.1', '--spring.profiles.active=benchmark',
        '--spring.datasource.hikari.maximum-pool-size=4', '--spring.datasource.hikari.minimum-idle=1',
        f'--benchmark.calendar-reference={saved["calendar_reference"]}',
        '--scheduler.optimizer.cron=-', '--routing.cache.cleanup-cron=-', '--routing.prewarm.enabled=false',
        '--time-off.analysis.enabled=false', '--booking.reservations.enabled=true', '--booking.search.bounded=true', f'--booking.search.variant={case["solver"]}']
    with launch(args, artifacts, env, server_log) as server:
        for _ in range(90):
            if server.poll() is not None:
                raise RuntimeError(f'Scheduler exited; inspect {server_log}')
            try:
                with urlopen(env['ENGINE_URL'] + '/health', timeout=1) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError(f'Scheduler did not become ready; inspect {server_log}')
        command(['node', web / 'node_modules/tsx/dist/cli.mjs', 'scripts/benchmark-scheduling.ts'], web, env,
                attempt / 'client.log', timeout=300 + case['requests'] * 150)


def completed_attempt(run, case):
    completed = []
    for attempt in sorted((run / 'attempts' / case['id']).glob('*')):
        receipt = attempt / 'completed.json'
        if receipt.exists():
            saved = read_json(receipt)
            if saved['case'] != case or saved['raw_sha256'] != sha(attempt / 'raw.jsonl'):
                raise ValueError(f'Completed evidence changed: {attempt}')
            completed.append(attempt)
    if len(completed) > 1:
        raise ValueError(f'Duplicate completed case receipts: {case["id"]}')
    return completed[0] if completed else None


def toolkit_hashes():
    return {name: sha(ROOT / 'infra' / name) for name in ('experiments.py', 'experiment_runtime.py', 'experiment_config.py')}


def execute(run, prepare=False):
    run = run.resolve()
    config = validate(read_json(run / 'config.json'))
    with Progress(len(expand(config))) as progress:
        if prepare:
            progress.update('Building frozen source and dependencies')
            prepare_run(run, config)
        if not (run / 'manifest.json').exists():
            raise ValueError('Preparation did not finish. Start a new run; retained build evidence is not resumable.')
        progress.update('Checking saved evidence and runtime')
        manifest = read_json(run / 'manifest.json')
        if manifest.get('toolkit_hashes') != toolkit_hashes():
            raise ValueError('Toolkit implementation changed; resume using the archived source/infra/experiments.py command')
        cases = read_json(run / 'cases.json')
        if manifest['config_hash'] != digest(config) or manifest['cases_hash'] != digest(cases) or cases != expand(config):
            raise ValueError('Saved configuration or matrix provenance mismatch')
        verify_files(run / 'frozen', manifest['files'])
        if runtime() != manifest['runtime']:
            raise ValueError('Runtime/hardware provenance mismatch; start a new run')
        env = environment()
        if 'booking' in config and booking_identity(run / 'frozen', env, manifest['booking']['calendar_reference']) != manifest['booking']:
            raise ValueError('Frozen booking dates/routing no longer valid; start a new run')
        completed = {case['id']: completed_attempt(run, case) is not None for case in cases}
        progress.update('Checking completed cases', completed=sum(completed.values()))
        from experiment_analysis import load_raw
        for index, case in enumerate(cases):
            if completed[case['id']]:
                continue
            folder = run / 'attempts' / case['id']
            for old in sorted(folder.glob('*')):
                if not (old / 'completed.json').exists() and not (old / 'failed.json').exists() and not (old / 'interrupted.json').exists():
                    write_new(old / 'interrupted.json', {'reason': 'Previous orchestrator stopped without a completion receipt'})
            attempt = folder / (stamp() + '-' + uuid.uuid4().hex[:8])
            attempt.mkdir(parents=True)
            write_new(attempt / 'started.json', {'case': case, 'at': stamp()})
            detail = (f'{case["budget_ms"] / 1000:g}s' if case['kind'] == 'daily' else
                      f'c{case["concurrency"]} {case["cache"]} {case["requests"]}req')
            progress.update(f'#{index + 1} {case["solver"]} f{case["fleet"]} '
                            f'{case["workload"]} s{case["seed"]} {detail}', active=True)
            try:
                if case['kind'] == 'daily':
                    command(daily_command(run / 'frozen', case, manifest['revision'], attempt / 'raw.jsonl', manifest['runtime']['java']),
                            run / 'frozen', env, attempt / 'solver.log', timeout=case['budget_ms'] / 1000 + 120)
                else:
                    booking_case(run, attempt, case, manifest, env)
                rows, issues = load_raw(attempt / 'raw.jsonl', expected=case)
                if len(rows) != 1 or issues:
                    raise ValueError(f'Raw output is incomplete or invalid: {issues}')
                write_new(attempt / 'completed.json', {'case': case, 'raw_sha256': sha(attempt / 'raw.jsonl'), 'at': stamp()})
                progress.update(f'Finished case {index + 1}/{len(cases)}', completed=progress.completed + 1)
            except BaseException as error:
                # Persist only controlled error types, never subprocess environment/credentials.
                write_new(attempt / ('interrupted.json' if isinstance(error, KeyboardInterrupt) else 'failed.json'),
                          {'exception': type(error).__name__, 'at': stamp(), 'instruction': 'Inspect this attempt logs; resume retries in a new directory'})
                raise


def import_history(archive):
    manifest_path = archive / 'experiment-manifest.json'
    manifest = read_json(manifest_path)
    if 'dailyFrozenRevision' not in manifest or 'bookingArtifactSha256' not in manifest:
        raise ValueError('Expected September 29 experiment manifest')
    run = ROOT / 'experiments/runs' / f'{stamp()}-history-{uuid.uuid4().hex[:8]}'
    (run / 'history').mkdir(parents=True)
    paths = [manifest_path, *sorted(archive.glob('*.jsonl.gz'))]
    receipts = []
    for path in paths:
        shutil.copy2(path, run / 'history' / path.name)
        receipts.append({'name': path.name, 'sha256': sha(path)})
    write_new(run / 'import.json', {'source': str(archive), 'files': receipts,
        'warning': 'Historical stages are separate cohorts. Failed and duplicate observations are listed, not repaired. Original report is unchanged.'})
    return run
