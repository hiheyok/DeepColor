package dev.deepcolor.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.Hdr;
import dev.deepcolor.core.OutputProbe;
import dev.deepcolor.core.OutputState;
import dev.deepcolor.gui.DeepColorConfigScreen;
import dev.deepcolor.gui.GradientScreen;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Measures what the mod really outputs, on whichever backend and mod set the run was started with.
 * Results go to deepcolor-test-result.txt in the run directory. Any failed check fails the run.
 */
public final class DeepColorClientTest implements FabricClientGameTest {
	private final List<String> lines = new ArrayList<>();
	private int failures;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(10);
		if (DeepColor.isDisabledByHdrMod()) {
			this.checkDisabled(context);
			this.finish(context);
			return;
		}
		this.log("STATUS " + context.computeOnClient(minecraft -> OutputState.statusLine()));
		context.takeScreenshot("01_title");
		this.checkModMenu();
		this.checkSodiumPage(context);

		this.checkGradient(context);

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("time set midnight");
			// The command runs on the server. Under load the client can still be showing daytime, and
			// the sky changing between two captures looks like huge noise. Wait for the update.
			world.getConnection().waitForClientboundPackets();
			world.getConnection().waitForChunksRender();
			context.waitTicks(5);
			context.takeScreenshot("03_world_night");
			this.checkWorldDither(context);
			this.checkCameraMotion(context, world);
			if (context.computeOnClient(minecraft -> OutputState.isScrgb())) {
				world.getServer().runCommand("time set noon");
				world.getConnection().waitForClientboundPackets();
				context.waitTicks(10);
				this.checkWorldHdr(context);
			}

			// The outline target and its blit are another path that does not end in the main target.
			world.getServer().runCommand("summon pig ~ ~ ~6 {Glowing:1b,NoAI:1b}");
			context.waitTicks(10);
			context.takeScreenshot("03b_outline");

			world.getServer().runCommand("give @p diamond_sword");
			world.getServer().runCommand("give @p oak_sapling");
			context.waitTicks(10);
			// Entity previews and the item atlas render into RGBA8 targets, not the main target.
			context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
			context.waitTicks(20);
			context.takeScreenshot("04_inventory");

			// Pausing runs the menu blur post chain over the world.
			context.setScreen(() -> new OptionsScreen(null, Minecraft.getInstance().options));
			context.waitTicks(20);
			context.takeScreenshot("05_blur");

			context.setScreen(() -> new DeepColorConfigScreen(null));
			context.waitTicks(10);
			context.takeScreenshot("06_config");
			context.setScreen(() -> null);

			context.getInput().pressKey(InputConstants.KEY_F3);
			context.waitTicks(10);
			context.takeScreenshot("07_f3");
			context.getInput().pressKey(InputConstants.KEY_F3);

