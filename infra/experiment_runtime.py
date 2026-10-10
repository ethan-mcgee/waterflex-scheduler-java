"""Append-only evidence, isolated child processes, and frozen executable artifacts."""
from contextlib import contextmanager
from datetime import datetime, timezone
import gzip
import collections
import concurrent.futures
import hashlib
import heapq
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
import uuid

from experiment_config import digest, expand, read_json, validate

ROOT = Path(__file__).resolve().parents[1]
# Every reactor module listed in pom.xml must be present for Maven to load the frozen tree; the solver itself
# lives in calculation-engine, so it is frozen and built from source rather than resolved from ~/.m2.
SOURCE_PATHS = ['pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', '.settings', 'calculation-engine', 'solver-service', 'scheduler-service',
                'routing-service', 'infra', 'experiments/configs']


class Progress:
    """Terminal progress for preparation and measured cases, including several concurrent daily cases."""

    def __init__(self, total, stream=None, clock=None):
        self.total = total
        self.stream = stream if stream is not None else sys.stderr
        self.clock = clock if clock is not None else time.monotonic
        self.started = self.clock()
        self.completed = 0
        self.stage = 'Preparing run'
        self.case_started = None
        self.running = {}
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
        if self.running:
            case_time = f' running {len(self.running)} oldest {self.duration(self.clock() - min(self.running.values()))}'
        else:
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

    def start_case(self, key, stage):
        with self.lock:
            self.stage = stage
            self.running[key] = self.clock()
        self.emit(force=True)

    def finish_case(self, key, stage):
        # Increment under the lock: concurrent cases finish on different threads.
        with self.lock:
            self.stage = stage
            self.running.pop(key, None)
            self.completed += 1
        self.emit(force=True)

    def abandon_case(self, key):
        with self.lock:
            self.running.pop(key, None)

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


def child_job(process, affinity=None):
    """Windows kills this owned process tree if the orchestrator disappears; optionally confines it to affinity."""
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
    if affinity is not None:
        info.basic.flags |= 0x10  # JOB_OBJECT_LIMIT_AFFINITY
        info.basic.affinity = affinity
    if not handle or not kernel.SetInformationJobObject(handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
        if handle:
            kernel.CloseHandle(handle)
        raise OSError('Cannot establish owned child job')
    if not kernel.AssignProcessToJobObject(handle, int(process._handle)):
        kernel.CloseHandle(handle)
        raise OSError('Cannot attach owned process to child job')
    return lambda: kernel.CloseHandle(handle)


@contextmanager
def launch(command, cwd, env, log, affinity=None):
    with log.open('xb') as output:
        process = subprocess.Popen([str(x) for x in command], cwd=cwd, env=env, stdout=output, stderr=output,
            stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
            start_new_session=os.name != 'nt')
        close_job = None
        try:
            close_job = child_job(process, affinity)
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



class StopRequested(Exception):
    """The orchestrator was interrupted; an in-flight case is abandoned and its process tree closed."""


def command(args, cwd, env, log, timeout=1200, affinity=None, stop=None):
    with launch(args, cwd, env, log, affinity) as process:
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f'Process timed out; inspect {log}')
            try:
                code = process.wait(timeout=min(.5, remaining))
                break
            except subprocess.TimeoutExpired:
                if stop is not None and stop.is_set():
                    raise StopRequested('Orchestrator interrupted') from None
        if code:
            raise RuntimeError(f'Process exited {code}; inspect {log}')


def environment():
    # Do not inherit ad hoc benchmark controls or JVM/Spring overrides.
    blocked = ('BENCHMARK_', 'FIELD_', 'SPRING_', 'BOOKING_', 'SCHEDULER_', 'TIME_OFF_', 'ROUTING_PREWARM_', 'ROUTING_CACHE_')
    return {k: v for k, v in os.environ.items() if not k.upper().startswith(blocked) and k.upper() not in
            ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'NODE_OPTIONS', 'CLASSPATH']}


