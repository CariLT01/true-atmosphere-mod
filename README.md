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

## License

LGPL-v3.0
