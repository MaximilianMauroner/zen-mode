# Dependency patches

## `react-native-screens` 4.26.2 Android listener lifetime fix

`react-native-screens+4.26.2.patch` is an unchanged backport of the merged
Android fix from:

- Original fix: https://github.com/software-mansion/react-native-screens/pull/4413
- SDK 57-era backport: https://github.com/software-mansion/react-native-screens/pull/4637
- Upstream fix commit: `b3badd012f83679b12f4e29f2e28eceaa4830efd`
- Backport merge commit: `584fc86ceb0b00fbabfbc878dd8f8f5a8a7c94f5`

The four patched files match the corresponding files released in
`react-native-screens` 4.28.0 exactly; no Zen Mode-specific adaptation was
made. The backport is needed because Expo SDK 57 declares
`react-native-screens` `~4.26.0`, while the upstream fix first ships outside
that range. It prevents concurrent Fabric initialization from corrupting the
screen-removal listener's `shared_ptr` and later crashing in
`MountingCoordinator::pullTransaction`.

Remove this patch and `patch-package` only after Zen Mode upgrades to an
Expo-supported `react-native-screens` version that contains the fix, and a
clean install plus the Android cold-start and Settings escape-path checks pass
without it.
