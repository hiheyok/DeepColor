# Deep Color

Client-side Fabric mod for Minecraft Java 26.3 that reduces banding in dark scenes. It renders with
more than 8 bits per channel, outputs 10-bit where the driver allows it, and dithers the final image
just before it is quantized.

By default this is an **SDR** mod: it only picks sRGB surfaces. The optional **HDR output** (off by
default) lets the game use the monitor's full brightness range when Windows HDR is on, or the
display's EDR headroom on macOS.

Runs on Windows and macOS (see [macOS](#macos) for what differs there).

- Internal targets upgraded to `RGBA16_FLOAT`: the main target, the lightmap, post chains (menu blur)
  and the entity outline target.
- 10-bit output on OpenGL and Vulkan.
- Triangular-PDF blue noise dither at ±1 LSB, animated per frame. It fades out in the last LSB, so
  true black stays exactly black. Plain TPDF would lift it, which shows on an OLED.
- Dithers for 10 bits in exclusive fullscreen and for 8 bits in a window, where Windows reduces the
  image to 8 bits anyway (see below).
- Settings page in Sodium's video settings, and in Mod Menu.
- Works with Sodium, and with Iris shader packs on OpenGL.

## Install

Needs Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API and Java 25. Sodium, Iris and Mod Menu
are optional.

1. Download or build `deepcolor-1.0.0.jar` and put it in `mods/`.
2. Set your driver to 10 bpc output (NVIDIA Control Panel > Change resolution > Output color depth).
   Without that the desktop link is 8-bit and only the dither helps.
3. Pick a backend in Options > Video Settings > Graphics API, or launch with `--graphicsBackend vulkan`
   or `--graphicsBackend opengl`.

To build it yourself you need a JDK 25, because Loom 1.19 runs Gradle itself on Java 25. If none is
installed, Gradle downloads one (see `gradle/gradle-daemon-jvm.properties`):

```
./gradlew build
```

The jar ends up in `build/libs/`.

## Configuration

With Sodium installed: Video Settings, then **Deep Color** in the sidebar, with a Dither page and an
Output page. With Mod Menu: Mods, Deep Color, configure. Both edit `config/deepcolor.json`.

| Key | Values | Default | Applies |
| --- | --- | --- | --- |
| `dither` | `true`, `false` | `true` | immediately |
| `ditherStrength` | `0.0` to `2.0`, in LSB | `1.0` | immediately |
| `temporal` | `true`, `false` | `true` | immediately |
| `ditherBits` | `auto`, `8`, `10` | `auto` | immediately |
| `noisePattern` | `blue`, `gradient` | `blue` | immediately |
| `outputBits` | `auto`, `8`, `10` | `auto` | after restart |
| `internalFormat` | `RGBA16_FLOAT`, `RGBA16_UNORM`, `RGB10A2_UNORM`, `RGBA8_UNORM` | `RGBA16_FLOAT` | after restart |

`outputBits`: `auto` and `10` behave the same. Both request 10 bits and fall back to what the driver
gives.

`ditherBits` is the depth the noise is made for. It never exceeds what the surface really has.

- `auto` uses 10 bits in exclusive fullscreen and 8 bits in a window or borderless window. With HDR
  off, Windows composites windowed games to 8 bits, which throws 10-bit noise away but keeps 8-bit
  noise. In 8-bit mode the output codes are chosen so the compositor maps them back to exactly the
  level the dither picked, whether it rounds or truncates.
- `10` forces 10-bit noise. Use it if your setup keeps 10 bits in a window, for example with HDR on.

Tuning the grain:

- Some fine grain where a dark gradient brightens is inherent: near black one code value is a large
  relative brightness step, and that step is exactly what the noise spreads out.
- `noisePattern` `blue` (the default) puts the grain at the finest spatial scale. Modelled on a dark
  ramp it is about a third less visible than the `gradient` pattern of version 1.0.
- `temporal` on lets your eye average the grain over several frames. At 360 fps the visible grain is
  about a quarter of the static amount. Off gives a still pattern, which some prefer at low frame rates.
- Lowering `ditherStrength` below 1.0 reduces grain but lets some banding back in.

Which internal format?

- `RGBA16_FLOAT` keeps most precision in the darks. Use this unless something looks wrong.
- `RGBA16_UNORM` clamps to 0-1 exactly like vanilla, which helps if a mod relies on that clamping.
- `RGB10A2_UNORM` is the lightest, but has only 2 bits of alpha in the main target.
- `RGBA8_UNORM` keeps vanilla targets and leaves only the output and dither features.

## macOS

- **Use the Vulkan backend** (`--graphicsBackend vulkan`, which runs on Metal through MoltenVK) for
  10-bit output and HDR. macOS OpenGL offers no 10-bit window formats, so on OpenGL the mod dithers for
  8 bits and logs that once.
- `ditherBits` `auto` dithers for 8 bits, since macOS has no exclusive fullscreen. If your display keeps
  10 bits through the compositor, try `10` and compare with the gradient screen.
- **HDR output works on Vulkan only.** It uses an FP16 extended linear sRGB swapchain, which macOS shows
  with EDR. OpenGL stays SDR, because a float OpenGL window gets no EDR there.
- macOS has no absolute nits: 1.0 on the surface is SDR white at whatever brightness is set. Deep Color
  counts SDR white as 203 nits (BT.2408 reference white), so the default `hdrUiNits` of 203 puts menus
  exactly at SDR white, and every nits setting is relative to that. `hdrPeakNits` `0` follows the
  current EDR headroom, which macOS raises once the game shows HDR content and lowers as the display
  brightness goes up.

## Windows HDR output

Off by default. Turn it on in Sodium (Deep Color, HDR page) or in `config/deepcolor.json`
(`"hdrOutput": "on"`), then restart. It only does something while Windows HDR is on.

What it does:

- The game hands Windows a 16-bit float scRGB image, in which every value is an absolute brightness.
  The game sets brightness in nits itself, so the Windows "SDR content brightness" slider no longer
  affects it.
- Shadows use **gamma 2.2**, like a monitor in SDR mode. Windows decodes ordinary SDR apps with the sRGB
  curve in HDR mode, which lifts the shadows and is the washed-out look. That does not happen here.
- The world and the interface are separated. Just before the interface is drawn, the finished world is
  expanded into the range above interface white, so the world can reach the monitor's peak while menus
  and text stay at a comfortable level.

| Key | Values | Default |
| --- | --- | --- |
| `hdrOutput` | `off`, `on` (restart) | `off` |
| `hdrUiNits` | `40` to `600`, or `0` for the Windows slider value | `203` (BT.2408 reference white) |
| `hdrPeakNits` | `200` to `2000`, or `0` for the monitor peak Windows reports | `0` |
| `hdrMapping` | `highlights`, `linear`, `none` | `highlights` |
| `hdrGamma` | `1.8` to `3.0`, or `0` for the sRGB curve Windows uses | `2.2` |
| `hdrShadowFloor` | `0` (off) to `0.2` nits | `0` |
| `hdrGamutStretch` | `0` (accurate sRGB) to `1` (fully stretched to the target) | `0` |
| `hdrGamutTarget` | `p3`, `adobe`, `bt2020` | `p3` |

Brightness mappings:

- `highlights` scales by luminance: bright things (sky, sun, light sources) move toward the peak and
  shadows do not move. However far the peak is above interface white, a gray at half brightness gains
  at most 15%. Hues are kept.
- `linear` scales the whole world image evenly, shadows included. With a 1000-nit peak and a 203-nit
  interface that is about 5x brighter everywhere, which reads as washed out in dark scenes.
- `none` keeps the world at interface brightness, only fixing the shadow curve.

For a dark, contrasty look: `highlights`, interface brightness close to your normal SDR brightness
(often 80 to 120 nits), and an `hdrGamma` of 2.4 to 2.6 for deeper shadows. The gamma slider applies
immediately, so it can be tuned while looking at a dark scene.

**Matching your SDR look.** Two things make HDR look washed out next to SDR. First brightness: a
monitor's SDR white can be well below 100 nits, so lower `hdrUiNits` until white text matches what you
see with Windows HDR off. Second color: many monitors' default SDR mode stretches the game's sRGB
colors to the panel's wide native gamut, while Windows HDR shows them accurately. `hdrGamutStretch`
recreates that stretch toward `hdrGamutTarget` without touching grays. Display P3 is close to a
QD-OLED's native gamut. Adobe RGB widens greens and cyans. BT.2020 is the widest; a panel like the
AW2725DF covers about 82% of it, so the most saturated colors are clipped by the panel at full
stretch. Lower the stretch to keep them in range.

**Shadows that clip to black.** OLED panels in HDR mode turn very dark levels into pure black, so a
dark gradient can end in a hard edge, especially with a high gamma and a low interface brightness.
With Windows HDR on, `/deepcolor gradient` shows a near-black pattern in absolute nits instead of the
SDR strips: a log ramp from 0.0001 to 1 nit, patches from 0.001 to 0.2 nits, and the game's own
darkest shades. Set `hdrShadowFloor` to about the dimmest patch you can still see. It lifts only the
darkest shadows to that level (applied to luminance, so colors keep their hue); true black stays
black, and anything well above the floor gains only the floor itself.

The peak Windows reports comes from the Windows HDR Calibration app or the monitor itself. On the
AW2725DF it reports 1000 nits.

Notes:

- The OpenGL backend gets a floating-point window from the NVIDIA driver as well, so both backends work.
  Iris shader packs work too: their output is expanded like the vanilla world.
- Float output is not dithered and needs no dither. Windows sends it to the monitor as 10-bit PQ.
- Turning Windows HDR off while playing switches to a plain SDR image on the next frame. Turning it on
  needs a restart, because the surface type is chosen at startup.
- Screenshots stay SDR. Expanded highlights are clipped to white in them.
- The F3 line shows the brightness in use, and the log lists what Windows reports whenever it changes.

## How to verify 10-bit is actually active

Software can prove the image reaches the surface at 10 bits. It cannot see what the panel receives, so
the last step is your eyes.

**1. Status.** Press F3, or run `/deepcolor status`:

```
Deep Color: Vulkan | surface A2B10G10R10_UNORM_PACK32 sRGB | output 10-bit | main RGBA16_FLOAT | dither TPDF 1.00 LSB temporal | exclusive fullscreen
Deep Color: OpenGL | surface GL default framebuffer R10G10B10A2 | output 10-bit | main RGBA16_FLOAT | dither TPDF 1.00 LSB temporal | windowed/borderless
```

The log has the same line (`Output active:`). On Vulkan it also lists every surface format the driver
offered. On OpenGL, a driver that silently gives 8 bits produces a warning and `output 8-bit`.

**2. Measure.** Run `/deepcolor gradient` (or use the button on the Sodium or Mod Menu page) and press
`P`. This counts the distinct levels really stored in the output strip of the ramp from RGB 0 to 32:

- about 129 levels: 10-bit.
- 33 levels: 8-bit.

On OpenGL it reports both the dither target and the actual back buffer. Vulkan swapchain images cannot
be read back, so there it measures the dither target, the last stop before the swapchain.

**3. Look.** Use the gradient in a dark room, because this is the only test that includes Windows, the
driver and the monitor. Keys `1` to `4` show one strip full screen, `0` shows all four.

| Strip | Expected on a working 10-bit chain |
| --- | --- |
| 8-bit, no dither | wide, clearly visible bands, the vanilla look |
| 8-bit, dithered | no bands, slight fine grain |
| Output depth, no dither | bands about four times narrower |
| Active dither | smooth, the depth in the label is what the game uses right now |

**If strip 3 looks as banded as strip 1, the 10 bits are being lost after the game**, usually by the
desktop compositor. That is the normal case in a window with HDR off, and the reason `ditherBits` auto
switches to 8-bit noise there. Exclusive fullscreen keeps the 10 bits: F3 then ends with
`exclusive fullscreen` and the dither depth shows 10-bit.

## Known limitations

- **Windows composites windowed games to 8 bits** while HDR is off, even on a 10-bit surface. Only
  exclusive fullscreen gets real 10-bit output. `ditherBits` auto handles this by dithering for 8 bits
  in a window, which looks nearly as smooth.
- **Iris 1.11.7 needs Sodium 0.9.3-beta.1.** With Sodium 0.9.3-beta.2 or beta.3, Iris shader packs move
  the camera in whole-block steps and drop faces, with or without Deep Color (the test matrix checks
  this with quarter-block camera moves). Use Sodium 0.9.3-beta.1 with Iris until Iris is updated.
- **Iris shader packs only run on OpenGL.** Iris itself refuses to run shaders on Vulkan. With Iris
  installed on Vulkan the game runs normally without shaders, and Deep Color keeps working.
- **Shader packs still decide their own internal precision.** Deep Color dithers what the pack outputs,
  and cannot restore detail a pack already lost in an 8-bit `colortex`.
- **Textures stay 8-bit.** Extra precision comes from lighting, fog and blending math on top of them.
- **F2 screenshots are 8-bit PNGs.** Upgraded targets are flattened to RGBA8 for screenshots, world icons
  and huge screenshots, with the dither applied at 8 bits. A screenshot of the gradient screen shows only
  the labels, since the gradient exists only in the final pass. Screenshots do not show 10-bit quality.
- **First use of a pipeline on Vulkan.** A pipeline drawing to a target that was not predicted, such as
  the RGBA8 GUI item atlas, compiles a variant the first time, which can cause a very short hitch.
- **Other mods reading the main target directly** may assume RGBA8. The vanilla screenshot path is
  handled, anything custom is not.
- **rrtt217's HDR Mod:** if it is installed, Deep Color disables itself entirely and logs why.
- If an injection fails on a future version, it is logged and the feature using it turns off. If a hook
  that upgraded targets depend on is missing, targets stay RGBA8 rather than risking a crash.

## How it works

Pipelines carry a color format, and `FrontendRenderPass.setPipeline` rejects a mismatch with the
attachment. The reference mod handles this at each hardcoded call site. Deep Color handles it in one
place, `PipelineRouter`, which also covers pipelines from any other mod:

- On OpenGL the format is only a validation check, so the record is copied with the right format and
  shares the same GL program. No recompile, and Iris program overrides are left alone.
- On Vulkan the format is baked into the VkPipeline. `PipelineBuilder.compilePipeline` compiles everything
  for the internal format up front, and anything that lands elsewhere gets a real variant.

The dither target uses the true output format, so writing to it is the only quantization step. An
RGBA16_FLOAT target would be worse: near white, half floats only resolve about half a 10-bit step.

Noise is a 64x64 blue noise map, generated at startup with Ulichney's void-and-cluster method (about
40 ms), remapped to a triangular distribution. Each frame adds the golden ratio to the noise value, so
every pixel walks evenly through its range and a few frames average out to the true level. Offsetting
the position instead, as version 1.0 did, made some frame pairs nearly repeat. The pass owns its own
shader source and pipeline cache because the first frames are presented before resource packs load.

## Development

The client gametest drives the real game and reads real output pixels. The fastest way to run all of
it is the parallel runner, which starts 8 game instances at once by default (`-j` to change). Each
needs about 2.5 GB of memory and 0.3 GB of GPU memory:

```
python tools/parallel_gametest.py --pack C:/path/to/ComplementaryReimagined.zip
python tools/parallel_gametest.py -j 2 gl vk-sodium
```

Jobs are listed in `tools/test-matrix.json`. Results go to `run/parallel/<job>/`. `--repeat N` runs
each selected job N times, to catch rare failures. A run that hangs gets a thread dump
(`hang-threads.txt`) before it is stopped.

Fabric's client gametest harness (fabric-client-gametest-api-v1 6.0.12) has a startup race: the test
thread can register with its Phaser after the game has already moved on, and both then wait for each
other forever. It hit about 1 run in 8 when several instances loaded at once. The test mod fixes it
with a mixin (`ThreadingImplMixin`, test source set only) that registers before the thread starts.
After the fix, 64 runs at 8 instances had no hangs. The runner still retries a run once if it ever
hangs before the test starts, and says so in its report. Gradle cannot run
tests side by side itself, so the script asks it once per mod set for the exact launch command
(`writeGameTestLaunch`) and starts the instances directly.

Single runs through Gradle still work:

```
./gradlew runClientGameTest -Pdc.backend=vulkan
./gradlew runClientGameTest -Pdc.backend=opengl -Pdc.sodium
./gradlew runClientGameTest -Pdc.backend=opengl -Pdc.iris -Pdc.pack=C:/path/to/ComplementaryReimagined.zip
./gradlew runClientGameTest -Pdc.backend=vulkan '-Pdc.config={"outputBits":"8"}'
```

Each combination has its own directory under `run/`, with results in `deepcolor-test-result.txt`.
`-Pdc.fakeHdr` loads a stand-in mod with the id `hdr_mod`, to test the disable path.

The HDR jobs work with Windows HDR off too: `-Ddeepcolor.test.forceScrgb=true` picks the scRGB surface
regardless, and `-Ddeepcolor.test.assumeHdr=true` encodes as if HDR were on. The `-real` jobs use no
overrides and only take the HDR path when Windows HDR really is on.

`TestInput.resizeWindow` only changes the size the game believes it has. On Vulkan that makes vanilla
blit out of bounds and lose the device, with or without this mod, so the test only uses it on OpenGL.
Test real resizes with `runClient`.

## Credits

Built on techniques from [Minecraft-HDR-Mod](https://github.com/rrtt217/Minecraft-HDR-Mod) (branch 26.3)
by rrtt217, MIT license. It solved the hard parts first: upgrading the main target, retargeting pipeline
formats, choosing the Vulkan swapchain format and hooking the final blit. Its copyright notice:

> Copyright (c) 2026 rrtt217. Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal in the Software without
> restriction, under the terms of the MIT License.

Blue noise by the void-and-cluster method of Robert Ulichney, *The void-and-cluster method for dither
array generation* (1993). Interleaved gradient noise by Jorge Jimenez, from *Next Generation Post
Processing in Call of Duty: Advanced Warfare* (2014). Mod template from
[fabric-example-mod](https://github.com/FabricMC/fabric-example-mod).

Licensed under MIT, see `LICENSE`.
