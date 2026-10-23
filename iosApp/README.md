# Tidings iOS Shell

This directory is a minimal SwiftUI shell for the generated KMP app. It is a
first-class part of the generated app shape, but intentionally project-light:
signing, team id, schemes, asset catalogs, and Xcode target ownership are
product-owned.

Create an Xcode project or Swift Package app target that:

- Uses bundle id `com.kapp.tidings`.
- Uses `Info.plist` as the target plist.
- Links the generated framework from the shared `composeApp` KMP module.
- Uses `iOSApp.swift` as the app entry point.

The iOS shell should stay thin: lifecycle, permissions, native SDK wiring, and
platform provider adapters only. Product state, navigation, and shared UI should
stay in `composeApp`.

## Build Shared Framework

For simulator smoke:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

For device framework smoke:

```bash
./gradlew :composeApp:linkDebugFrameworkIosArm64
```

The framework base name is `RelayShared`.
