package dev.deepcolor.core;

import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;

/**
 * HDR output. On Windows the game hands the compositor a scRGB image (linear, FP16, 1.0 = 80 nits),
 * so it sets brightness in absolute nits itself instead of going through the "SDR content brightness"
 * slider.
 *
 * <p>On macOS the same FP16 extended linear sRGB surface (Vulkan through MoltenVK) is shown with EDR,
 * where 1.0 is SDR white at whatever brightness the user has set, and the headroom above it changes with
 * that brightness. macOS has no absolute nits, so SDR white counts as {@link #MACOS_SDR_WHITE_NITS}
 * (BT.2408 reference white): the default interface brightness then sits exactly at SDR white, and every
 * nits setting is relative to it.
 *
 * <p>Two system properties exist for automated tests on a machine with Windows HDR off:
 * deepcolor.test.forceScrgb picks the scRGB surface anyway, deepcolor.test.assumeHdr then encodes as
 * if HDR were on.
 */
public final class Hdr {
	public static final float SCRGB_NITS = 80.0F;
	public static final float MACOS_SDR_WHITE_NITS = 203.0F;
	private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static final boolean MACOS = OS_NAME.startsWith("mac");
	private static final String SYSTEM_NAME = MACOS ? "macOS" : OS_NAME.startsWith("windows") ? "Windows" : "The system";
	private static final boolean FORCE_SCRGB = Boolean.getBoolean("deepcolor.test.forceScrgb");
	private static final boolean ASSUME_HDR = Boolean.getBoolean("deepcolor.test.assumeHdr");
	/** Used when the system does not report a peak, and for tests. */
	private static final float FALLBACK_PEAK_NITS = 1000.0F;
	private static final float FALLBACK_SDR_WHITE_NITS = 203.0F;

	private static @org.jspecify.annotations.Nullable String lastState;
	private static float lastHeadroom;

	private Hdr() {
	}

