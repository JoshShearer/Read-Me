import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PAGES, TEXTS } from './fixtures.generated';
import { fingerprintPage, fingerprintText } from './fingerprint';

// Root of the devcheck bundle only (index.devcheck.js). Logs one DEVCHECK line per fixture:
// counts, timings and a hash, never fixture text. 'auto' takes whatever segmenter the
// runtime has, which on Hermes is the fallback (SPIKE-03).
export default function DevCheck() {
  const [status, setStatus] = useState('devcheck running');
  useEffect(() => {
    // Let the first frame draw before the JS thread is busy for seconds.
    const timer = setTimeout(() => {
      const intl = Intl as unknown as { Segmenter?: unknown };
      console.log(
        `DEVCHECK_ENV ${JSON.stringify({
          hermes: 'HermesInternal' in globalThis,
          intlSegmenter: typeof intl.Segmenter === 'function',
        })}`,
      );
      for (const p of PAGES) {
        console.log(
          `DEVCHECK ${JSON.stringify(
            fingerprintPage(p.name, p.html, p.bytes, 'auto'),
          )}`,
        );
      }
      for (const t of TEXTS) {
        console.log(
          `DEVCHECK ${JSON.stringify(fingerprintText(t.name, t.text, 'auto'))}`,
        );
      }
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