def capture(args, cwd=ROOT):
    return subprocess.check_output([str(a) for a in args], cwd=cwd, env=environment(), text=True, stderr=subprocess.STDOUT).strip()


def _group_masks(buffer, relation):
    """Group 0 affinity masks for one relationship type from GetLogicalProcessorInformationEx output."""
    import struct
    masks, offset = [], 0
    while offset < len(buffer):
        kind, size = struct.unpack_from('<II', buffer, offset)
        if size == 0:
            raise OSError('Malformed processor topology')
        if kind == relation:
            # Processor relation: GroupCount at +30, masks at +32. Cache relation: GroupCount at +38, masks at +40.
            count_at, first_at = (offset + 8 + 22, offset + 8 + 24) if relation == 0 else (offset + 8 + 30, offset + 8 + 32)
            groups = struct.unpack_from('<H', buffer, count_at)[0]
            if groups != 1:
                raise OSError('Processor groups above 64 logical CPUs are not supported')
            mask, group = struct.unpack_from('<QH', buffer, first_at)
            if group != 0:
                raise OSError('Only processor group 0 is supported')
            if relation == 2 and buffer[offset + 8] != 3:
                offset += size
                continue
            masks.append(mask)
        offset += size
    return masks


def topology():
    """Physical core affinity masks and L3 groupings (CCDs) on Windows; None elsewhere."""
    if os.name != 'nt':
        return None
    import ctypes
    from ctypes import wintypes as w
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetLogicalProcessorInformationEx.argtypes = [w.DWORD, ctypes.c_void_p, ctypes.POINTER(w.DWORD)]
    kernel.GetLogicalProcessorInformationEx.restype = w.BOOL
    size = w.DWORD(0)
    kernel.GetLogicalProcessorInformationEx(0xFFFF, None, ctypes.byref(size))  # RelationAll sizing call
    if not size.value:
        raise OSError('Cannot read processor topology')
    raw = ctypes.create_string_buffer(size.value)
    if not kernel.GetLogicalProcessorInformationEx(0xFFFF, raw, ctypes.byref(size)):
        raise OSError('Cannot read processor topology')
    data = raw.raw[:size.value]
    cores, l3 = sorted(_group_masks(data, 0)), sorted(set(_group_masks(data, 2)))
    if not cores or not l3:
        raise OSError('Processor topology lacks physical cores or L3 caches')
    return {'cores': cores, 'l3': l3}


def slot_masks(parallel_cases, saved):
    """One dedicated physical-core mask per concurrent case: core 0 stays free, slots alternate between L3 groups."""
    if saved is None:
        if parallel_cases > 1:
            raise ValueError('parallel_cases above 1 needs Windows physical-core affinity')
        return [None]
    usable = [mask for mask in saved['cores'] if not mask & 1]
    if parallel_cases > len(usable):
        raise ValueError(f'parallel_cases {parallel_cases} exceeds the {len(usable)} usable physical cores (core 0 is left free)')
    groups = [[mask for mask in usable if mask & cache == mask] for cache in saved['l3']]
    ordered = []
    for rank in range(max((len(g) for g in groups), default=0)):
        ordered.extend(g[rank] for g in groups if rank < len(g))
    if len(ordered) != len(usable):
        raise ValueError('Physical cores do not partition into L3 groups')
    return ordered[:parallel_cases]


def runtime():
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or '')
    if not java.is_file():
        raise ValueError('Set JAVA_HOME to Java 25')
    hardware = json.loads(capture(['node', '-e', 'const o=require("node:os");console.log(JSON.stringify({cpuModel:o.cpus()[0].model,memoryBytes:o.totalmem()}))']))
    return {'java': str(java.resolve()), 'java_version': capture([java, '-version']), 'java_sha256': sha(java),
            'python': sys.version, 'node': shutil.which('node'), 'node_version': capture(['node', '--version']),
            'platform': platform.platform(), 'machine': platform.machine(), 'hardware': hardware, 'logical_cpus': os.cpu_count(),
            'topology': topology()}


