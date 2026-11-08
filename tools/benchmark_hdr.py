"""Run opt-in HDR GPU/frame benchmarks strictly sequentially (one game process at a time)."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('matrix', ROOT / 'tools/parallel_gametest.py')
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', default='d0a216b', help='Historical HDR shader revision (requires local Git history)')
parser.add_argument('backends', nargs='*', default=['opengl', 'vulkan'], choices=['opengl', 'vulkan'])
args = parser.parse_args()
matrix.gradle('build', 'gametestClasses', 'writeGameTestLaunch')
# Generate the historical fixture into test build output; do not ship a copied old shader.
baseline = subprocess.check_output(['git', 'show', args.baseline + ':src/main/resources/assets/deepcolor/shaders/dither.fsh'], cwd=ROOT)
fixture = ROOT / 'build/resources/gametest/assets/deepcolor/shaders/perf_hdr_baseline.fsh'
fixture.parent.mkdir(parents=True, exist_ok=True)
fixture.write_bytes(baseline)
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
for backend in args.backends:
    job = {'name': 'perf-' + backend, 'backend': backend, 'mods': 'vanilla', 'config': {'hdrOutput': 'on'}}
    # Keep old benchmark runs; no destructive run-directory setup from the regression runner.
    name = time.strftime('%Y%m%d-%H%M%S') + '-' + backend
    run_dir = ROOT / 'run/performance' / name
    (run_dir / 'config').mkdir(parents=True)
    (run_dir / 'eula.txt').write_text('eula=true\n')
    (run_dir / 'options.txt').write_text(f'preferredGraphicsBackend:"{backend}"\nenableVsync:false\nmaxFps:260\npauseOnLostFocus:false\n')
    (run_dir / 'config/deepcolor.json').write_text(json.dumps(job['config']))
    launch = json.loads((matrix.LAUNCH_DIR / 'vanilla.json').read_text())
    argfile = run_dir / 'launch.args'
    argfile.write_text('-cp\n"' + os.pathsep.join(matrix.argfile_path(p) for p in launch['classpath']) + '"\n')
    command = [launch['java'], '@' + str(argfile), *[a for a in launch['jvmArgs'] if not a.startswith('@')],
        '-Ddeepcolor.test.forceScrgb=true', '-Ddeepcolor.test.assumeHdr=true', '-Ddeepcolor.test.benchmark=true',
        '-Ddeepcolor.bench.commit=' + commit, '-Ddeepcolor.bench.baseline=' + args.baseline, launch['mainClass'], *launch['args'], '--graphicsBackend', backend]
    print('Starting one instance:', backend, run_dir, flush=True)
    with (run_dir / 'output.log').open('w') as log:
        process = subprocess.Popen(command, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT)
        started = time.monotonic()
        try:
            while True:
                if time.monotonic() - started > 600:
                    raise TimeoutError('Benchmark exceeded ten minutes')
                try:
                    code = process.wait(timeout=30)
                    break
                except subprocess.TimeoutExpired:
                    lines = (run_dir / 'output.log').read_text(errors='replace').splitlines()
                    progress = [line for line in lines if '[DeepColorBench]' in line]
                    print(progress[-1] if progress else 'Still starting ' + backend, flush=True)
        except BaseException:
            process.terminate()
            process.wait(timeout=15)
            raise
    if code or not (run_dir / 'hdr-performance.json').exists():
        print((run_dir / 'output.log').read_text(errors='replace')[-6000:])
        sys.exit(f'Benchmark failed ({code}): {run_dir}')
    print('Completed:', run_dir / 'hdr-performance.json', flush=True)
