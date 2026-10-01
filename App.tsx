import React, {useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {runSpike} from './src/spikes/run';

type Props = {spike?: string};

export default function App({spike}: Props) {
  const [status, setStatus] = useState(spike ? `running spike: ${spike}` : 'Read Me');

  useEffect(() => {
    if (spike) {
      runSpike(spike).then(setStatus);
    }
  }, [spike]);

  return (
    <View style={styles.root}>
      <Text style={styles.text}>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  text: {fontSize: 18},
});
