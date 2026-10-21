import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from datetime import datetime
from pathlib import Path

import representation_study as rs
import stack_profiles as sp


def jdk_tool(name):
    home = os.environ.get('JAVA_HOME')
    return shutil.which(name, path=str(Path(home) / 'bin')) if home else shutil.which(name)


JFR = jdk_tool('jfr')
INSTANT = re.compile(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\.?(\d*)(Z|[+-]\d\d:\d\d)')


def instant(text):
    """Nanoseconds since the epoch: jfr print writes the local offset, JfrEvents UTC, for the same instant."""
    match = INSTANT.fullmatch(text)
    whole = datetime.fromisoformat(match[1] + ('+00:00' if match[3] == 'Z' else match[3]))
    return int(whole.timestamp()) * 1_000_000_000 + int(match[2].ljust(9, '0'))
# Calls deep enough for the 64-frame cut, allocations for the allocation samples and a collection for the GC event.
PROGRAM = '''
public class Busy {
    static long sink;
    static long deep(int depth, long seed) {
        if (depth > 0) return deep(depth - 1, seed) + 1;
        long total = seed;
        for (int i = 0; i < 2_000; i++) { total += new long[16 + (i & 7)].length; total ^= Long.rotateLeft(total, 7); }
        return total;
    }
    public static void main(String[] arguments) {
        long until = System.nanoTime() + 2_000_000_000L;
        int round = 0;
        while (System.nanoTime() < until) { sink += deep(round++ % 90, sink); if (round % 5_000 == 0) System.gc(); }
        System.out.println(sink);
    }
}
'''


@unittest.skipIf(JFR is None, 'a JDK with jfr is required (JAVA_HOME or PATH)')
class JfrEventsTest(unittest.TestCase):
    """JfrEvents must give the attribution what jfr print --json gives it, on a recording made here."""

    @classmethod
    def setUpClass(cls):
        cls.directory = Path(tempfile.mkdtemp(prefix='jfr-events-test-'))
        source, cls.recording = cls.directory / 'Busy.java', cls.directory / 'busy.jfr'
        source.write_text(PROGRAM, encoding='utf-8')
        java = jdk_tool('java')
        subprocess.run([java, f'-XX:StartFlightRecording:filename={cls.recording},settings=profile', str(source)],
                       capture_output=True, check=True, text=True, timeout=120)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.directory, ignore_errors=True)

    def printed(self, events):
        result = subprocess.run([JFR, 'print', '--json', '--stack-depth', str(rs.STACK_DEPTH), '--events', events, str(self.recording)],
                                capture_output=True, check=True, text=True, encoding='utf-8')
        return json.loads(result.stdout)

    def test_the_attribution_reads_the_same_from_both(self):
        printed, read = self.printed('jdk.ExecutionSample,jdk.ObjectAllocationSample'), rs.jfr_document(self.recording, JFR)
        self.assertEqual(len(printed['recording']['events']), len(read['recording']['events']))
        self.assertGreater(sum(rs.attribute(read)['samples'].values()), 0)
        self.assertGreater(sum(rs.attribute(read)['allocationBytes'].values()), 0)
        self.assertEqual(rs.attribute(printed), rs.attribute(read))
        threads = Counter(sp.thread_of(event) for event in printed['recording']['events'])
        self.assertEqual(threads, Counter(sp.thread_of(event) for event in read['recording']['events']))
        for thread in threads:
            if thread is None:
                continue
            (expected, other), (actual, read_other) = sp.on_thread(printed, thread), sp.on_thread(read, thread)
            self.assertEqual((rs.attribute(expected), other), (rs.attribute(actual), read_other), thread)

    def test_stacks_are_cut_at_the_depth_and_fields_keep_jfr_print_s_forms(self):
        events = 'jdk.ExecutionSample,jdk.GarbageCollection'
        printed, read = self.printed(events), rs.jfr_document(self.recording, JFR, events)
        deepest = max(len(event['values']['stackTrace']['frames']) for event in read['recording']['events']
                      if event['type'] == 'jdk.ExecutionSample' and event['values'].get('stackTrace'))
        self.assertEqual(deepest, rs.STACK_DEPTH)

        def collections(document):
            return sorted((event['values']['gcId'], instant(event['values']['startTime']), event['values']['sumOfPauses'], event['values']['name'])
                          for event in document['recording']['events'] if event['type'] == 'jdk.GarbageCollection')

        # The forced collections: the same ids, instants and durations (ISO durations, as jfr print writes them).
        self.assertTrue(collections(read))
        self.assertEqual(collections(printed), collections(read))

        def samples(document):
            return sorted((instant(event['values']['startTime']), tuple((frame['method']['type']['name'].replace('/', '.'), frame['method']['name'], frame['lineNumber'])
                                                                 for frame in event['values']['stackTrace']['frames']))
                          for event in document['recording']['events'] if event['type'] == 'jdk.ExecutionSample' and event['values'].get('stackTrace'))

        # Class names as the attribution reads them: jfr print writes java/lang/String, RecordedClass java.lang.String.
        self.assertEqual(samples(printed), samples(read))


if __name__ == '__main__':
    unittest.main()
