# Public Release Status

Status: **advanced prototype**.

This branch is suitable for a public portfolio repository and local evaluation,
not for a `1.0` manufacturing guarantee.

CI verifies the portable core, application build, camera math and gizmo math.
CPU/GPU parity and rendered ghost previews require a real Metal device and are
run by the local release harness, not by hosted GitHub runners.

Still open before a distributable release:

- sustained prints on multiple real machines;
- profile versioning and restore validation;
- SwiftUI accessibility verification;
- signed/notarized packaging;
- a third-party dependency and asset inventory for packaged distribution.
