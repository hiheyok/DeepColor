package dev.deepcolor.core;

import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * Windows HDR output. The game hands Windows a scRGB image (linear, FP16, 1.0 = 80 nits), so it sets
 * brightness in absolute nits itself instead of going through the "SDR content brightness" slider.
 *
 * <p>Two system properties exist for automated tests on a machine with Windows HDR off:
 * deepcolor.test.forceScrgb picks the scRGB surface anyway, deepcolor.test.assumeHdr then encodes as
 * if HDR were on.
 */
public final class Hdr {
	public static final float SCRGB_NITS = 80.0F;
	private static final boolean FORCE_SCRGB = Boolean.getBoolean("deepcolor.test.forceScrgb");
	private static final boolean ASSUME_HDR = Boolean.getBoolean("deepcolor.test.assumeHdr");
	/** Used when Windows does not report a peak, and for tests. */
	private static final float FALLBACK_PEAK_NITS = 1000.0F;
	private static final float FALLBACK_SDR_WHITE_NITS = 203.0F;

	private static @org.jspecify.annotations.Nullable String lastState;

	private Hdr() {
	}

	/**
	 * Logs what Windows reports whenever it changes. SDL fills in a window's HDR properties a few
	 * frames after the window appears, and again when Windows HDR is toggled.
	 */
	public static void logChanges() {
		long window = window();
		int display = SDLVideo.SDL_GetDisplayForWindow(window);
		String state = String.format(java.util.Locale.ROOT, "display HDR %s, window HDR %s, SDR white %.0f nits, headroom %.2f",
			display != 0 && SDLProperties.SDL_GetBooleanProperty(SDLVideo.SDL_GetDisplayProperties(display), SDLVideo.SDL_PROP_DISPLAY_HDR_ENABLED_BOOLEAN, false),
			windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN),
			SCRGB_NITS * windowFloat(SDLVideo.SDL_PROP_WINDOW_SDR_WHITE_LEVEL_FLOAT, 0.0F),
			windowFloat(SDLVideo.SDL_PROP_WINDOW_HDR_HEADROOM_FLOAT, 0.0F));
		if (!state.equals(lastState)) {
			lastState = state;
			DeepColor.LOGGER.info("Windows reports: {}", state);
		}
	}

	/** Whether to ask for an HDR-capable surface at startup. */
	public static boolean requested() {
		return FORCE_SCRGB || DeepColorConfig.get().hdrOutput == DeepColorConfig.HdrOutput.ON;
	}

	/** Checked before the window and surface exist, so it asks about the primary display. */
	public static boolean displayHdrEnabled() {
		if (FORCE_SCRGB) {
			return true;
		}
		int display = SDLVideo.SDL_GetPrimaryDisplay();
		boolean enabled = display != 0 && SDLProperties.SDL_GetBooleanProperty(SDLVideo.SDL_GetDisplayProperties(display), SDLVideo.SDL_PROP_DISPLAY_HDR_ENABLED_BOOLEAN, false);
		if (!enabled) {
			DeepColor.LOGGER.info("HDR output is on in the config, but Windows HDR is off for the primary display. Using SDR output.");
		}
		return enabled;
	}

	/**
	 * True while the scRGB surface is in use and Windows HDR is on for the window. Checked every frame,
	 * so turning Windows HDR off while playing falls back to a plain SDR image instead of clipping.
	 */
	public static boolean active() {
		return OutputState.isScrgb() && (ASSUME_HDR || windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN));
	}

	/** Brightness of SDR white, which is where the interface sits. */
	public static float uiNits() {
		int configured = DeepColorConfig.get().hdrUiNits;
		return configured > 0 ? configured : windowsSdrWhiteNits();
	}

	/** Brightest value the world may reach. */
	public static float peakNits() {
		int configured = DeepColorConfig.get().hdrPeakNits;
		float peak = configured > 0 ? configured : displayPeakNits();
		return Math.max(peak, uiNits());
	}

	public static float windowsSdrWhiteNits() {
		if (ASSUME_HDR && !windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN)) {
			return FALLBACK_SDR_WHITE_NITS;
		}
		return SCRGB_NITS * windowFloat(SDLVideo.SDL_PROP_WINDOW_SDR_WHITE_LEVEL_FLOAT, FALLBACK_SDR_WHITE_NITS / SCRGB_NITS);
	}

	/** Windows knows the panel's peak from the HDR Calibration app or the display's EDID. */
	public static float displayPeakNits() {
		float headroom = windowFloat(SDLVideo.SDL_PROP_WINDOW_HDR_HEADROOM_FLOAT, 0.0F);
		return headroom > 1.0F ? windowsSdrWhiteNits() * headroom : FALLBACK_PEAK_NITS;
	}

	private static long window() {
		return Minecraft.getInstance().getWindow().handle();
	}

	private static boolean windowBoolean(String property) {
		return SDLProperties.SDL_GetBooleanProperty(SDLVideo.SDL_GetWindowProperties(window()), property, false);
	}

	private static float windowFloat(String property, float fallback) {
		return SDLProperties.SDL_GetFloatProperty(SDLVideo.SDL_GetWindowProperties(window()), property, fallback);
	}
}
