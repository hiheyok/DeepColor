package dev.deepcolor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.DeepColor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * Stored as config/deepcolor.json. outputBits and internalFormat are read once at startup, everything
 * else applies on the next frame.
 */
public final class DeepColorConfig {
	/** Shared by the Mod Menu screen and the Sodium page, so both show the same names. */
	public interface Named {
		String key();

		default Component displayName() {
			return Component.translatable("deepcolor.option." + this.key());
		}
	}

	public enum OutputBits implements Named {
		@SerializedName("auto") AUTO,
		@SerializedName("8") EIGHT,
		@SerializedName("10") TEN;

		@Override
		public String key() {
			return "output_bits." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** Depth the noise is shaped for. Windows composites windowed games to 8 bits while HDR is off. */
	public enum DitherBits implements Named {
		@SerializedName("auto") AUTO,
		@SerializedName("8") EIGHT,
		@SerializedName("10") TEN;

		@Override
		public String key() {
			return "dither_bits." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	public enum NoisePattern implements Named {
		@SerializedName("blue") BLUE_NOISE,
		@SerializedName("gradient") GRADIENT;

		@Override
		public String key() {
			return "noise_pattern." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	public enum InternalFormat implements Named {
		RGBA16_FLOAT(GpuFormat.RGBA16_FLOAT),
		RGBA16_UNORM(GpuFormat.RGBA16_UNORM),
		RGB10A2_UNORM(GpuFormat.RGB10A2_UNORM),
		RGBA8_UNORM(GpuFormat.RGBA8_UNORM);

		private final GpuFormat format;

		InternalFormat(GpuFormat format) {
			this.format = format;
		}

		public GpuFormat format() {
			return this.format;
		}

		@Override
		public String key() {
			return "internal_format." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	public enum HdrOutput implements Named {
		@SerializedName("off") OFF,
		@SerializedName("on") ON;

		@Override
		public String key() {
			return "hdr_output." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	public enum HdrMapping implements Named {
		@SerializedName("highlights") HIGHLIGHTS,
		@SerializedName("linear") LINEAR,
		@SerializedName("none") NONE;

		@Override
		public String key() {
			return "hdr_mapping." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	/** Only read, to carry over the fixed curve choices of earlier builds into hdrGamma. */
	private enum ShadowCurve {
		@SerializedName("gamma22") GAMMA_2_2(2.2F),
		@SerializedName("gamma24") GAMMA_2_4(2.4F),
		@SerializedName("gamma26") GAMMA_2_6(2.6F),
		@SerializedName("srgb") SRGB(SRGB_CURVE);

		private final float exponent;

		ShadowCurve(float exponent) {
			this.exponent = exponent;
		}
	}

	/** Order matches gamutTarget in dither.fsh. */
	public enum GamutTarget implements Named {
		@SerializedName("p3") DISPLAY_P3,
		@SerializedName("adobe") ADOBE_RGB,
		@SerializedName("bt2020") BT_2020;

		@Override
		public String key() {
			return "hdr_gamut_target." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	public static final float MAX_STRENGTH = 2.0F;
	public static final float MAX_HDR_SHADOW_DITHER = 8.0F;
	public static final float MAX_HDR_SHADOW_CONTRAST = 0.95F;
	/** Upper limit of the contrast correction, in nominal nits. */
	public static final float[] SHADOW_CONTRAST_RANGE_STEPS = {0.1F, 0.2F, 0.5F, 1.0F, 2.0F, 5.0F, 10.0F, 20.0F};
	public static final float DEFAULT_SHADOW_CONTRAST_RANGE = 2.0F;
	/** Low enough to match a dim SDR setup, which can sit around 60 to 80 nits. */
	public static final int MIN_UI_NITS = 40;
	public static final int MAX_UI_NITS = 600;
	/** The reference white for SDR content inside HDR (ITU-R BT.2408). */
	public static final int DEFAULT_UI_NITS = 203;
	public static final int MIN_PEAK_NITS = 200;
	public static final int MAX_PEAK_NITS = 2000;
	/** hdrGamma value meaning the piecewise sRGB curve, which Windows uses for SDR apps in HDR mode. */
	public static final float SRGB_CURVE = 0.0F;
	/** Shadow floor choices in nits, for the Sodium slider. 0 is off. */
	public static final float[] SHADOW_FLOOR_STEPS = {0.0F, 0.001F, 0.002F, 0.003F, 0.005F, 0.0075F, 0.01F, 0.015F, 0.02F, 0.03F, 0.05F, 0.075F, 0.1F, 0.15F, 0.2F};
	public static final float MIN_GAMMA = 1.8F;
	public static final float MAX_GAMMA = 3.0F;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("deepcolor.json");
	private static DeepColorConfig instance;

	public OutputBits outputBits = OutputBits.AUTO;
	public InternalFormat internalFormat = InternalFormat.RGBA16_FLOAT;
	public boolean dither = true;
	public DitherBits ditherBits = DitherBits.AUTO;
	public NoisePattern noisePattern = NoisePattern.BLUE_NOISE;
	public float ditherStrength = 1.0F;
	public boolean temporal = true;
	public HdrOutput hdrOutput = HdrOutput.OFF;
	/**
	 * 203 nits is the reference white for SDR content inside HDR (ITU-R BT.2408). 0 means the SDR
	 * white level set by the Windows slider instead (SDR white on macOS).
	 */
	public int hdrUiNits = DEFAULT_UI_NITS;
	/** 0 means the peak the system reports for the display (EDR headroom on macOS). */
	public int hdrPeakNits = 0;
	public HdrMapping hdrMapping = HdrMapping.HIGHLIGHTS;
	/**
	 * Shadow curve for HDR output: a power-law exponent from MIN_GAMMA to MAX_GAMMA, higher is darker.
	 * SRGB_CURVE (0) selects the sRGB curve instead.
	 */
	public float hdrGamma = 2.2F;
	/**
	 * Nits that the darkest shadows are lifted to, for panels that clip very dark HDR levels to black.
	 * 0 is off. Measure it with the near-black pattern on the gradient screen.
	 */
	public float hdrShadowFloor = 0.0F;
	/** Optional near-black noise amplitude in nominal 10-bit PQ steps; 0 is off. */
	public float hdrShadowDither = 0.0F;
	/** Optional stochastic pulses above hdrShadowFloor to preserve sub-floor detail on OLED. */
	public boolean hdrFloorDither = false;
	/** Maximum fractional near-black reduction; 0 is off. Independent of dithering. */
	public float hdrShadowContrast = 0.0F;
	/** Brightness above which the contrast correction has no effect, in nominal nits. */
	public float hdrShadowContrastRange = DEFAULT_SHADOW_CONTRAST_RANGE;
	/**
	 * 0 shows the game's sRGB colors accurately. 1 stretches them to the Display P3 gamut, like a
	 * monitor's native-gamut SDR mode, which is why SDR can look more saturated than accurate HDR.
	 */
	public float hdrGamutStretch = 0.0F;
	/** The gamut hdrGamutStretch stretches toward. */
	public GamutTarget hdrGamutTarget = GamutTarget.DISPLAY_P3;
	/** Earlier builds stored a fixed choice here. Read once, then dropped from the file. */
	private ShadowCurve shadowCurve;

	public static synchronized DeepColorConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static DeepColorConfig load() {
		DeepColorConfig config = new DeepColorConfig();
		if (Files.exists(PATH)) {
			try {
				DeepColorConfig parsed = GSON.fromJson(Files.readString(PATH), DeepColorConfig.class);
				if (parsed != null) {
					config = parsed;
				}
			} catch (IOException | JsonParseException e) {
				DeepColor.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}
		config.sanitize();
		config.save();
		return config;
	}

	/** Gson leaves an enum field null when the file holds a value it does not know. */
	private void sanitize() {
		DeepColorConfig defaults = new DeepColorConfig();
		if (this.outputBits == null) {
			DeepColor.LOGGER.warn("Unknown outputBits in {}, using auto", PATH);
			this.outputBits = defaults.outputBits;
		}
		if (this.internalFormat == null) {
			DeepColor.LOGGER.warn("Unknown internalFormat in {}, using RGBA16_FLOAT", PATH);
			this.internalFormat = defaults.internalFormat;
		}
		if (this.ditherBits == null) {
			this.ditherBits = defaults.ditherBits;
		}
		if (this.noisePattern == null) {
			this.noisePattern = defaults.noisePattern;
		}
		if (this.hdrOutput == null) {
			this.hdrOutput = defaults.hdrOutput;
		}
		if (this.hdrMapping == null) {
			this.hdrMapping = defaults.hdrMapping;
		}
		if (this.shadowCurve != null) {
			this.hdrGamma = this.shadowCurve.exponent;
			this.shadowCurve = null;
		}
		if (this.hdrGamutTarget == null) {
			this.hdrGamutTarget = defaults.hdrGamutTarget;
		}
		if (!(this.hdrGamutStretch >= 0.0F && this.hdrGamutStretch <= 1.0F)) {
			this.hdrGamutStretch = 0.0F;
		}
		if (!(this.hdrShadowContrast >= 0.0F && this.hdrShadowContrast <= MAX_HDR_SHADOW_CONTRAST)) {
			this.hdrShadowContrast = defaults.hdrShadowContrast;
		}
		if (!(this.hdrShadowContrastRange >= 0.1F && this.hdrShadowContrastRange <= 20.0F)) {
			this.hdrShadowContrastRange = defaults.hdrShadowContrastRange;
		}
		if (!(this.hdrShadowDither >= 0.0F && this.hdrShadowDither <= MAX_HDR_SHADOW_DITHER)) {
			this.hdrShadowDither = defaults.hdrShadowDither;
		}
		if (!(this.hdrShadowFloor >= 0.0F && this.hdrShadowFloor <= 0.2F)) {
			this.hdrShadowFloor = 0.0F;
		}
		if (this.hdrGamma != SRGB_CURVE && !(this.hdrGamma >= MIN_GAMMA && this.hdrGamma <= MAX_GAMMA)) {
			this.hdrGamma = defaults.hdrGamma;
		}
		if (this.hdrUiNits != 0) {
			this.hdrUiNits = Math.clamp(this.hdrUiNits, MIN_UI_NITS, MAX_UI_NITS);
		}
		if (this.hdrPeakNits != 0) {
			this.hdrPeakNits = Math.clamp(this.hdrPeakNits, MIN_PEAK_NITS, MAX_PEAK_NITS);
		}
		// NaN fails both comparisons, so test for the valid range rather than the invalid one.
		if (!(this.ditherStrength >= 0.0F && this.ditherStrength <= MAX_STRENGTH)) {
			this.ditherStrength = defaults.ditherStrength;
		}
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			DeepColor.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	public boolean wants10BitOutput() {
		return this.outputBits != OutputBits.EIGHT;
	}
}
