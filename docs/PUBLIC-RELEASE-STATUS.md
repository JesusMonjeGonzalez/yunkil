# Public Release Status

Status: **advanced prototype**.

This branch is suitable for a public portfolio repository and local evaluation,
not for a `1.0` manufacturing guarantee.

CI verifies the portable core, camera math and gizmo math. The macOS app build,
CPU/GPU parity and rendered ghost previews require the local Kotlin/Native and
Metal toolchain, so they are run by the local release harness rather than by
hosted GitHub runners.

Still open before a distributable release:

- sustained prints on multiple real machines;
- profile versioning and restore validation;
- SwiftUI accessibility verification;
- signed/notarized packaging;
- a third-party dependency and asset inventory for packaged distribution.
