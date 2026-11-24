# VM performance test

These scripts load the scheduler VM through its public API from a workstation and record what each stage costs on the VM, so a 2 vCPU VM's limits and binding resources can be read in the morning. The study's plan and bars live in `research/vm-performance-2026-11/plan.md`, and its start script in `research/vm-performance-2026-11/start.ps1`.

## How it fits together

The diagram shows where each part runs and what it reaches.

```text
workstation                              scheduler VM (~/perf-test, outside the checkout)
+--------------------------+  HTTPS 443  +----------------------------------------+
| run-vm-performance.ps1   |------------>| scheduler-proxy --> scheduler-booking  |
|   routes-load.ts         |             |                 --> scheduler-daily    |
|   load.ts                |             |        |                               |
|                          | SSH tunnel  |        v                               |
|   metrics 19010, 19011 <-|-------------| :9000  routing-omaha  scheduler-db     |
|                          |     SSH     |                                        |
|   stages, copies --------|------------>| vm-stage.sh  vm-sampler.sh             |
|                          |             | vm-collect.sh  vm-restore.sh  vm-arm.sh|
+--------------------------+             +----------------------------------------+
```

- **`run-vm-performance.ps1`:** the workstation runner. It builds the cell matrix (`-Mode DryRun` prints it), copies the VM files to `~/perf-test`, changes stages over SSH, runs each cell, copies each stage's logs and recordings back, and always restores the live layout in its `finally` block.
- **`perf.override.yml` and `perf.ports.yml`:** the test's additions to the live Compose stack. The first mounts `~/perf-test/profiles` in the Java containers, lets routing take JVM flags and the booking pool a background limit. The second publishes each pool's metrics port on the VM's loopback.
- **`vm-stage.sh <stage> [KEY=VALUE ...]`:** starts a stage from the live `deploy/vm/.env` plus the test's recording flags and the stage's whitelisted settings, then starts the booking and daily JVMs again and writes `out/<stage>/stage.json`.
- **`vm-sampler.sh`:** every 5 s, one JSON line of cgroup CPU, throttling and memory per container, and host CPU with steal, pressure stalls, memory, swap and disk. It reads host files only, never `docker stats`.
- **`vm-collect.sh`:** saves each service's log for a stage before the next stage recreates the containers, and removes copied files.
- **`vm-restore.sh`:** the documented `up` with the live env file and no override, which takes every addition away. **`vm-arm.sh`** runs it after a set number of hours unless the runner restored first.
- **`build_points.py`:** builds the run's pool of routable points (`points.json`) from the imported backtest days' job addresses, which generated days and jobs are drawn from. Random points over a wide area often land where no road is within the router's reach. Tested by `infra/test_vm_perf_points.py`.
- **`summarize.py`:** writes `summary.md` and `research/evidence/<run>.json` from what the run wrote. Tested by `infra/test_vm_perf_summary.py`.

The drive-time tool is `infra/host-conformance/routes-load.ts`, beside the kit's `load.ts`.

## Run it

Start it with `research/vm-performance-2026-11/start.ps1`, which asks for the token once. To see the matrix only:

```powershell
powershell -ExecutionPolicy Bypass -File infra/vm-perf/run-vm-performance.ps1 -Mode DryRun
```

The runner needs:

- An SSH key for the VM user, since stage changes run without a password prompt.
- `$env:SCHEDULER_STUDY_TOKEN` holding a token of a dedicated tenant (`loadtest`); it is never read from a file.
- The workstation awake and on the VM's network for the whole run; the runner keeps Windows from sleeping.

Run the same command again with the same `-Run` to resume: a cell with a `cell.json` is done and skipped.

## What a run leaves

Each run folder under `experiments/runs/` holds the following:

- **`stages/<stage>/cells/<cell>/`:** the cell's `cell.json`, tool reports (`routes-*.json`, `load-*.json` and their markdown) and tool logs.
- **`stages/<stage>/vm-out/<stage>/`:** `stage.json` (caps and settings each container ran with) and `logs/` (each service's log and the watchdog's lines).
- **`stages/<stage>/vm-profiles/<stage>/`:** the stage's flight recordings (`booking.jfr`, `daily.jfr`), GC logs and per-solve recordings (`solves/`).
- **`vm/`:** `samples.jsonl`, `live-caps.txt`, `host.txt`, routing's recordings, the restore log, and the live check before and after.
- **`summary.md`:** the generated summary.

Open a `.jfr` file in JDK Mission Control for the deep dive.

## Safety on the VM

- The live `deploy/vm/.env` and the checkout are only read. Stage env files hold the live secrets, so they stay in `~/perf-test/stages` with mode 600 and never leave the VM.
- A stage may set only CPU, memory, admission, pool and per-solve recording settings (`vm-stage.sh` refuses any other key).
- No stage starts under 3 GB of free disk, and the sampler raises `out/DISK_LOW`, which stops the run.
- The dead-man timer restores the live layout after `-DeadmanHours` (11 by default). Each arming has its own ID, so a timer from an earlier run never fires into a later one.
- The watchdog cron stays on: a replica it restarts under load is one of the limits the run reports.

## Local rehearsal

`-Local` runs the same VM scripts through Git Bash against the local rehearsal stack (`deploy/vm/rehearsal.override.yml`, `http://127.0.0.1:18080`), with `PERF_HOME` in `.scratch/vm-perf-rehearsal`. The rehearsal needs the development `routing-service` on 8001 and `routing-preview` on 18001 serving the VM's map version. Windows has no cgroup or pressure files, so a rehearsal's samples read "not measured" for them: it proves the pipeline, not the figures.
