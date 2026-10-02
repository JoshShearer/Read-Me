import React, { useEffect, useState } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import { PAGES, TEXTS } from './fixtures.generated';
import { fingerprintPage, fingerprintText } from './fingerprint';

// Root of the devcheck bundle only (index.devcheck.js). Logs one DEVCHECK line per fixture:
// counts, timings and a hash, never fixture text. 'auto' takes whatever segmenter the
// runtime has, which on Hermes is the fallback (SPIKE-03).
//
// devcheck.sh launches the app once per fixture with the data URI devcheck://fixture/<name>
// (an explicit intent, so no intent filter), so each page is measured in a fresh process, as
// a share would be, not after every other fixture has churned the heap. With no URI it runs
// every fixture in one process.
const FIXTURE_URI = /^devcheck:\/\/fixture\/([a-z0-9-]+)$/;

function runFixtures(only: string | null) {
  for (const p of PAGES) {
    if (only !== null && p.name !== only) continue;
    console.log(
      `DEVCHECK ${JSON.stringify(
        fingerprintPage(p.name, p.html, p.bytes, 'auto'),
      )}`,
    );
  }
  for (const t of TEXTS) {
    if (only !== null && t.name !== only) continue;
    console.log(
      `DEVCHECK ${JSON.stringify(fingerprintText(t.name, t.text, 'auto'))}`,
    );
  }
}

export default function DevCheck() {
  const [status, setStatus] = useState('devcheck running');
  useEffect(() => {
    // Let the first frame draw before the JS thread is busy for seconds.
    const timer = setTimeout(async () => {
      const uri = await Linking.getInitialURL().catch(() => null);
      const only = FIXTURE_URI.exec(uri ?? '')?.[1] ?? null;
      const intl = Intl as unknown as { Segmenter?: unknown };
      console.log(
        `DEVCHECK_ENV ${JSON.stringify({
          hermes: 'HermesInternal' in globalThis,
          intlSegmenter: typeof intl.Segmenter === 'function',
        })}`,
      );
      runFixtures(only);
      console.log('DEVCHECK_DONE');
      setStatus('devcheck done');
    }, 500);
    return () => clearTimeout(timer);
  }, []);
  return (
    <View style={styles.root}>
      <Text>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