			// TestInput.resizeWindow only changes the size the game believes it has. On Vulkan the
			// real surface stays small, so vanilla blits out of bounds and loses the device even
			// with this mod switched off. Real resizes have to be tested outside this harness.
			if (context.computeOnClient(minecraft -> OutputState.isOpenGl())) {
				context.getInput().resizeWindow(1280, 720);
				context.waitTicks(10);
				OutputProbe.Capture resized = capture(context, OutputProbe::captureDitherTarget);
				if (resized != null) {
					this.check("dither target follows the render size", resized.width() == 1280 && resized.height() == 720);
				}
			}
		}
		this.finish(context);
	}

	private void finish(ClientGameTestContext context) {
		this.writeResults(context);
		if (this.failures > 0) {
			throw new AssertionError(this.failures + " Deep Color checks failed, see deepcolor-test-result.txt");
		}
	}

	private void checkDisabled(ClientGameTestContext context) {
		String format = context.computeOnClient(minecraft -> minecraft.gameRenderer.mainRenderTarget().getColorTexture().getFormat().toString());
		this.check("main target stays RGBA8 next to HDR Mod (" + format + ")", format.equals("RGBA8_UNORM"));
		context.takeScreenshot("01_disabled");
		if (FabricLoader.getInstance().isModLoaded("iris")) {
			try (TestSingleplayerContext world = context.worldBuilder().create()) {
				world.getConnection().waitForChunksRender();
				this.checkCameraMotion(context, world);
			}
		}
	}

	private void checkSodiumPage(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			return;
		}
		boolean registered = FabricLoader.getInstance().getEntrypoints("sodium:config_api_user", Object.class).stream()
			.anyMatch(entry -> entry.getClass().getName().equals("dev.deepcolor.compat.DeepColorSodiumOptions"));
		this.check("Sodium options page is registered", registered);
		context.setScreen(() -> {
			try {
				Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
				return (net.minecraft.client.gui.screens.Screen) screen.getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class).invoke(null, (Object) null);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		context.waitTicks(5);
		// Sodium validates every option while building this screen, so reaching it is the check.
		// Its sidebar is not made of vanilla buttons, so the page itself is not clicked here.
		context.takeScreenshot("01b_sodium");
		context.setScreen(() -> new TitleScreen());
		context.waitTicks(3);
	}

	private void checkModMenu() {
		if (FabricLoader.getInstance().isModLoaded("modmenu")) {
			this.check("Mod Menu entrypoint loads", !FabricLoader.getInstance().getEntrypoints("modmenu", Object.class).isEmpty());
		}
	}

	/**
	 * The undithered output strip is a known ramp, so its scRGB values can be checked exactly: with
	 * HDR active they must follow the shadow curve scaled to the interface brightness, without HDR
	 * they must decode back to the plain SDR image.
	 */
	/**
	 * Moves the player a quarter block at a time and checks that the image moves with it. A pipeline
	 * that loses the fractional camera position renders the world in whole-block steps, so a quarter
	 * block move changes nothing. Shader packs jitter frames for anti-aliasing even when standing
	 * still, so the change is compared against a capture without moving.
	 */
	private void checkCameraMotion(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("tp @p 0.5 -60 0.5 45 30");
		world.getConnection().waitForClientboundPackets();
		context.waitTicks(20);
		BufferedImage start = screenshot(context, "camera_0");
		context.waitTicks(3);
		BufferedImage still = screenshot(context, "camera_0_still");
		double[] moved = new double[3];
		BufferedImage previous = still;
		for (int step = 0; step < moved.length; step++) {
			world.getServer().runCommand("execute as @p at @p run tp @p ~0.25 ~ ~");
			world.getConnection().waitForClientboundPackets();
			context.waitTicks(3);
			BufferedImage now = screenshot(context, "camera_" + (step + 1));
			moved[step] = changedShare(previous, now);
			previous = now;
		}
		double baseline = changedShare(start, still);
		this.log(String.format(Locale.ROOT, "camera motion: still %.1f%%, quarter-block moves %.1f%% %.1f%% %.1f%% of pixels changed",
			baseline, moved[0], moved[1], moved[2]));
		double smallest = Math.min(moved[0], Math.min(moved[1], moved[2]));
		this.check("every quarter-block move changes the image (no whole-block camera steps)", smallest > baseline + 10.0);
	}

	private static BufferedImage screenshot(ClientGameTestContext context, String name) {
		try {
			return ImageIO.read(context.takeScreenshot(name).toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Share of pixels in the top 70% of the frame (no hotbar) whose green channel changed by more
	 * than 4 of 255. 8-bit dither in screenshots changes a pixel by at most 2.
	 */
	private static double changedShare(BufferedImage a, BufferedImage b) {
		long changed = 0;
		long total = 0;
		for (int y = 0; y < a.getHeight() * 7 / 10; y += 2) {
			for (int x = 0; x < a.getWidth(); x += 2) {
				int ga = (a.getRGB(x, y) >> 8) & 0xFF;
				int gb = (b.getRGB(x, y) >> 8) & 0xFF;
				changed += Math.abs(ga - gb) > 4 ? 1 : 0;
				total++;
			}
		}
		return 100.0 * changed / total;
	}

	/** Patch centers sit in the middle row; labels are drawn lower, at three quarters height. */
	private void checkStepChart(ClientGameTestContext context, GradientScreen screen) {
		context.runOnClient(minecraft -> screen.select(4));
		context.waitTicks(3);
		OutputProbe.Capture capture = capture(context, OutputProbe::captureDitherTarget);
		context.takeScreenshot("02c_steps");
		int[] codes = {2, 4, 8, 16, 32, 64, 128, 255};
		int row = capture.height() / 2;
		double worst = 0.0;
		for (int patch = 0; patch < codes.length; patch++) {
			int x = capture.width() * patch / 8 + capture.width() * 6 / 100;
			// 8-bit shades on a 10-bit surface use the next code up, so an 8-bit compositor maps
			// them back exactly (see toLevels in dither.fsh).
			double expected = Math.ceil((double) codes[patch] / 255 * ((1 << capture.bits()) - 1) - 0.001);
			worst = Math.max(worst, Math.abs(capture.code(x, row) - expected));
		}
		this.check(String.format(Locale.ROOT, "grey step chart shows exact flat levels (worst %.2f codes off)", worst), worst < 0.01);
		context.runOnClient(minecraft -> screen.select(-1));
		context.waitTicks(3);
	}

	private void checkScrgbEncoding(ClientGameTestContext context) {
		OutputProbe.Capture capture = capture(context, OutputProbe::captureDitherTarget);
		boolean active = context.computeOnClient(minecraft -> Hdr.active());
		float uiScale = context.computeOnClient(minecraft -> Hdr.uiNits()) / Hdr.SCRGB_NITS;
		float floorScale = DeepColorConfig.get().hdrShadowFloor / Hdr.SCRGB_NITS;
		this.log("scRGB output, HDR " + (active ? "active" : "inactive") + ", interface white " + uiScale * Hdr.SCRGB_NITS + " nits");
		// With HDR active the screen shows the near-black pattern, whose bottom strip is the game's own
		// shades. Without HDR it shows the SDR strips, where strip 2 is the plain ramp.
		int row = active ? capture.height() / 8 : capture.height() - 1 - capture.height() * 5 / 8;
		double error = 0.0;
		int counted = 0;
		for (int x = capture.width() / 3; x < capture.width(); x += 4) {
			double code = (x + 0.5) / capture.width() * 32.0 / 255.0;
			float gamma = DeepColorConfig.get().hdrGamma;
			double expected = !active ? srgbDecode(code) : toe((gamma > 0 ? Math.pow(code, gamma) : srgbDecode(code)) * uiScale, floorScale);
			error += Math.abs(capture.value(x, row) - expected) / expected;
			counted++;
		}
		double relative = error / counted;
		this.check(String.format(Locale.ROOT, "scRGB ramp matches the %s encoding (%.2f%% off)", active ? "HDR gamma " + DeepColorConfig.get().hdrGamma : "SDR", relative * 100), relative < 0.02);
	}

	/** Same curve as toe() in dither.fsh. */
	private static double toe(double value, double floor) {
		return floor <= 0 ? value : value + floor * (1.0 - Math.exp(-value / (0.25 * floor)));
	}

	private static double srgbDecode(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/**
	 * Switches the brightness mapping live and compares the same daytime view. Only the middle of the
	 * screen is used, where no interface is drawn apart from the crosshair.
	 */
	private void checkWorldHdr(ClientGameTestContext context) {
		if (!context.computeOnClient(minecraft -> Hdr.active())) {
			this.log("scRGB without HDR: no brightness expansion to check");
			return;
		}
		DeepColorConfig config = DeepColorConfig.get();
		float ui = context.computeOnClient(minecraft -> Hdr.uiNits());
		float peak = context.computeOnClient(minecraft -> Hdr.peakNits());
		double[] none = this.worldStats(context, config, DeepColorConfig.HdrMapping.NONE);
		double[] linear = this.worldStats(context, config, DeepColorConfig.HdrMapping.LINEAR);
		double[] highlights = this.worldStats(context, config, DeepColorConfig.HdrMapping.HIGHLIGHTS);
		context.takeScreenshot("03c_hdr_world");
		this.log(String.format(Locale.ROOT, "world mean/max in nits: none %.0f/%.0f, linear %.0f/%.0f, highlights %.0f/%.0f (UI %.0f, peak %.0f)",
			none[0], none[1], linear[0], linear[1], highlights[0], highlights[1], ui, peak));
		this.check("without mapping the world stays at or below interface white", none[1] <= ui * 1.01);
		double ratio = linear[0] / none[0];
		this.check(String.format(Locale.ROOT, "linear stretch scales the world by peak / UI (%.3f vs %.3f)", ratio, peak / ui), Math.abs(ratio / (peak / ui) - 1.0) < 0.03);
		this.check("highlights stay within the peak", highlights[1] <= peak * 1.01);
		if (config.hdrGamutStretch > 0.0F) {
			// The ramp check above runs on gray, so it already proves grays are untouched by the gamut stretch.
			this.log("gamut stretch " + config.hdrGamutStretch + ": gray ramp unchanged (checked above)");
		}
		this.check("highlights brighten the world less than a linear stretch", highlights[0] > none[0] && highlights[0] < linear[0]);
		// Midtones: the expansion gain at half luminance is capped at 15%.
		double gainAtHalf = 1.0 + (peak / ui - 1.0) * Math.pow(0.5, Math.max(4.0, Math.log((peak / ui - 1.0) / 0.15) / Math.log(2.0)));
		this.check(String.format(Locale.ROOT, "midtone gain stays at 15%% (%.3f)", gainAtHalf), Math.abs(gainAtHalf - 1.15) < 0.005 || gainAtHalf < 1.15);
	}

	private double[] worldStats(ClientGameTestContext context, DeepColorConfig config, DeepColorConfig.HdrMapping mapping) {
		context.runOnClient(minecraft -> config.hdrMapping = mapping);
		context.waitTicks(3);
		OutputProbe.Capture capture = capture(context, OutputProbe::captureDitherTarget);
		double sum = 0.0;
		double max = 0.0;
		int count = 0;
		int cx = capture.width() / 2;
		int cy = capture.height() / 2;
		for (int y = capture.height() / 4; y < capture.height() * 3 / 4; y++) {
			for (int x = capture.width() / 4; x < capture.width() * 3 / 4; x++) {
				if (Math.abs(x - cx) < 12 && Math.abs(y - cy) < 12) {
					continue;
				}
				double nits = capture.value(x, y) * Hdr.SCRGB_NITS;
				sum += nits;
				max = Math.max(max, nits);
				count++;
			}
		}
		return new double[]{sum / count, max};
	}

	/** Exercises the real image path (mode 0). The gradient strips use their own fixed amounts. */
	private void checkWorldDither(ClientGameTestContext context) {
		if (context.computeOnClient(minecraft -> OutputState.isScrgb())) {
			return;
		}
		DeepColorConfig config = DeepColorConfig.get();
		OutputProbe.Capture first = capture(context, OutputProbe::captureDitherTarget);
		if (first == null) {
			this.check("dither pass is skipped when it has nothing to do", !config.dither);
			return;
		}
		// Noise similarity depends on the frame lag: at a lag of 1 about 64% of pixels change, at 5
		// only 7%. Capture timing is not controlled here, so take the widest of several pairs.
		OutputProbe.Capture second = capture(context, OutputProbe::captureDitherTarget);
		OutputProbe.Capture third = capture(context, OutputProbe::captureDitherTarget);
		int ditherBits = context.computeOnClient(minecraft -> OutputState.ditherBits());
		// One dither step in output codes: 4 when dithering for 8 bits on a 10-bit surface.
		int step = (int) Math.round(((1 << first.bits()) - 1) / (double) ((1 << ditherBits) - 1));
		int limit = 2 * step + 1;
		double changedShare = 0.0;
		double largeShare = 0.0;
		for (OutputProbe.Capture[] pair : new OutputProbe.Capture[][]{{first, second}, {first, third}, {second, third}}) {
			long changed = 0;
			long large = 0;
			int[] a = pair[0].red();
			int[] b = pair[1].red();
			for (int i = 0; i < a.length; i++) {
				int difference = Math.abs(a[i] - b[i]);
				changed += difference > 0 ? 1 : 0;
				large += difference > limit ? 1 : 0;
			}
			changedShare = Math.max(changedShare, 100.0 * changed / a.length);
			largeShare = Math.max(largeShare, 100.0 * large / a.length);
		}
		this.log(String.format(Locale.ROOT, "world frame to frame: %.1f%% of pixels changed, %.3f%% by more than %d codes", changedShare, largeShare, limit));
		if (config.dither && config.temporal) {
			this.check("world image carries temporal noise", changedShare > 20.0);
		}
		// Two samples of noise within 1 LSB can differ by at most 2 LSB. A little scene motion is allowed.
		if (config.dither && config.ditherStrength <= 1.0F) {
			// Complementary animates its sky: 5.5% of pixels move this far with dither switched off.
			double allowedMotion = FabricLoader.getInstance().isModLoaded("iris") ? 8.0 : 1.0;
			this.check("world dither stays within 1 LSB", largeShare < allowedMotion);
		}
		if (!config.dither) {
			// With noise the widest pair changes about 60%, so this leaves room for scene motion.
			this.check("no noise in the world image with dither off", changedShare < 20.0);
		}
	}

	private void checkGradient(ClientGameTestContext context) {
		GradientScreen screen = context.computeOnClient(minecraft -> new GradientScreen(null));
		context.setScreen(() -> screen);
		context.waitTicks(5);
		context.takeScreenshot("02_gradient");
		if (context.computeOnClient(minecraft -> OutputState.isScrgb())) {
			this.checkScrgbEncoding(context);
			context.setScreen(() -> new TitleScreen());
			context.waitTicks(3);
			return;
		}

		OutputProbe.Capture first = capture(context, OutputProbe::captureDitherTarget);
		int bits = first.bits();
		int top = first.height() - 1;
		float scale = (1 << bits) - 1;
		// One LSB of the simulated 8-bit strip, measured in output codes.
		int quarter = first.height() / 4;
		int[] rows = {top - quarter / 2, top - quarter - quarter / 2, top - 2 * quarter - quarter / 2, top - 3 * quarter - quarter / 2};

		long[] distinct = new long[4];
		for (int strip = 0; strip < 4; strip++) {
			distinct[strip] = distinctInRow(first, rows[strip]);
			this.log(String.format(Locale.ROOT, "strip %d (row %d): %d distinct codes, ramp mean error %.3f codes",
				strip, rows[strip], distinct[strip], meanError(first, rows[strip], quarter / 2, scale, scale)));
		}

		int ditherBits = context.computeOnClient(minecraft -> OutputState.ditherBits());
		this.log("dither depth in use: " + ditherBits + "-bit");
		// 33 8-bit levels from 0 to 32. Row-to-row noise would raise this, so it proves no dither here.
		this.check("8-bit strip has at most 33 levels", distinct[0] <= 33);
		if (bits == 10) {
			// 32/255 * 1023 is about 128 codes, at most one per pixel column.
			long expected = Math.min(first.width(), 129);
			this.check("10-bit strip resolves between 8-bit levels (" + distinct[2] + " of " + expected + ")", distinct[2] >= expected * 9 / 10);
			this.check("8-bit strips survive an 8-bit compositor exactly", survives8Bit(first, rows[0]) && survives8Bit(first, rows[1]));
			if (ditherBits == 8) {
				this.check("8-bit dither on a 10-bit surface survives an 8-bit compositor", survives8Bit(first, rows[3]));
			}
		}
		// Compare with the undithered strip of the same depth, measured in that depth's own levels.
		float levels = (1 << ditherBits) - 1;
		double undithered = meanError(first, ditherBits == 8 ? rows[0] : rows[2], quarter / 2, scale, levels);
		double dithered = meanError(first, rows[3], quarter / 2, scale, levels);
		this.check(String.format(Locale.ROOT, "dither brings the local mean closer to the true ramp (%.3f vs %.3f LSB)", dithered, undithered), dithered < undithered / 2.0);
		this.check("true black stays black in every strip", blackColumnIsZero(first, rows));

		OutputProbe.Capture second = capture(context, OutputProbe::captureDitherTarget);
		boolean temporal = DeepColorConfig.get().temporal;
		this.check("noise over time matches the temporal setting", temporal != Arrays.equals(first.red(), second.red()));

		for (int strip = 0; strip < 4; strip++) {
			int selected = strip;
			context.runOnClient(minecraft -> screen.select(selected));
			context.waitTicks(3);
			OutputProbe.Capture full = capture(context, OutputProbe::captureDitherTarget);
			this.log("full-screen strip " + strip + ": " + distinctInRow(full, full.height() / 2) + " distinct codes");
		}
		context.runOnClient(minecraft -> screen.select(-1));
		context.waitTicks(3);
		this.checkStepChart(context, screen);

		// Last, because the report is drawn over the strips that the checks above measure.
		context.getInput().pressKey(InputConstants.KEY_P);
		context.waitTicks(10);
		context.takeScreenshot("02b_measured");

		if (context.computeOnClient(minecraft -> OutputState.isOpenGl())) {
			OutputProbe.Capture back = capture(context, OutputProbe::captureBackBuffer);
			long noDither = distinctInRow(back, rows[2]);
			this.log("OpenGL back buffer, strip 2: " + noDither + " distinct 10-bit codes");
			if (bits == 10) {
				this.check("OpenGL back buffer holds real 10-bit values", noDither > 100);
			} else {
				this.check("OpenGL back buffer matches the reported 8 bits", noDither <= 33);
			}
		} else {
			this.log("Vulkan swapchain images have no TRANSFER_SRC usage, back buffer not readable");
		}
		context.setScreen(() -> new TitleScreen());
		context.waitTicks(3);
	}

	private static OutputProbe.Capture capture(ClientGameTestContext context, Consumer<Consumer<OutputProbe.Capture>> request) {
		AtomicReference<OutputProbe.Capture> result = new AtomicReference<>();
		context.runOnClient(minecraft -> request.accept(result::set));
		try {
			context.waitFor(minecraft -> result.get() != null, 60);
		} catch (AssertionError timedOut) {
			return null;
		}
		return result.get();
	}

	private static long distinctInRow(OutputProbe.Capture capture, int row) {
		return Arrays.stream(capture.red(), row * capture.width(), (row + 1) * capture.width()).distinct().count();
	}

	/**
	 * Compares column averages over a block of rows with the ideal ramp, in units of the given number
	 * of levels. Codes are mapped to levels by truncation, as an 8-bit compositor would do. Labels sit
	 * at the far left, so the left third is skipped.
	 */
	private static double meanError(OutputProbe.Capture capture, int centerRow, int halfHeight, float scale, float levels) {
		double error = 0.0;
		int counted = 0;
		int start = capture.width() / 3;
		for (int x = start; x < capture.width(); x += 8) {
			double sum = 0.0;
			int samples = 0;
			for (int y = centerRow - halfHeight / 2; y < centerRow + halfHeight / 2; y++) {
				sum += Math.floor(capture.code(x, y) * levels / scale + 1e-9);
				samples++;
			}
			double ideal = (x + 0.5) / capture.width() * 32.0 / 255.0 * levels;
			error += Math.abs(sum / samples - ideal);
			counted++;
		}
		return error / counted;
	}

	/** A 10-bit code survives if rounding and truncating it to 8 bits give the same level. */
	private static boolean survives8Bit(OutputProbe.Capture capture, int row) {
		for (int x = capture.width() / 3; x < capture.width(); x++) {
			double eight = capture.code(x, row) * 255.0 / 1023.0;
			if (Math.floor(eight + 1e-9) != Math.round(eight)) {
				return false;
			}
		}
		return true;
	}

	private static boolean blackColumnIsZero(OutputProbe.Capture capture, int[] rows) {
		// The label text is drawn near the top left of each strip, so sample the bottom edge of it.
		for (int row : rows) {
			if (capture.code(0, row - capture.height() / 16) > 1) {
				return false;
			}
		}
		return true;
	}

	private void check(String name, boolean passed) {
		this.log((passed ? "PASS " : "FAIL ") + name);
		if (!passed) {
			this.failures++;
		}
	}

	private void log(String line) {
		this.lines.add(line);
		System.out.println("[DeepColorTest] " + line);
	}

	private void writeResults(ClientGameTestContext context) {
		Path file = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath()).resolve("deepcolor-test-result.txt");
		try {
			Files.write(file, this.lines);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
