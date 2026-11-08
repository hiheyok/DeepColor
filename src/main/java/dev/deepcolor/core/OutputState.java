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

	/** The surface is 16-bit float linear (scRGB, or extended linear sRGB on macOS), for HDR output. */
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
			// Some drivers report 0 for the default framebuffer, and a GL error reads as 0 too. That
			// would make the dither step infinite and the image black, so it counts as 8. Real depths
			// (10, 12, 16 for the float framebuffer) are kept.
			bits = result.bits() <= 0 ? 8 : result.bits();
			// On macOS a float framebuffer is only HDR once the view has EDR, otherwise it stays SDR.
			scrgb = result.floating() && (!Hdr.isMacOs() || MacEdr.isOn(Minecraft.getInstance().getWindow().handle()));
			if (GlWindowFormat.requested() == GlWindowFormat.Request.FLOAT && !result.floating()) {
				DeepColor.LOGGER.info("HDR output: the driver gave no floating-point OpenGL framebuffer, using SDR output");
			} else if (result.floating() && !scrgb) {
				DeepColor.LOGGER.info("HDR output: EDR is not available for the OpenGL view, using SDR output");
				// The dither pass then writes an 8-bit target, which is what the window shows.
				bits = 8;
			}
			if (!scrgb && wanted10 && bits < 10 && Hdr.isMacOs()) {
				DeepColor.LOGGER.info("macOS OpenGL offers no 10-bit window formats. Deep Color dithers for {} bits; "
					+ "use the Vulkan backend for 10-bit output.", bits);
			} else if (!scrgb && wanted10 && bits < 10) {
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
				: Hdr.isMacOs() ? "no EDR headroom, plain SDR" : "Windows HDR off, plain SDR";
			return String.format(Locale.ROOT, "%s | surface %s | %s | main %s | %s", backend(), surface, hdr, Formats.internal(), mode);
		}
		return String.format(Locale.ROOT, "%s | surface %s | output %d-bit | main %s | dither %s | %s",
			backend(), surface, bits, Formats.internal(), dither, mode);
	}
}