	/**
	 * Logs what the system reports whenever it changes. SDL fills in a window's HDR properties a few
	 * frames after the window appears, and again when HDR is toggled. On macOS the EDR headroom also
	 * ramps smoothly with the display brightness, so the headroom alone is logged only when it moves
	 * by more than a quarter.
	 */
	public static void logChanges() {
		// Only of interest when HDR was asked for, also (and most of all) when it did not turn on, or
		// while the float surface is still in use after the setting was turned off (until a restart).
		// Otherwise skip the SDL queries every frame.
		if (!requested() && !OutputState.isScrgb()) {
			return;
		}
		long window = window();
		int display = SDLVideo.SDL_GetDisplayForWindow(window);
		String state = String.format(Locale.ROOT, "display HDR %s, window HDR %s, SDR white %.0f nits",
			display != 0 && SDLProperties.SDL_GetBooleanProperty(SDLVideo.SDL_GetDisplayProperties(display), SDLVideo.SDL_PROP_DISPLAY_HDR_ENABLED_BOOLEAN, false),
			windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN),
			unitNits() * windowFloat(SDLVideo.SDL_PROP_WINDOW_SDR_WHITE_LEVEL_FLOAT, 0.0F));
		float headroom = windowFloat(SDLVideo.SDL_PROP_WINDOW_HDR_HEADROOM_FLOAT, 0.0F);
		boolean headroomMoved = lastHeadroom <= 0.0F ? headroom != lastHeadroom : Math.abs(headroom / lastHeadroom - 1.0F) > 0.25F;
		if (!state.equals(lastState) || headroomMoved) {
			lastState = state;
			lastHeadroom = headroom;
			DeepColor.LOGGER.info("{} reports: {}, headroom {}", SYSTEM_NAME, state, String.format(Locale.ROOT, "%.2f", headroom));
		}
	}

	/** Whether to ask for an HDR-capable surface at startup. */
	public static boolean requested() {
		return FORCE_SCRGB || DeepColorConfig.get().hdrOutput == DeepColorConfig.HdrOutput.ON;
	}

	/**
	 * Whether any display has HDR on (Windows HDR, or EDR headroom on macOS). The surface is chosen
	 * while the window still sits on the primary display, where the game always opens it, and cannot
	 * change later. Asking only about the primary display would rule out HDR on a secondary HDR
	 * display the window is then moved to. The HDR surface works on an SDR display as well:
	 * {@link #active()} follows the display the window is on every frame and encodes plain SDR there.
	 */
	public static boolean displayHdrEnabled() {
		if (FORCE_SCRGB) {
			return true;
		}
		IntBuffer displays = SDLVideo.SDL_GetDisplays();
		if (displays == null) {
			DeepColor.LOGGER.warn("HDR output: SDL could not list the displays ({}), using SDR output", SDLError.SDL_GetError());
			return false;
		}
		List<String> hdrDisplays = new ArrayList<>();
		try {
			for (int i = 0; i < displays.remaining(); i++) {
				int display = displays.get(i);
				if (SDLProperties.SDL_GetBooleanProperty(SDLVideo.SDL_GetDisplayProperties(display), SDLVideo.SDL_PROP_DISPLAY_HDR_ENABLED_BOOLEAN, false)) {
					hdrDisplays.add(Objects.requireNonNullElse(SDLVideo.SDL_GetDisplayName(display), "display " + display));
				}
			}
		} finally {
			SDLStdinc.SDL_free(displays);
		}
		if (hdrDisplays.isEmpty()) {
			DeepColor.LOGGER.info(MACOS
				? "HDR output is on in the config, but no display has EDR headroom. Using SDR output."
				: "HDR output is on in the config, but Windows HDR is off for every display. Using SDR output.");
			return false;
		}
		DeepColor.LOGGER.info("HDR output: HDR is on for {}", String.join(", ", hdrDisplays));
		return true;
	}

	/** True on macOS, where 1.0 on the HDR surface is SDR white rather than 80 nits. */
	public static boolean isMacOs() {
		return MACOS;
	}

	/** The brightness that 1.0 on the FP16 surface stands for. */
	public static float unitNits() {
		return MACOS ? MACOS_SDR_WHITE_NITS : SCRGB_NITS;
	}

	/**
	 * True while the scRGB surface is in use and HDR is on for the window (Windows HDR, or EDR headroom
	 * on macOS). Checked every frame,
	 * so turning Windows HDR off while playing falls back to a plain SDR image instead of clipping.
	 */
	public static boolean active() {
		return OutputState.hasHeadroom() && (ASSUME_HDR || windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN));
	}

	/** Brightness of SDR white, which is where the interface sits. */
	public static float uiNits() {
		int configured = DeepColorConfig.get().hdrUiNits;
		return configured > 0 ? configured : systemSdrWhiteNits();
	}

	/** Brightest value the world may reach. */
	public static float peakNits() {
		int configured = DeepColorConfig.get().hdrPeakNits;
		float peak = configured > 0 ? configured : displayPeakNits();
		return Math.max(peak, uiNits());
	}

	/** SDR white as the system sets it: the Windows slider, or EDR's 1.0 on macOS. */
	public static float systemSdrWhiteNits() {
		if (ASSUME_HDR && !windowBoolean(SDLVideo.SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN)) {
			return FALLBACK_SDR_WHITE_NITS;
		}
		return unitNits() * windowFloat(SDLVideo.SDL_PROP_WINDOW_SDR_WHITE_LEVEL_FLOAT, FALLBACK_SDR_WHITE_NITS / unitNits());
	}

	/**
	 * Windows knows the panel's peak from the HDR Calibration app or the display's EDID. macOS reports
	 * the current EDR headroom, which shrinks as the display brightness goes up.
	 */
	public static float displayPeakNits() {
		float headroom = windowFloat(SDLVideo.SDL_PROP_WINDOW_HDR_HEADROOM_FLOAT, 0.0F);
		return headroom > 1.0F ? systemSdrWhiteNits() * headroom : FALLBACK_PEAK_NITS;
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
