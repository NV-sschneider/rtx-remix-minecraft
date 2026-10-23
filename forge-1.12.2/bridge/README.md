# Java 8 Remix bridge

This isolated bridge snapshot derives from the repository's `rtxremix-java/`
at commit `f2c6fdaa763f45676f1576e63f8e054bf4ef16af`. The Fabric bridge remains
unchanged. Java records and `StringBuilder.repeat` are replaced with Java 8
constructs; the transform helper accepts primitive quaternion components so
this build needs no JOML dependency. Native signatures and C API 0.16.0 stay
unchanged, including the copied, matching MIT-licensed C header.

From the repository root, run:

```powershell
.\forge-1.12.2\scripts\build-bridge.ps1 -JdkPath C:\path\to\jdk8
```

The script defaults to a JDK in the ignored root `.tools/jdk8/` directory.
Native builds require Visual Studio x64 C++ tools. Contract checks validate
Java/native layouts, structure identifiers, and command-line redaction; a
native smoke test checks JNI allocation and cleanup. Supply `-RuntimeDirectory`
to verify negotiation with the runtime pinned in `packman/remix-sdk.packman.xml`.
Runtime, launcher, game, and generated build files are not tracked.

Copied license texts and attribution are included beside these sources.
