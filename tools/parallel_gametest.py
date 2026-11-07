"""Runs the Deep Color client gametest for many configurations at the same time.

Gradle cannot do this itself: two runs in one project wait for each other. Instead this script asks
Gradle once per mod set for the exact launch command (task writeGameTestLaunch), then starts several
game instances directly, each in its own directory under run/parallel/.

    python tools/parallel_gametest.py                      # whole matrix, 4 at a time
    python tools/parallel_gametest.py -j 3 gl vk-sodium    # only these jobs
    python tools/parallel_gametest.py --pack C:/packs/ComplementaryReimagined_r5.9.3.zip

Every instance opens a game window. Each needs about 2.5 GB of memory and 0.3 GB of GPU memory;
8 at once peaked at 19.7 GB and 3.7 GB on a 32 GB / RTX 3070 machine.
"""
import argparse
import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAUNCH_DIR = ROOT / 'build' / 'gametest-launch'
RUN_ROOT = ROOT / 'run' / 'parallel'
FAKE_HDR_MOD = ROOT / 'src' / 'gametest' / 'fake-hdr-mod'
MOD_FLAGS = {'vanilla': [], 'sodium': ['-Pdc.sodium'], 'iris': ['-Pdc.iris']}
# A job can pin another Sodium build with "sodium": "<modrinth version>".
# A normal run takes 30 to 50 seconds. Anything far beyond that is a hang.
TIMEOUT_SECONDS = 150
# The harness startup hang shows as a log that stops before the first test line. Loading never goes
# quiet that long, so this catches it well before the overall timeout.
STARTUP_SILENCE_SECONDS = 45
print_lock = threading.Lock()


def say(text):
    with print_lock:
        print(text, flush=True)


