"""Count materialization calls in a single merge; never measure wall time."""
import json
from pathlib import Path
import subprocess

work = Path(__file__).resolve().parent
production = Path('/Users/jan/projects/HestiaStore')
relative = Path('engine/src/main/java/org/hestiastore/index/bytes/ConcatenatedByteSequence.java')
probe = '''package org.hestiastore.index.senku.internal;

import org.hestiastore.index.bytes.ConcatenatedByteSequence;

public final class MergeCallProbe {
    public static void main(final String[] args) {
        final SenkuMaintenanceMergeBenchmark benchmark = new SenkuMaintenanceMergeBenchmark();
        benchmark.entryCount = 1_000_000;
        benchmark.sourceCount = Integer.parseInt(args[0]);
        benchmark.duplicatePercent = 50;
        benchmark.setupTrial();
        benchmark.setupInvocation();
        ConcatenatedByteSequence.materializationCalls = 0;
        ConcatenatedByteSequence.materializedBytes = 0;
        final long outputRecords = benchmark.merge();
        System.out.println("MERGE_PROBE " + benchmark.sourceCount + " "
                + ConcatenatedByteSequence.materializationCalls + " "
                + ConcatenatedByteSequence.materializedBytes + " " + outputRecords);
    }
}
'''
results = []
for version, repo in [('baseline', work / 'baseline'), ('candidate', production)]:
    folder = work / 'merge-call-probe' / version
    folder.mkdir(parents=True, exist_ok=True)
    source = (repo / relative).read_text()
    anchor = '    private final ByteSequence first;'
    assert source.count(anchor) == 1
    source = source.replace(anchor, '''    public static long materializationCalls;
    public static long materializedBytes;

''' + anchor)
    anchor = '    protected byte[] computeByteArray() {'
    assert source.count(anchor) == 1
    source = source.replace(anchor, anchor + '''
        materializationCalls++;
        materializedBytes += totalLength;''')
    source_path = folder / 'ConcatenatedByteSequence.java'
    source_path.write_text(source)
    probe_path = folder / 'MergeCallProbe.java'
    probe_path.write_text(probe)
    classes = folder / 'classes'
    classes.mkdir(exist_ok=True)
    jar = work / (version + '.jar')
    subprocess.run(['javac', '-proc:none', '-cp', str(jar), '-d', str(classes),
                    str(source_path), str(probe_path)], check=True)
    for source_count in (4, 64):
        command = ['java', '-Xms1g', '-Xmx1g', '-XX:ActiveProcessorCount=4',
                   '-cp', str(classes) + ':' + str(jar),
                   'org.hestiastore.index.senku.internal.MergeCallProbe', str(source_count)]
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
        log = folder / ('sources-' + str(source_count) + '.log')
        log.write_text(completed.stdout + completed.stderr)
        matches = [line for line in completed.stdout.splitlines() if line.startswith('MERGE_PROBE ')]
        assert len(matches) == 1, completed.stdout
        _, actual_sources, calls, total_bytes, output_records = matches[0].split()
        result = {'version': version, 'sourceCount': int(actual_sources),
                  'entryCount': 1_000_000, 'duplicatePercent': 50,
                  'materializationCalls': int(calls), 'materializedBytes': int(total_bytes),
                  'outputRecords': int(output_records), 'command': command}
        results.append(result)
        print(json.dumps(result), flush=True)
(work / 'merge-call-probe' / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