def tree_hashes(root):
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def verify_files(root, hashes):
    if tree_hashes(root) != hashes:
        raise ValueError(f'Frozen artifact provenance mismatch: {root}')


def prepare_run(run, config):
    import matplotlib  # Fail prerequisites before starting any measurement.
    info = runtime()
    count = config['daily']['parallel_cases']
    parallelism = {'parallel_cases': count, 'slot_masks': slot_masks(count, info['topology']),
                   'jvm_flags': [*DAILY_JVM_FLAGS, '-Xlog:gc:file=<attempt>/gc.log']}
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
    command([wrapper, '-B', '-pl', 'scheduler-service', '-am', '-Pnullability', '-DskipTests', 'package',
             'dependency:copy-dependencies', '-DincludeScope=test'], source, env, run / 'build.log')
    target = source / 'scheduler-service/target'
    for name in ('classes', 'test-classes', 'dependency'):
        shutil.copytree(target / name, artifacts / name)
    shutil.copy2(target / 'scheduler-service-0.1.0-SNAPSHOT.jar', artifacts / 'scheduler.jar')
    # Build products stay in the build log and canonical executable copies, not duplicated source.
    for module in ('scheduler-service', 'calculation-engine'):
        resolved_target = (source / module / 'target').resolve()
        if not resolved_target.is_relative_to(run.resolve()):
            raise ValueError('Build output escaped run archive')
        shutil.rmtree(resolved_target)
    hashes = tree_hashes(artifacts)
    write_new(run / 'manifest.json', {'format': 1, 'revision': revision, 'config_hash': digest(config),
        'cases_hash': digest(read_json(run / 'cases.json')), 'files': hashes, 'runtime': info,
        'matplotlib': matplotlib.__version__, 'parallelism': parallelism,
        'source_hashes': {name: sha(source / name) for name in source_names},
        'warmup': 'Daily: 200 ms SPARSE/20 for the selected solver in each fresh JVM',
        'toolkit_hashes': {name: sha(source / 'infra' / name) for name in toolkit_hashes()},
        'order': 'Independent cyclic rotations of solvers and budgets across fixture/seed blocks',
        'limitations': 'Local shared workstation; toolkit lock excludes other toolkit runs, not unrelated PC activity. '
                       'Concurrent daily cases run on dedicated physical cores but share memory bandwidth, L3 cache and boost clocks; '
                       'compare move evaluations per second across parallel_cases settings before trusting parallel timings'})


DAILY_JVM_FLAGS = ['-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC']


def daily_command(artifacts, case, revision, output, java):
    cp = os.pathsep.join(str(artifacts / p) for p in ('test-classes', 'classes', 'dependency/*'))
    # GC evidence lands beside the raw output, never inside the hashed frozen tree.
    return [java, '-Xmx1536m', *DAILY_JVM_FLAGS, f'-Xlog:gc:file={Path(output).with_name("gc.log")}',
        f'-Dbenchmark.revision={revision}', f'-Dbenchmark.output={output}',
        f'-Dbenchmark.durationMs={case["budget_ms"]}', f'-Dbenchmark.sizes={case["fleet"]}',
        f'-Dbenchmark.workloads={case["workload"]}', f'-Dbenchmark.variants={case["solver"]}',
        f'-Dbenchmark.seeds={case["seed"]}', '-cp', cp, 'dev.waterflex.scheduler.optimizer.SolverBenchmark']


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


class Occupancy:
    """Tracks concurrent cases so each attempt records the fewest neighbors it ever had."""

    def __init__(self):
        self.lock = threading.Lock()
        self.lowest = {}

    def start(self, key):
        with self.lock:
            others = len(self.lowest)
            self.lowest[key] = others
            return others

    def finish(self, key):
        with self.lock:
            low = self.lowest.pop(key, None)  # Idempotent: a failed receipt write finishes the case again.
            for other in self.lowest:
                self.lowest[other] = min(self.lowest[other], len(self.lowest) - 1)
            return low


