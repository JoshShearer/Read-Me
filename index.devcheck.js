/**
 * @format
 */
// Devcheck bundle entry (scripts/devcheck.sh builds with -PreadmeEntryFile). Never the
// product entry: it carries ~6 MB of fixture pages.
import { AppRegistry } from 'react-native';
import DevCheck from './src/devcheck/DevCheck';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => DevCheck);
