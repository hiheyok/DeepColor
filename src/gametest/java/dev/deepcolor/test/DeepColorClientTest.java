package dev.deepcolor.test;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.DitherPass;
import dev.deepcolor.core.Formats;
import dev.deepcolor.core.GlWindowFormat;
import dev.deepcolor.core.Hdr;
import dev.deepcolor.core.HdrExpandPass;
import dev.deepcolor.core.MacEdr;
import dev.deepcolor.core.OutputProbe;
import dev.deepcolor.core.OutputState;
import dev.deepcolor.core.PipelineRouter;
import dev.deepcolor.gui.DeepColorConfigScreen;
import dev.deepcolor.gui.GradientScreen;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;

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
		if (Boolean.getBoolean("deepcolor.test.failDither")) {
			this.checkDitherFailure(context);
			this.finish(context);
			return;
		}
		this.log("STATUS " + context.computeOnClient(minecraft -> OutputState.statusLine()));
		// The simulated context failure only exists on macOS. Elsewhere the job is a plain forced-HDR run.
		boolean contextFails = Boolean.getBoolean("deepcolor.test.failGlFloatContext") && Hdr.isMacOs();
		if (contextFails) {
			// HDR was asked for, but the float context "failed": the game must have started anyway,
			// on the next format, without EDR (which would show an SDR image far too dark).
			this.check("OpenGL falls back from the float format when its context fails (now " + GlWindowFormat.requested() + ")",
				GlWindowFormat.requested() != GlWindowFormat.Request.FLOAT);
			this.check("the fallback output is SDR", context.computeOnClient(minecraft -> !OutputState.isScrgb()));
		}
		if (Boolean.getBoolean("deepcolor.test.forceScrgb") && !contextFails) {
			this.check("forced HDR output ends up on the float surface", context.computeOnClient(minecraft -> OutputState.isScrgb()));
		}
		if (Hdr.isMacOs() && context.computeOnClient(minecraft -> OutputState.isOpenGl())) {
			// Asked from the game window itself: the float (linear) encoding must be used exactly when
			// the window reads linear light. A mismatch shows an image far too dark (linear values read
			// as gamma) or washed out (the reverse). HDR scaling needs the EDR flag as well, or
			// everything above SDR white would clip.
			boolean linear = context.computeOnClient(minecraft -> MacEdr.hasLinearColorSpace(minecraft.getWindow().handle()));
			boolean edr = context.computeOnClient(minecraft -> MacEdr.isOn(minecraft.getWindow().handle()));
			boolean scrgb = context.computeOnClient(minecraft -> OutputState.isScrgb());
			boolean headroom = context.computeOnClient(minecraft -> OutputState.hasHeadroom());
			this.check("game window color space matches the output (linear " + linear + ", EDR " + edr + ")", linear == scrgb && headroom == (scrgb && edr));
			if (Boolean.getBoolean("deepcolor.test.noEdr") && !contextFails) {
				// The test assumes HDR is on, so only the missing EDR flag can keep it off.
				this.check("without EDR the float surface stays SDR (linear " + linear + ", EDR " + edr + ")",
					scrgb && !edr && !context.computeOnClient(minecraft -> Hdr.active()));
			}
		}
		context.takeScreenshot("01_title");
		this.checkModMenu();
		this.checkSodiumPage(context);

		this.checkGradient(context);
		this.checkEndpoints(context);
		this.checkScreenshotColor(context);
		this.checkReloadReleasesPipelines(context);

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
				this.checkVignetteHdr(context);
				this.checkHdrScreenshots(context);
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
			int lowestWidget = context.computeOnClient(minecraft -> minecraft.gui.screen().children().stream()
				.filter(child -> child instanceof AbstractWidget)
				.mapToInt(child -> ((AbstractWidget) child).getBottom())
				.max().orElse(0));
			int footer = context.computeOnClient(minecraft -> DeepColorConfigScreen.footerY(minecraft.gui.screen().height));
			this.log("config screen: lowest widget ends at " + lowestWidget + ", restart notice starts at " + footer);
			this.check("config screen buttons stay clear of the restart notice", lowestWidget > 0 && lowestWidget <= footer);
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

		// Valid config values between slider steps. Sodium replaces any value its validator rejects
		// with the default while building the screen, so they must survive opening it.
		DeepColorConfig config = DeepColorConfig.get();
		int uiNits = config.hdrUiNits;
		int peakNits = config.hdrPeakNits;
		float gamma = config.hdrGamma;
		float gamut = config.hdrGamutStretch;
		context.runOnClient(minecraft -> {
			config.hdrUiNits = 123;
			config.hdrPeakNits = 333;
			config.hdrGamma = 2.23F;
			config.hdrGamutStretch = 0.33F;
		});
		context.setScreen(() -> {
			try {
				Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
				return (net.minecraft.client.gui.screens.Screen) screen.getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class).invoke(null, (Object) null);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		context.waitTicks(5);
		context.setScreen(() -> new TitleScreen());
		context.waitTicks(3);
		String kept = context.computeOnClient(minecraft -> String.format(Locale.ROOT, "UI %d, peak %d, gamma %.2f, gamut %.2f",
			config.hdrUiNits, config.hdrPeakNits, config.hdrGamma, config.hdrGamutStretch));
		this.check("Sodium page keeps HDR values between slider steps (" + kept + ")", kept.equals("UI 123, peak 333, gamma 2.23, gamut 0.33"));
		context.runOnClient(minecraft -> {
			config.hdrUiNits = uiNits;
			config.hdrPeakNits = peakNits;
			config.hdrGamma = gamma;
			config.hdrGamutStretch = gamut;
			config.save();
		});
	}

	/**
	 * With the dither pass broken, the game must keep running on the plain image, and screenshots
	 * (F2, world icons) must still work instead of hitting the same failure.
	 */
	private void checkDitherFailure(ClientGameTestContext context) {
		context.waitTicks(10);
		this.check("dither pass switched itself off after failing", context.computeOnClient(minecraft -> DitherPass.failed()));
		// A request for the dither target must still be answered, or the gradient screen's measure
		// key (and anything else waiting) would wait forever.
		AtomicReference<String> answer = new AtomicReference<>();
		context.runOnClient(minecraft -> OutputProbe.captureDitherTarget(capture -> answer.set(capture == null ? "none" : "a capture")));
		try {
			context.waitFor(minecraft -> answer.get() != null, 60);
		} catch (AssertionError timedOut) {
			// Reported by the check below.
		}
		this.check("dither target request is answered without a capture (" + answer.get() + ")", "none".equals(answer.get()));
		this.checkScreenshotColor(context);
		this.check("screenshot of the title screen works without the dither pass", hasDetail(screenshot(context, "01_failed_title")));
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.waitTicks(5);
			this.check("screenshot of the world works without the dither pass", hasDetail(screenshot(context, "02_failed_world")));
		}
		this.check("game still running after leaving the world", context.computeOnClient(minecraft -> minecraft.isRunning()));
	}

	/**
	 * Pure black and pure white must come out exact through the real dithered path, at any
	 * strength: noise there shows as a grainy floor or a speckled white.
	 */
	private void checkEndpoints(ClientGameTestContext context) {
		DeepColorConfig config = DeepColorConfig.get();
		if (!config.dither || context.computeOnClient(minecraft -> OutputState.isScrgb())) {
			return;
		}
		context.setScreen(BlackAndWhiteScreen::new);
		context.waitTicks(5);
		OutputProbe.Capture capture = capture(context, OutputProbe::captureDitherTarget);
		int max = (1 << capture.bits()) - 1;
		long wrongBlack = 0;
		long wrongWhite = 0;
		long samples = 0;
		for (int y = capture.height() / 8; y < capture.height() * 7 / 8; y++) {
			for (int x = capture.width() / 8; x < capture.width() * 3 / 8; x++) {
				wrongBlack += capture.code(x, y) != 0 ? 1 : 0;
				wrongWhite += capture.code(capture.width() - 1 - x, y) != max ? 1 : 0;
				samples++;
			}
		}
		this.check(String.format(Locale.ROOT, "pure black and white stay exact at strength %.2f (%d and %d of %d pixels off)",
			config.ditherStrength, wrongBlack, wrongWhite, samples), wrongBlack == 0 && wrongWhite == 0);
		context.setScreen(() -> new TitleScreen());
		context.waitTicks(3);
	}

	/**
	 * Screenshots read a deeper target through an RGBA8 copy. The copy must keep the color of pixels
	 * whose alpha is below 1, as the game's own RGBA8 screenshots do: 0.8 at alpha 0.5 stays 204, not
	 * 102. Runs through the dither pass normally, and through the fallback copy once it has failed.
	 */
	private void checkScreenshotColor(ClientGameTestContext context) {
		AtomicReference<Integer> red = new AtomicReference<>();
		context.runOnClient(minecraft -> {
			TextureTarget target = new TextureTarget("Deep Color test", 64, 64, GpuFormat.RGBA16_FLOAT, null);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), new Vector4f(0.8F, 0.8F, 0.8F, 0.5F));
			Screenshot.takeScreenshot(target, image -> {
				red.set(image.getPixel(32, 32) >> 16 & 0xFF);
				image.close();
				target.destroyBuffers();
			});
		});
		context.waitFor(minecraft -> red.get() != null, 60);
		this.check("screenshot keeps the color of half-transparent pixels (0.8 at alpha 0.5 -> " + red.get() + ", want 204)", Math.abs(red.get() - 204) <= 1);
	}

	/** Left half pure black, right half pure white, nothing else. */
	private static final class BlackAndWhiteScreen extends Screen {
		private BlackAndWhiteScreen() {
			super(Component.empty());
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			graphics.fill(0, 0, this.width / 2, this.height, 0xFF000000);
			graphics.fill(this.width / 2, 0, this.width, this.height, 0xFFFFFFFF);
		}
	}

	/** A real image, as opposed to one solid color from a failed conversion. */
	private static boolean hasDetail(BufferedImage image) {
		if (image == null) {
			return false;
		}
		Set<Integer> colors = new HashSet<>();
		for (int y = 0; y < image.getHeight(); y += 4) {
			for (int x = 0; x < image.getWidth(); x += 4) {
				colors.add(image.getRGB(x, y));
			}
		}
		return colors.size() > 50;
	}

	/** A resource reload closes and recompiles every pipeline. The router must not keep the old ones. */
	private void checkReloadReleasesPipelines(ClientGameTestContext context) {
		int[] closed = new int[3];
		for (int reload = 0; reload < closed.length; reload++) {
			CompletableFuture<Void> done = context.computeOnClient(Minecraft::reloadResourcePacks);
			context.waitFor(minecraft -> done.isDone(), 1200);
			context.waitTicks(5);
			closed[reload] = context.computeOnClient(minecraft -> PipelineRouter.closedPipelinesTracked());
		}
		this.log("closed pipelines still tracked by the router after each reload: " + Arrays.toString(closed));
		this.check("resource reloads do not keep closed pipelines", Arrays.stream(closed).allMatch(count -> count == 0));
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
	 * The HDR brightness expansion must reach the screen but never screenshots, which are 8-bit SDR
	 * images, in the world and with the pause menu open (whose blur reads the main target).
	 * On screen, Linear at 1000/203 nits multiplies the world by about 4.9; behind the pause menu
	 * the menu's dark backdrop covers part of it, so less. In screenshots it must not show at all.
	 */
	private void checkHdrScreenshots(ClientGameTestContext context) {
		if (!context.computeOnClient(minecraft -> Hdr.active())) {
			// Without HDR nothing is expanded, and the comparison would pass without testing anything.
			this.log("HDR not active: no expansion that screenshots could show");
			return;
		}
		DeepColorConfig config = DeepColorConfig.get();
		DeepColorConfig.HdrMapping mapping = config.hdrMapping;
		int peak = config.hdrPeakNits;
		int ui = config.hdrUiNits;
		try {
			context.runOnClient(minecraft -> {
				config.hdrPeakNits = 1000;
				config.hdrUiNits = 203;
			});
			for (boolean paused : new boolean[]{false, true}) {
				int blursBefore = context.computeOnClient(minecraft -> HdrExpandPass.blursKept());
				context.setScreen(() -> paused ? new PauseScreen(true) : null);
				context.waitTicks(5);
				if (paused) {
					// The pause menu must really blur and keep its world, or the gain check below would
					// be measuring something else.
					this.check("pause menu blurs and keeps the world behind it for HDR", context.computeOnClient(minecraft -> HdrExpandPass.blursKept()) > blursBefore);
				}
				String where = paused ? "pause menu" : "world";
				for (DeepColorConfig.HdrMapping tested : new DeepColorConfig.HdrMapping[]{DeepColorConfig.HdrMapping.LINEAR, DeepColorConfig.HdrMapping.HIGHLIGHTS}) {
					// The sky keeps brightening for a while after "time set noon" (8% of pixels change
					// within seconds), so each mapping is compared with an SDR image taken just before.
					String name = (paused ? "paused_" : "") + tested.name().toLowerCase(Locale.ROOT);
					context.runOnClient(minecraft -> config.hdrMapping = DeepColorConfig.HdrMapping.NONE);
					context.waitTicks(3);
					double plainScreen = meanValue(capture(context, OutputProbe::captureDitherTarget));
					BufferedImage plain = screenshot(context, "hdr_shot_none_before_" + name);
					context.runOnClient(minecraft -> config.hdrMapping = tested);
					context.waitTicks(3);
					double expandedScreen = meanValue(capture(context, OutputProbe::captureDitherTarget));
					BufferedImage shot = screenshot(context, "hdr_shot_" + name);
					double changed = changedShare(plain, shot);
					this.check(String.format(Locale.ROOT, "%s screenshot with %s HDR mapping matches the SDR image (%.2f%% of pixels differ)", where, tested, changed), changed < 2.0);
					double gain = expandedScreen / plainScreen;
					if (tested == DeepColorConfig.HdrMapping.LINEAR) {
						this.check(String.format(Locale.ROOT, "%s on screen is expanded by Linear (mean %.2fx)", where, gain), gain > (paused ? 1.5 : 3.0));
					} else {
						this.log(String.format(Locale.ROOT, "%s on screen with Highlights: mean %.2fx", where, gain));
					}
				}
			}
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(minecraft -> {
				config.hdrMapping = mapping;
				config.hdrPeakNits = peak;
				config.hdrUiNits = ui;
			});
			context.waitTicks(3);
		}
	}

	/** Mean brightness of a float capture (1.0 = SDR white on macOS, 80 nits on Windows). */
	private static double meanValue(OutputProbe.Capture capture) {
		if (capture == null) {
			return Double.NaN;
		}
		double sum = 0.0;
		for (int y = 0; y < capture.height(); y++) {
			for (int x = 0; x < capture.width(); x++) {
				sum += capture.value(x, y);
			}
		}
		return sum / (capture.width() * capture.height());
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
		float uiScale = context.computeOnClient(minecraft -> Hdr.uiNits()) / Hdr.unitNits();
		float floorScale = DeepColorConfig.get().hdrShadowFloor / Hdr.unitNits();
		this.log("scRGB output, HDR " + (active ? "active" : "inactive") + ", interface white " + uiScale * Hdr.unitNits() + " nits");
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

	/**
	 * The vanilla vignette at full strength (as in a dark cave) keeps only about 18% of the light in
	 * the corners. That darkening is even and must not be mistaken for a mod overlay: the corners get
	 * the same Linear gain as the rest of the world. Only the top corners are used, away from the
	 * hotbar and chat.
	 */
	private void checkVignetteHdr(ClientGameTestContext context) {
		if (!context.computeOnClient(minecraft -> Hdr.active())) {
			return;
		}
		DeepColorConfig config = DeepColorConfig.get();
		DeepColorConfig.HdrMapping mapping = config.hdrMapping;
		boolean vignette = context.computeOnClient(minecraft -> minecraft.options.vignette().get());
		float brightness = context.computeOnClient(minecraft -> minecraft.gui.hud.vignetteBrightness);
		try {
			context.runOnClient(minecraft -> minecraft.options.vignette().set(true));
			double none = this.cornerMean(context, config, DeepColorConfig.HdrMapping.NONE);
			double linear = this.cornerMean(context, config, DeepColorConfig.HdrMapping.LINEAR);
			float expected = context.computeOnClient(minecraft -> Hdr.peakNits() / Hdr.uiNits());
			double gain = linear / none;
			this.check(String.format(Locale.ROOT, "corners under a full-strength vignette keep the Linear gain (%.2f of %.2f)", gain, expected),
				gain > expected * 0.9 && gain < expected * 1.05);
		} finally {
			context.runOnClient(minecraft -> {
				minecraft.options.vignette().set(vignette);
				config.hdrMapping = mapping;
				// Back where it was, or it would fade back during the screenshot comparisons that follow.
				minecraft.gui.hud.vignetteBrightness = brightness;
			});
		}
	}

	/** Mean brightness of the outer 1/8 of the two top corners, with the vignette pinned at full strength. */
	private double cornerMean(ClientGameTestContext context, DeepColorConfig config, DeepColorConfig.HdrMapping mapping) {
		context.runOnClient(minecraft -> {
			config.hdrMapping = mapping;
			// Moves 1% per tick toward the light level, so it stays near full over the few ticks below.
			minecraft.gui.hud.vignetteBrightness = 1.0F;
		});
		context.waitTicks(3);
		context.runOnClient(minecraft -> minecraft.gui.hud.vignetteBrightness = 1.0F);
		OutputProbe.Capture capture = capture(context, OutputProbe::captureDitherTarget);
		int w = capture.width() / 8;
		int h = capture.height() / 8;
		double sum = 0.0;
		int count = 0;
		// Row 0 of a capture is the bottom of the screen.
		for (int y = capture.height() - h; y < capture.height(); y++) {
			for (int x = 0; x < w; x++) {
				sum += capture.value(x, y) + capture.value(capture.width() - 1 - x, y);
				count += 2;
			}
		}
		return sum / count;
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
				double nits = capture.value(x, y) * Hdr.unitNits();
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
			GpuFormat internal = context.computeOnClient(minecraft -> Formats.internal());
			if (first.bits() == 10 && internal != GpuFormat.RGBA8_UNORM) {
				// Without dither nothing should round the image to 8-bit levels: a deeper scene keeps the
				// in-between codes, and fog and sky gradients always produce some.
				double offGrid = 100.0 * offEightBitGrid(first) / first.red().length;
				this.log(String.format(Locale.ROOT, "world with dither off: %.1f%% of pixels between 8-bit levels", offGrid));
				this.check("dither off keeps the full 10-bit output", offGrid > 1.0);
			}
		}
	}

	/** Pixels whose 10-bit code is not one that an 8-bit level is written as. */
	private static long offEightBitGrid(OutputProbe.Capture capture) {
		long count = 0;
		for (int code : capture.red()) {
			int level = (int) Math.floor(code * 255.0 / 1023.0 + 0.5);
			if (code != (int) Math.ceil(level * 1023.0 / 255.0 - 0.001)) {
				count++;
			}
		}
		return count;
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
		// Triangular noise at strength s moves a pixel less than s steps, so after rounding it lands
		// less than s + 0.5 steps from the true ramp: up to about 1.48 at strength 1, 2.48 at 2. The
		// limits leave room for the sampled ramp position being slightly off.
		float strength = DeepColorConfig.get().ditherStrength;
		double spread = maxDeviation(first, rows[3], quarter / 2, scale, levels);
		this.log(String.format(Locale.ROOT, "dithered strip: farthest pixel %.2f steps from the ramp at strength %.2f", spread, strength));
		boolean followsStrength = spread < strength + 0.6 && (strength > 1.5F ? spread > strength - 0.25 : true);
		this.check("dithered strip follows the configured strength", followsStrength);

		OutputProbe.Capture second = capture(context, OutputProbe::captureDitherTarget);
		boolean temporal = DeepColorConfig.get().temporal;
		this.check("noise over time matches the temporal setting", temporal != Arrays.equals(first.red(), second.red()));

		for (int strip = 0; strip < 4; strip++) {
			int selected = strip;
			context.runOnClient(minecraft -> screen.select(selected));
			context.waitTicks(3);
			OutputProbe.Capture full = capture(context, OutputProbe::captureDitherTarget);
			this.log("full-screen strip " + strip + ": " + distinctInRow(full, full.height() / 2) + " distinct codes");
			if (strip == 3) {
				// Averaged over frames: at 10 bits the darkest levels span only a few pixel columns,
				// and one frame's noise alone would move the mean by about the limit.
				double darkBias = darkEndBias(full, ditherBits);
				for (int i = 0; i < 2; i++) {
					context.waitTicks(1);
					darkBias += darkEndBias(capture(context, OutputProbe::captureDitherTarget), ditherBits);
				}
				darkBias /= 3.0;
				this.check(String.format(Locale.ROOT, "dither keeps the darkest shades at their true level (%+.3f LSB)", darkBias), Math.abs(darkBias) < 0.03);
			}
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
			// A count alone would also pass a black or misplaced image, so the strip must also hold the
			// same ramp as the dither target.
			this.check("OpenGL back buffer shows the undithered strip of the dither target", backBufferMatches(back, first, rows[2]));
			if (bits == 10) {
				this.check("OpenGL back buffer holds real 10-bit values", noDither > 100);
			} else {
				this.check("OpenGL back buffer matches the reported 8 bits", noDither <= 33);
			}
		} else {
			this.log("Vulkan swapchain images have no TRANSFER_SRC usage, back buffer not readable");
			// A request must still be answered (with nothing), or whatever waits for it never ends.
			AtomicReference<String> answer = new AtomicReference<>();
			context.runOnClient(minecraft -> OutputProbe.captureBackBuffer(capture -> answer.set(capture == null ? "none" : "a capture")));
			context.waitFor(minecraft -> answer.get() != null, 60);
			this.check("Vulkan back buffer request is answered without a capture (" + answer.get() + ")", "none".equals(answer.get()));
		}
		context.setScreen(() -> new TitleScreen());
		context.waitTicks(3);
	}

	/** The capture, or null when the probe answered without one or never answered. */
	private static OutputProbe.Capture capture(ClientGameTestContext context, Consumer<Consumer<OutputProbe.Capture>> request) {
		AtomicReference<OutputProbe.Capture> result = new AtomicReference<>();
		AtomicBoolean answered = new AtomicBoolean();
		context.runOnClient(minecraft -> request.accept(capture -> {
			result.set(capture);
			answered.set(true);
		}));
		try {
			context.waitFor(minecraft -> answered.get(), 60);
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

	/**
	 * The back buffer is read as 10-bit codes, the dither target holds codes of its own depth. Labels
	 * sit at the far left, so the left third is skipped. The rest of the strip spans about 22 8-bit
	 * levels, which a black or wrongly placed image cannot match.
	 */
	private static boolean backBufferMatches(OutputProbe.Capture back, OutputProbe.Capture target, int row) {
		if (back.width() != target.width() || back.height() != target.height()) {
			return false;
		}
		double toBack = 1023.0 / ((1 << target.bits()) - 1);
		Set<Integer> levels = new HashSet<>();
		for (int x = target.width() / 3; x < target.width(); x++) {
			int expected = (int) Math.round(target.code(x, row) * toBack);
			if (Math.abs(back.code(x, row) - expected) > 1) {
				return false;
			}
			levels.add(target.code(x, row));
		}
		return levels.size() >= 16;
	}

	/**
	 * Average offset, in dither steps, of the dithered ramp within its first step above black:
	 * columns averaged over the middle of the screen, away from the labels. Within that step the old
	 * noise fade read about 0.08 steps too dark, and anything below a quarter step went to black.
	 * Taken signed, so the row-to-row noise of a single frame averages out across the columns.
	 */
	private static double darkEndBias(OutputProbe.Capture capture, int ditherBits) {
		float scale = (1 << capture.bits()) - 1;
		float levels = (1 << ditherBits) - 1;
		double bias = 0.0;
		int counted = 0;
		// The ramp starts with a sliver of true black (BLACK_EDGE in dither.fsh), which is left out.
		// The first two levels: the last step before black, where the noise changes shape, and the
		// one above it, which gives the 10-bit ramp enough columns for a steady mean.
		for (int x = (int) Math.ceil(capture.width() * 0.004) + 1; ; x++) {
			double ideal = (x + 0.5) / capture.width() * 32.0 / 255.0 * levels;
			if (ideal >= 2.0) {
				break;
			}
			double sum = 0.0;
			int samples = 0;
			for (int y = capture.height() / 5; y < capture.height() * 4 / 5; y++) {
				sum += Math.floor(capture.code(x, y) * levels / scale + 1e-9);
				samples++;
			}
			bias += sum / samples - ideal;
			counted++;
		}
		return counted == 0 ? 0.0 : bias / counted;
	}

	/** Farthest any pixel lands from the ideal ramp, in levels. Labels sit at the far left. */
	private static double maxDeviation(OutputProbe.Capture capture, int centerRow, int halfHeight, float scale, float levels) {
		double max = 0.0;
		for (int x = capture.width() / 3; x < capture.width(); x++) {
			double ideal = (x + 0.5) / capture.width() * 32.0 / 255.0 * levels;
			for (int y = centerRow - halfHeight / 2; y < centerRow + halfHeight / 2; y++) {
				max = Math.max(max, Math.abs(Math.floor(capture.code(x, y) * levels / scale + 1e-9) - ideal));
			}
		}
		return max;
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