def run_case(run, manifest, env, progress, occupancy, stop, number, total, case, slot, mask):
    folder = run / 'attempts' / case['id']
    attempt = folder / (stamp() + '-' + uuid.uuid4().hex[:8])
    attempt.mkdir(parents=True)
    neighbors = occupancy.start(case['id'])
    write_new(attempt / 'started.json', {'case': case, 'at': stamp(), 'slot': slot, 'mask': mask, 'in_flight_at_start': neighbors})
    progress.start_case(case['id'], f'#{number} {case["solver"]} f{case["fleet"]} '
                                     f'{case["workload"]} s{case["seed"]} {case["budget_ms"] / 1000:g}s')
    try:
        command(daily_command(run / 'frozen', case, manifest['revision'], attempt / 'raw.jsonl', manifest['runtime']['java']),
                run / 'frozen', env, attempt / 'solver.log', timeout=case['budget_ms'] / 1000 + 120,
                affinity=mask, stop=stop)
        from experiment_analysis import load_raw
        rows, issues = load_raw(attempt / 'raw.jsonl', expected=case)
        if len(rows) != 1 or issues:
            raise ValueError(f'Raw output is incomplete or invalid: {issues}')
        write_new(attempt / 'completed.json', {'case': case, 'raw_sha256': sha(attempt / 'raw.jsonl'), 'at': stamp(),
                                               'slot': slot, 'mask': mask, 'in_flight_min': occupancy.finish(case['id'])})
        progress.finish_case(case['id'], f'Finished case {number}/{total}')
    except BaseException as error:
        occupancy.finish(case['id'])
        progress.abandon_case(case['id'])
        # Persist only controlled error types, never subprocess environment/credentials.
        write_new(attempt / ('interrupted.json' if isinstance(error, (KeyboardInterrupt, StopRequested)) else 'failed.json'),
                  {'exception': type(error).__name__, 'at': stamp(), 'instruction': 'Inspect this attempt logs; resume retries in a new directory'})
        raise


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
        info = runtime()
        if info != manifest['runtime']:
            raise ValueError('Runtime/hardware provenance mismatch; start a new run')
        workers = config['daily']['parallel_cases']
        masks = slot_masks(workers, info.get('topology'))
        saved = manifest.get('parallelism')
        if not isinstance(saved, dict) or saved.get('parallel_cases') != workers or saved.get('slot_masks') != masks:
            raise ValueError('Saved parallelism provenance mismatch; start a new run')
        env = environment()
        completed = {case['id']: completed_attempt(run, case) is not None for case in cases}
        progress.update('Checking completed cases', completed=sum(completed.values()))
        queue = collections.deque()
        for index, case in enumerate(cases):
            if completed[case['id']]:
                continue
            for old in sorted((run / 'attempts' / case['id']).glob('*')):
                if not (old / 'completed.json').exists() and not (old / 'failed.json').exists() and not (old / 'interrupted.json').exists():
                    write_new(old / 'interrupted.json', {'reason': 'Previous orchestrator stopped without a completion receipt'})
            queue.append((index + 1, case))
        free = collections.deque(enumerate(masks))
        occupancy, stop, running, failure = Occupancy(), threading.Event(), {}, None
        # Daily cases fan out over the dedicated slots in saved order.
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(masks)) as pool:
            try:
                while (queue and failure is None) or running:
                    while queue and failure is None and free:
                        number, case = queue.popleft()
                        slot, mask = free.popleft()
                        future = pool.submit(run_case, run, manifest, env, progress, occupancy, stop, number, len(cases), case, slot, mask)
                        running[future] = (case, (slot, mask))
                    done, _ = concurrent.futures.wait(running, timeout=.5, return_when=concurrent.futures.FIRST_COMPLETED)
                    for future in done:
                        _, slot = running.pop(future)
                        free.append(slot)
                        if failure is None and future.exception() is not None:
                            failure = future.exception()  # In-flight cases finish and keep their receipts; nothing new starts.
            except BaseException:
                stop.set()
                raise
        if failure is not None:
            raise failure


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