def gradle(*args):
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    result = subprocess.run([str(wrapper), *args, '--console=plain', '-q'], cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit('Gradle failed:\n' + result.stdout[-3000:] + result.stderr[-3000:])


def launch_name(job):
    return job['mods'] + (f'-{job["sodium"]}' if job.get('sodium') else '')


def prepare_launches(jobs):
    """Builds everything once, then writes one launch description per mod set."""
    gradle('build', 'gametestClasses')
    for name, job in sorted({launch_name(job): job for job in jobs}.items()):
        extra = [f'-Pdc.sodiumVersion={job["sodium"]}'] if job.get('sodium') else []
        gradle('writeGameTestLaunch', *MOD_FLAGS[job['mods']], *extra)
        say(f'launch command ready: {name}')


def prepare_run_dir(job, pack):
    run_dir = RUN_ROOT / job['name']
    if run_dir.exists():
        shutil.rmtree(run_dir)
    (run_dir / 'config').mkdir(parents=True)
    (run_dir / 'eula.txt').write_text('eula=true\n')
    # Iris decides on its Vulkan mode from options.txt, before any command line is read.
    (run_dir / 'options.txt').write_text(f'preferredGraphicsBackend:"{job["backend"]}"\n')
    if 'config' in job:
        (run_dir / 'config' / 'deepcolor.json').write_text(json.dumps(job['config']))
    if job.get('pack'):
        if pack is None:
            raise ValueError(f'job {job["name"]} needs a shader pack, pass --pack')
        (run_dir / 'shaderpacks').mkdir()
        shutil.copy(pack, run_dir / 'shaderpacks' / pack.name)
        (run_dir / 'config' / 'iris.properties').write_text(f'enableShaders=true\nshaderPack={pack.name}\n')
    return run_dir


def argfile_path(path):
    # Java argfiles treat backslashes as escapes inside quotes, so use forward slashes everywhere.
    return str(path).replace('\\', '/')


def run_job_with_retry(job, pack):
    """
    Fabric's client gametest harness occasionally deadlocks while starting up (its test thread and
    the game wait for each other in ThreadingImpl, roughly 1 run in 8 under load). That happens before
    any test code runs, so such a run is retried once and reported as retried. A hang after the test
    started is a real failure and is not retried.
    """
    result = run_job(job, pack)
    if str(result['exit']).startswith('timeout') and not result['test_started']:
        say(f'retry {job["name"]}: the test harness hung before the test started')
        hung_dir = RUN_ROOT / job['name']
        kept = RUN_ROOT / (job['name'] + '-harness-hang')
        try:
            if kept.exists():
                shutil.rmtree(kept)
            shutil.copytree(hung_dir, kept, ignore=shutil.ignore_patterns('saves', 'data', 'downloads', 'resourcepacks', 'mods'))
        except OSError as error:
            # Keeping the evidence is nice to have; a locked file must not stop the retry.
            say(f'could not keep the hung run of {job["name"]}: {error}')
        result = run_job(job, pack)
        result['notes'].insert(0, 'retried once after a harness startup hang (kept in ' + kept.name + ')')
    return result


def run_job(job, pack):
    started = time.time()
    run_dir = prepare_run_dir(job, pack)
    launch = json.loads((LAUNCH_DIR / f'{launch_name(job)}.json').read_text())
    classpath = list(launch['classpath'])
    if job.get('fakeHdr'):
        classpath.append(str(FAKE_HDR_MOD))
    # Loom's own argfile is shared by every run of the project, so the classpath comes from here.
    jvm_args = [arg for arg in launch['jvmArgs'] if not arg.startswith('@')]
    argfile = run_dir / 'launch.args'
    argfile.write_text('-cp\n' + ';'.join(argfile_path(entry) for entry in classpath) + '\n')
    command = [launch['java'], f'@{argfile}', *jvm_args, *job.get('jvmArgs', []), launch['mainClass'], *launch['args'], '--graphicsBackend', job['backend']]

    log_path = run_dir / 'output.log'
    with open(log_path, 'w', encoding='utf-8', errors='replace') as log:
        process = subprocess.Popen(command, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT)
        exit_code = watch(process, log_path, started)
        if exit_code is None:
            dump_threads(launch['java'], process.pid, run_dir / 'hang-threads.txt')
            process.kill()
            process.wait()
            exit_code = 'timeout (thread dump in hang-threads.txt)'
    return summarize(job, run_dir, log_path, exit_code, time.time() - started)


def watch(process, log_path, started):
    """Returns the exit code, or None for a hang: overall timeout, or silence before the test starts."""
    last_size = -1
    last_change = time.time()
    while True:
        try:
            return process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
        now = time.time()
        if now - started > TIMEOUT_SECONDS:
            return None
        size = log_path.stat().st_size
        if size != last_size:
            last_size, last_change = size, now
        elif now - last_change > STARTUP_SILENCE_SECONDS and '[DeepColorTest]' not in log_path.read_text(encoding='utf-8', errors='replace'):
            return None


def dump_threads(java, pid, path):
    """Saves where every thread of a hung game is, before it gets killed."""
    jstack = Path(java).with_name('jstack.exe' if os.name == 'nt' else 'jstack')
    try:
        result = subprocess.run([str(jstack), '-l', str(pid)], capture_output=True, text=True, timeout=60)
        path.write_text(result.stdout + result.stderr, encoding='utf-8')
    except (OSError, subprocess.TimeoutExpired) as error:
        path.write_text(f'jstack failed: {error}\n', encoding='utf-8')


def summarize(job, run_dir, log_path, exit_code, seconds):
    log = log_path.read_text(encoding='utf-8', errors='replace')
    results_file = run_dir / 'deepcolor-test-result.txt'
    results = results_file.read_text(encoding='utf-8').splitlines() if results_file.exists() else []
    passed = sum(line.startswith('PASS') for line in results)
    failed = [line for line in results if line.startswith('FAIL')]
    notes = []
    status = re.search(r'Output active: (.*)', log)
    if status:
        notes.append(status.group(1).strip())
    if 'disabled itself' in log:
        notes.append('disabled itself next to HDR Mod')
    # Iris refuses to run shaders on Vulkan, so a pack is only expected to load on OpenGL.
    expect_shaders = job.get('pack') and job['backend'] == 'opengl'
    if expect_shaders:
        notes.append('shader pack loaded' if 'Using shaderpack' in log else 'SHADER PACK NOT LOADED')
    for marker in ('did not apply', 'Dither pass failed', 'No source recorded', 'VK_ERROR_DEVICE_LOST'):
        if marker in log:
            notes.append('LOG: ' + marker)
    ok = exit_code == 0 and not failed and results and (not expect_shaders or 'Using shaderpack' in log)
    return {'name': job['name'], 'ok': bool(ok), 'exit': exit_code, 'passed': passed, 'failed': failed,
            'seconds': seconds, 'notes': notes, 'log': str(log_path), 'test_started': '[DeepColorTest]' in log}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('jobs', nargs='*', help='job names from tools/test-matrix.json, default all')
    parser.add_argument('-j', '--parallel', type=int, default=8, help='instances at once (default 8)')
    parser.add_argument('--pack', type=Path, help='shader pack zip for the iris jobs')
    parser.add_argument('--skip-build', action='store_true', help='reuse the last build and launch commands')
    parser.add_argument('--repeat', type=int, default=1, help='run each selected job this many times, to catch rare failures')
    args = parser.parse_args()

    matrix = json.loads((ROOT / 'tools' / 'test-matrix.json').read_text())['jobs']
    jobs = [job for job in matrix if not args.jobs or job['name'] in args.jobs]
    unknown = set(args.jobs) - {job['name'] for job in matrix}
    if unknown:
        sys.exit(f'unknown jobs: {", ".join(sorted(unknown))}')
    if args.repeat > 1:
        jobs = [dict(job, name=f'{job["name"]}-{run + 1}') for job in jobs for run in range(args.repeat)]
    if args.pack is None:
        skipped = [job['name'] for job in jobs if job.get('pack')]
        jobs = [job for job in jobs if not job.get('pack')]
        if skipped:
            say(f'skipping {", ".join(skipped)}: no --pack given')

    started = time.time()
    if not args.skip_build:
        prepare_launches(jobs)
    say(f'running {len(jobs)} jobs, {args.parallel} at a time')

    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = {pool.submit(run_job_with_retry, job, args.pack): job for job in jobs}
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            say(f'{"PASS" if result["ok"] else "FAIL"}  {result["name"]:<20} {result["passed"]:>2} checks  {result["seconds"]:5.0f}s  exit {result["exit"]}')

    say('')
    say(f'{sum(r["ok"] for r in results)} of {len(results)} jobs passed in {time.time() - started:.0f}s')
    for result in sorted(results, key=lambda r: r['name']):
        if not result['ok'] or result['notes']:
            say(f'  {result["name"]}: ' + ' | '.join(result['notes'] + result['failed']))
        if not result['ok']:
            say(f'    log: {result["log"]}')
    sys.exit(0 if all(r['ok'] for r in results) else 1)


if __name__ == '__main__':
    main()
