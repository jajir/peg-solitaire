"""Run identical JMH profile cases in alternating baseline/candidate order."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

work = Path(__file__).resolve().parent
repo = Path('/Users/jan/projects/HestiaStore')
profile_path = repo / 'benchmarks/profiles/concatenated-page-read.json'
profile = json.loads(profile_path.read_text())
out = work / 'results' / sys.argv[1]
out.mkdir(parents=True, exist_ok=True)
metadata = {
    'profile': profile,
    'profileSha256': hashlib.sha256(profile_path.read_bytes()).hexdigest(),
    'javaVersion': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
    'jars': json.loads((work / 'paired-jars.json').read_text()),
    'runs': [],
}
for index, case in enumerate(profile['benchmarks']):
    order = ['baseline', 'candidate'] if index % 2 == 0 else ['candidate', 'baseline']
    for version in order:
        folder = out / version
        folder.mkdir(exist_ok=True)
        raw = folder / (case['label'] + '.json')
        log = folder / (case['label'] + '.log')
        command = ['java', '-jar', str(work / (version + '.jar')),
                   case['include'], *case['args'], '-rf', 'json', '-rff', str(raw)]
        print(time.strftime('%H:%M:%S'), version, case['label'], flush=True)
        before = subprocess.run(['ps', '-p', '29184', '-o', 'pid,state,pcpu,etime'],
                                capture_output=True, text=True).stdout
        start = time.time()
        with log.open('w') as output:
            subprocess.run(command, cwd=repo, stdout=output, stderr=subprocess.STDOUT,
                           check=True, timeout=180)
        rows = json.loads(raw.read_text())
        if len(rows) != case['expectedResultCount'] or {r['benchmark'] for r in rows} != set(case['expectedBenchmarks']):
            raise RuntimeError('Incomplete results: ' + case['label'])
        metadata['runs'].append({'version': version, 'label': case['label'],
                                 'command': command, 'startEpoch': start,
                                 'seconds': time.time() - start, 'solverBefore': before})
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2))
print('Complete:', out, flush=True)
