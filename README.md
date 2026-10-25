# True Atmosphere Mod

Realistic atmosphere at vanilla performance!

![True Atmosphere Sunset Image](
https://cdn.modrinth.com/data/tO71reCr/images/242aa15620346fc04755199ad884a27d3ad420c4.png)

**What this mod does**: This mod adds realistic skies, sunsets, and sunrises that are physically
simulated to match the real world.

**How it works**: The atmosphere colors are "baked" before the game even launches (using
a separate program) and stored inside the mod. During rendering, a shader pulls the color data
and interpolates between them on a massive dome (which is the sky).

This is much cheaper than doing the entire thing real-time (like shaders do) and allows
us to do much more advanced simulation that would be otherwise infeasible in real-time.
Techniques include but not limited to multi-scattering.

**Known issues**:
 - The sun turns into a "giant white blob". Appears to be realistic with the
    current sun size but does not look good.
 - The horizontal band in the middle

## Versions

Sixteen Minecraft releases, 1.21.1 through 26.3, build on both NeoForge and Fabric.
The rendering code moves with the game, so each rendering API has its own renderer
(`src/sky-<renderer>`); everything around it is shared, with `//? if mc >= ...` blocks for the
versions that moved (`internal/PORTING.md`). `versions.json` is the source of truth. Every
version in an era compiles to the same bytecode, so one jar covers the whole era:

| Minecraft versions | NeoForge | Fabric |
|---|---|---|
| 1.21.1 | 21.1.264 | 0.116.17+1.21.1 |
| 1.21.2 - 1.21.4 | 21.2.1-beta - 21.4.161 | 0.106.1+1.21.2 - 0.119.4+1.21.4 |
| 1.21.5 | 21.5.101 | 0.128.2+1.21.5 |
| 1.21.6 - 1.21.8 | 21.6.20-beta - 21.8.57 | 0.128.2+1.21.6 - 0.136.1+1.21.8 |
| 1.21.9 - 1.21.10 | 21.9.16-beta - 21.10.x | 0.134.1+1.21.9 - 0.138.4+1.21.10 |
| 1.21.11 | 21.11.x | 0.141.6+1.21.11 |
| 26.1 - 26.1.2 | 26.1 - 26.1.2 | 0.145.1+26.1 - 0.155.3+26.1.2 |
| 26.2 | 26.2.x | 0.161.0+26.2 |
| 26.3 | 26.3.x | 0.165.0+26.3 |

There is no Gradle build at the repository root: `fabric/` and `neoforge/` are two complete,
independent builds (different plugin sets, wrappers and Java toolchains) that share the sources
in `src/` and the matrix in `versions.json`. Point your IDE at both of them.

```bash
cd neoforge && ./gradlew :1.21.4:build     # one NeoForge jar
cd fabric   && JAVA_HOME=/path/to/jdk25 ./gradlew :1.21.4:build   # one Fabric jar
scripts/build-matrix.sh                    # every version, both loaders
scripts/package-jars.sh                    # 18 jars for the whole matrix -> dist/
```

Build outputs land in `<loader>/<minecraft>/build/libs/`; `dist/` holds one jar per
era, whose metadata accepts every Minecraft version in that row.

## License

LGPL-v3.0
