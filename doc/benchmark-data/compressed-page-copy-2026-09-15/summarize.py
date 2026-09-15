"""Compare complete JMH cases by method, parameters, mode, and metric unit."""
import json
from pathlib import Path
import sys

folder = Path(sys.argv[1])
metadata = json.loads((folder / 'metadata.json').read_text())
done = {(run['version'], run['label']) for run in metadata['runs']}
key = lambda row: (row['benchmark'], tuple(sorted(row.get('params', {}).items())))
comparison = []
for case in metadata['profile']['benchmarks']:
    label = case['label']
    if not all((version, label) in done for version in ['baseline', 'candidate']):
        continue
    baseline = {key(row): row for row in json.loads((folder / 'baseline' / (label + '.json')).read_text())}
    candidate = {key(row): row for row in json.loads((folder / 'candidate' / (label + '.json')).read_text())}
    assert baseline.keys() == candidate.keys(), label
    for identity, old in baseline.items():
        new = candidate[identity]
        before, after = old['primaryMetric'], new['primaryMetric']
        assert old['mode'] == new['mode']
        assert before['scoreUnit'] == after['scoreUnit']
        relative = (after['score'] / before['score'] - 1) * 100
        time_gain = -relative if old['mode'] in ['avgt', 'sample', 'ss'] else relative
        old_alloc = old['secondaryMetrics']['gc.alloc.rate.norm']['score']
        new_alloc = new['secondaryMetrics']['gc.alloc.rate.norm']['score']
        overlap = max(before['scoreConfidence'][0], after['scoreConfidence'][0]) <= min(before['scoreConfidence'][1], after['scoreConfidence'][1])
        comparison.append({'label': label, 'benchmark': old['benchmark'], 'params': old.get('params', {}),
                           'mode': old['mode'], 'unit': before['scoreUnit'],
                           'baseline': before['score'], 'candidate': after['score'],
                           'baselineError': before['scoreError'], 'candidateError': after['scoreError'],
                           'performanceGainPercent': time_gain, 'confidenceIntervalsOverlap': overlap,
                           'baselineBytesPerOp': old_alloc, 'candidateBytesPerOp': new_alloc,
                           'allocationReductionPercent': (1 - new_alloc / old_alloc) * 100})
(folder / 'comparison.json').write_text(json.dumps(comparison, indent=2))
lines = ['| Case | Baseline | Candidate | Performance change | Allocation B/op (before → after) |',
         '| --- | ---: | ---: | ---: | ---: |']
for row in comparison:
    params = ', '.join(k + '=' + v for k, v in row['params'].items())
    lines.append(f"| {row['label']} ({params}) | {row['baseline']:.3f} ± {row['baselineError']:.3f} {row['unit']} | "
                 f"{row['candidate']:.3f} ± {row['candidateError']:.3f} {row['unit']} | "
                 f"{row['performanceGainPercent']:+.2f}% | {row['baselineBytesPerOp']:.1f} → {row['candidateBytesPerOp']:.1f} |")
report = '\n'.join(lines) + '\n'
(folder / 'comparison.md').write_text(report)
print(report)
