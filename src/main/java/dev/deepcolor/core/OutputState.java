package dev.deepcolor.core;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** What the window surface actually turned out to be. Written during startup. */
public final class OutputState {
	private static @Nullable String backend;
	private static String surface = "unknown";
	private static int bits = 8;
	private static boolean scrgb;
	private static boolean initialized;

	private OutputState() {
	}

	/** Called by the Vulkan swapchain hook, which runs before the first present. */
	public static void setVulkanSurface(String description, int outputBits) {
		setVulkanSurface(description, outputBits, false);
	}

	public static void setVulkanSurface(String description, int outputBits, boolean floatScrgb) {
		surface = description;
		bits = outputBits;
		scrgb = floatScrgb;
	}

	/** The surface is 16-bit float scRGB, for Windows HDR output. */
	public static boolean isScrgb() {
		return scrgb;
	}

	/** Runs on the render thread at the start of the first frame blitted to the surface. */
	public static void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		boolean wanted10 = DeepColorConfig.get().wants10BitOutput();
		if (isOpenGl()) {
			GlOutput.Result result = GlOutput.query();
			surface = result.description();
			bits = result.bits();
			scrgb = result.floating();
			if (Hdr.requested() && !scrgb) {
				DeepColor.LOGGER.info("HDR output: the driver gave no floating-point OpenGL framebuffer, using SDR output");
			}
			if (!scrgb && wanted10 && bits < 10) {
				DeepColor.LOGGER.warn(
					"The OpenGL default framebuffer has {} bits per channel although 10 were requested. "
						+ "The driver reported no error. Deep Color keeps running and dithers for {} bits.", bits, bits);
			}
		} else if (!scrgb && wanted10 && bits < 10) {
			DeepColor.LOGGER.warn("No 10-bit SDR swapchain format is usable, staying at 8 bits ({})", surface);
		}
		DeepColor.LOGGER.info("Output active: {}", statusLine());
	}

	private static String backend() {
		if (backend == null) {
			backend = RenderSystem.getDevice().getDeviceInfo().backendName();
		}
		return backend;
	}

	/** Safe from the first render pass on, which runs before the first present. */
	public static boolean isOpenGl() {
		return backend().equals("OpenGL");
	}

	public static int bits() {
		return bits;
	}

	public static boolean isExclusiveFullscreen() {
		return Minecraft.getInstance().getWindow().isExclusiveFullscreen();
	}

	/**
	 * The depth the noise is shaped for, never more than the output. With HDR off, Windows composites
	 * windowed and borderless games to 8 bits, which throws 10-bit noise away but keeps 8-bit noise.
	 * Only exclusive fullscreen skips the compositor, so auto uses 10 bits only there.
	 */
	public static int ditherBits() {
		if (scrgb) {
			// Only the gradient test strips use this then. Float output is not dithered.
			return 10;
		}
		return switch (DeepColorConfig.get().ditherBits) {
			case EIGHT -> 8;
			case TEN -> bits;
			case AUTO -> bits == 10 && isExclusiveFullscreen() ? 10 : 8;
		};
	}

	public static String statusLine() {
		DeepColorConfig config = DeepColorConfig.get();
		String dither = config.dither
			? String.format(Locale.ROOT, "%d-bit %s %.2f LSB %s", ditherBits(),
				config.noisePattern == DeepColorConfig.NoisePattern.BLUE_NOISE ? "blue noise" : "gradient noise",
				config.ditherStrength, config.temporal ? "temporal" : "static")
			: "off";
		String mode = isExclusiveFullscreen() ? "exclusive fullscreen" : "windowed/borderless";
		if (scrgb) {
			String hdr = Hdr.active()
				? String.format(Locale.ROOT, "HDR on: UI %.0f nits, peak %.0f nits, %s, %s", Hdr.uiNits(), Hdr.peakNits(),
					config.hdrMapping.name().toLowerCase(Locale.ROOT),
					(config.hdrGamma == DeepColorConfig.SRGB_CURVE ? "sRGB curve" : String.format(Locale.ROOT, "gamma %.2f", config.hdrGamma))
						+ (config.hdrShadowFloor > 0.0F ? String.format(Locale.ROOT, ", floor %.3f nits", config.hdrShadowFloor) : "")
						+ (config.hdrGamutStretch > 0.0F ? String.format(Locale.ROOT, ", gamut %.0f%% toward %s", config.hdrGamutStretch * 100,
							switch (config.hdrGamutTarget) {
								case DISPLAY_P3 -> "Display P3";
								case ADOBE_RGB -> "Adobe RGB";
								case BT_2020 -> "BT.2020";
							}) : ""))
				: "Windows HDR off, plain SDR";
			return String.format(Locale.ROOT, "%s | surface %s | %s | main %s | %s", backend(), surface, hdr, Formats.internal(), mode);
		}
		return String.format(Locale.ROOT, "%s | surface %s | output %d-bit | main %s | dither %s | %s",
			backend(), surface, bits, Formats.internal(), dither, mode);
	}
}
