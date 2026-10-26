# RTX Remix Minecraft 26.2 port

This branch preserves the official v0.1.0 source as its base and adds the
independent [26.2 module](port-26.2/README.md). The root README and Gradle
build describe the original 1.21.1 project; use the module's Python build and
check commands for 26.2. This is an experimental community port.

Current manifest: `0.1.0-port26.2-prototype.9`.

- [Implemented features, build and runtime setup](port-26.2/README.md)
- [Validation and installed artifact identity](port-26.2/docs/CURRENT-VALIDATION.md)
- [Material, glass, water and lighting demo data pack](port-26.2/demo/README.md)

The native Runtime and JNI bridge remain the official external release payload.
Neither Minecraft, native binaries nor the separately installed SPBR resource
pack are included in this source commit. Upstream LICENSE and NOTICE are retained.
