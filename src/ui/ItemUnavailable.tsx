// R-M10 on the Reader and Trim: an item that is gone, or one that could not be read. Only a
// missing item is "deleted" (REA-50); any other failure keeps a persistent state with Retry,
// because retrying is the user's choice. Fixed strings only: nothing here shows or logs an
// error's message.
import React from 'react';
import { View } from 'react-native';
import { Button } from './Button';
import { Text } from './Text';
import { ui } from './ui';

export function ItemUnavailable({
  kind,
  onRetry,
  onBack,
}: {
  kind: 'deleted' | 'failed';
  onRetry?: () => void;
  onBack: () => void;
}) {
  return (
    <View collapsable={false} style={ui.screen}>
      <Text style={ui.empty}>{kind === 'deleted' ? 'This item was deleted.' : 'Could not open this item.'}</Text>
      {kind === 'failed' && onRetry ? (
        <Button appearance="filled" label="Retry" style={ui.start} onPress={onRetry} />
      ) : null}
      <Button label="Back to the list" style={ui.start} onPress={onBack} />
    </View>
  );
}
