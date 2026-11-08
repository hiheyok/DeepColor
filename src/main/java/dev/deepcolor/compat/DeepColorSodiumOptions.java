package dev.deepcolor.compat;

import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.gui.GradientScreen;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds a Deep Color page to Sodium's video settings, through Sodium's own config API. Loaded only
 * when Sodium is installed, from the sodium:config_api_user entrypoint.
 */
public final class DeepColorSodiumOptions implements ConfigEntryPoint {
	private static final int STRENGTH_STEPS = 20;
	private static final Identifier DITHER = id("dither");
	private static final Identifier HDR_OUTPUT = id("hdr_output");

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		if (DeepColor.isDisabledByHdrMod()) {
			return;
		}
		DeepColorConfig config = DeepColorConfig.get();
		String version = FabricLoader.getInstance().getModContainer(DeepColor.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");

		builder.registerOwnModOptions()
			.setName("Deep Color")
			.setVersion(version)
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x3D7EDB))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("deepcolor.page.dither"))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(builder.createBooleanOption(DITHER)
						.setName(Component.translatable("deepcolor.option.dither"))
						.setTooltip(Component.translatable("deepcolor.option.dither.tooltip"))
						.setDefaultValue(true)
						.setBinding(value -> config.dither = value, () -> config.dither)
						.setStorageHandler(config::save)
						.setImpact(OptionImpact.LOW))
					.addOption(builder.createIntegerOption(id("strength"))
						.setName(Component.translatable("deepcolor.option.strength"))
						.setTooltip(Component.translatable("deepcolor.option.strength.tooltip"))
						.setRange(0, (int) (DeepColorConfig.MAX_STRENGTH * STRENGTH_STEPS), 1)
						.setValueFormatter(steps -> Component.literal(String.format(Locale.ROOT, "%.2f LSB", steps / (float) STRENGTH_STEPS)))
						.setDefaultValue(STRENGTH_STEPS)
						.setBinding(value -> config.ditherStrength = value / (float) STRENGTH_STEPS, () -> Math.round(config.ditherStrength * STRENGTH_STEPS))
						.setEnabledProvider(state -> state.readBooleanOption(DITHER), DITHER)
						.setStorageHandler(config::save))
					.addOption(builder.createBooleanOption(id("temporal"))
						.setName(Component.translatable("deepcolor.option.temporal"))
						.setTooltip(Component.translatable("deepcolor.option.temporal.tooltip"))
						.setDefaultValue(true)
						.setBinding(value -> config.temporal = value, () -> config.temporal)
						.setEnabledProvider(state -> state.readBooleanOption(DITHER), DITHER)
						.setStorageHandler(config::save)))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(enumOption(builder, "dither_bits", DeepColorConfig.DitherBits.class, DeepColorConfig.DitherBits.AUTO,
						value -> config.ditherBits = value, () -> config.ditherBits, config)
						.setEnabledProvider(state -> state.readBooleanOption(DITHER), DITHER))
					.addOption(enumOption(builder, "noise_pattern", DeepColorConfig.NoisePattern.class, DeepColorConfig.NoisePattern.BLUE_NOISE,
						value -> config.noisePattern = value, () -> config.noisePattern, config)
						.setEnabledProvider(state -> state.readBooleanOption(DITHER), DITHER)))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(builder.createExternalButtonOption(id("gradient"))
						.setName(Component.translatable("deepcolor.config.gradient"))
						.setTooltip(Component.translatable("deepcolor.config.gradient.tooltip"))
						.setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new GradientScreen(parent))))))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("deepcolor.page.output"))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(enumOption(builder, "output_bits", DeepColorConfig.OutputBits.class, DeepColorConfig.OutputBits.AUTO,
						value -> config.outputBits = value, () -> config.outputBits, config)
						.setFlags(OptionFlag.REQUIRES_GAME_RESTART))
					.addOption(enumOption(builder, "internal_format", DeepColorConfig.InternalFormat.class, DeepColorConfig.InternalFormat.RGBA16_FLOAT,
						value -> config.internalFormat = value, () -> config.internalFormat, config)
						.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
						.setImpact(OptionImpact.LOW))))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("deepcolor.page.hdr"))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(enumOption(builder, "hdr_output", DeepColorConfig.HdrOutput.class, DeepColorConfig.HdrOutput.OFF,
						value -> config.hdrOutput = value, () -> config.hdrOutput, config)
						.setFlags(OptionFlag.REQUIRES_GAME_RESTART)))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(uiNitsOption(builder, config))
					.addOption(nitsOption(builder, "hdr_peak_nits", DeepColorConfig.MIN_PEAK_NITS, DeepColorConfig.MAX_PEAK_NITS, 50, 0,
						value -> config.hdrPeakNits = value, () -> config.hdrPeakNits, config))
					.addOption(enumOption(builder, "hdr_mapping", DeepColorConfig.HdrMapping.class, DeepColorConfig.HdrMapping.HIGHLIGHTS,
						value -> config.hdrMapping = value, () -> config.hdrMapping, config)
						.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT))
					.addOption(gammaOption(builder, config))
					.addOption(shadowFloorOption(builder, config))
					.addOption(enumOption(builder, "hdr_gamut_target", DeepColorConfig.GamutTarget.class, DeepColorConfig.GamutTarget.DISPLAY_P3,
						value -> config.hdrGamutTarget = value, () -> config.hdrGamutTarget, config)
						.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT))
					.addOption(builder.createIntegerOption(id("hdr_gamut"))
						.setName(Component.translatable("deepcolor.option.hdr_gamut"))
						.setTooltip(Component.translatable("deepcolor.option.hdr_gamut.tooltip"))
						.setRange(0, 100, 5)
						.setValueFormatter(percent -> Component.literal(percent == 0 ? "sRGB" : percent + "%"))
						.setDefaultValue(0)
						.setBinding(percent -> config.hdrGamutStretch = percent / 100.0F, () -> snap(Math.round(config.hdrGamutStretch * 100), 0, 100, 5))
						.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT)
						.setStorageHandler(config::save))));
	}

	private static net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder shadowFloorOption(ConfigBuilder builder, DeepColorConfig config) {
		float[] steps = DeepColorConfig.SHADOW_FLOOR_STEPS;
		return builder.createIntegerOption(id("hdr_shadow_floor"))
			.setName(Component.translatable("deepcolor.option.hdr_shadow_floor"))
			.setTooltip(Component.translatable("deepcolor.option.hdr_shadow_floor.tooltip"))
			.setRange(0, steps.length - 1, 1)
			.setValueFormatter(index -> index == 0
				? Component.translatable("deepcolor.option.hdr_shadow_floor.off")
				: Component.literal(String.format(Locale.ROOT, "%s nits", trim(steps[index]))))
			.setDefaultValue(0)
			.setBinding(index -> config.hdrShadowFloor = steps[index], () -> nearestStep(steps, config.hdrShadowFloor))
			.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT)
			.setStorageHandler(config::save);
	}

	/**
	 * Interface brightness. The positions are Auto, then every 10 nits, plus the config default
	 * (203, the BT.2408 reference white) so that "reset to default" gives exactly the default.
	 */
	private static net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder uiNitsOption(ConfigBuilder builder, DeepColorConfig config) {
		float[] steps = uiNitsSteps();
		int defaultIndex = nearestStep(steps, DeepColorConfig.DEFAULT_UI_NITS);
		return builder.createIntegerOption(id("hdr_ui_nits"))
			.setName(Component.translatable("deepcolor.option.hdr_ui_nits"))
			.setTooltip(Component.translatable("deepcolor.option.hdr_ui_nits.tooltip"))
			.setRange(0, steps.length - 1, 1)
			.setValueFormatter(index -> index == 0 ? Component.translatable("deepcolor.option.nits.auto") : Component.translatable("deepcolor.option.nits.value", (int) steps[index]))
			.setDefaultValue(defaultIndex)
			.setBinding(index -> config.hdrUiNits = (int) steps[index], () -> nearestStep(steps, config.hdrUiNits))
			.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT)
			.setStorageHandler(config::save);
	}

	/**
	 * 0 (Auto), then MIN_UI_NITS to MAX_UI_NITS in steps of 10, with the default added in order. The
	 * config never holds a value between 0 and MIN_UI_NITS, so only 0 itself lands on Auto.
	 */
	private static float[] uiNitsSteps() {
		java.util.TreeSet<Integer> values = new java.util.TreeSet<>();
		values.add(0);
		for (int nits = DeepColorConfig.MIN_UI_NITS; nits <= DeepColorConfig.MAX_UI_NITS; nits += 10) {
			values.add(nits);
		}
		values.add(DeepColorConfig.DEFAULT_UI_NITS);
		float[] steps = new float[values.size()];
		int i = 0;
		for (int nits : values) {
			steps[i++] = nits;
		}
		return steps;
	}

	private static int nearestStep(float[] steps, float value) {
		int best = 0;
		for (int i = 1; i < steps.length; i++) {
			if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) {
				best = i;
			}
		}
		return best;
	}

	private static String trim(float value) {
		return new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
	}

	/** Gamma times 100, in steps of 0.05. The position below 1.80 is the sRGB curve. */
	private static net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder gammaOption(ConfigBuilder builder, DeepColorConfig config) {
		int min = Math.round(DeepColorConfig.MIN_GAMMA * 100);
		int srgb = min - 5;
		return builder.createIntegerOption(id("hdr_gamma"))
			.setName(Component.translatable("deepcolor.option.hdr_gamma"))
			.setTooltip(Component.translatable("deepcolor.option.hdr_gamma.tooltip"))
			.setRange(srgb, Math.round(DeepColorConfig.MAX_GAMMA * 100), 5)
			.setValueFormatter(value -> value <= srgb
				? Component.translatable("deepcolor.option.hdr_gamma.srgb")
				: Component.literal(String.format(Locale.ROOT, "%.2f", value / 100.0F)))
			.setDefaultValue(220)
			.setBinding(
				value -> config.hdrGamma = value <= srgb ? DeepColorConfig.SRGB_CURVE : value / 100.0F,
				() -> config.hdrGamma == DeepColorConfig.SRGB_CURVE ? srgb : snap(Math.round(config.hdrGamma * 100), min, Math.round(DeepColorConfig.MAX_GAMMA * 100), 5))
			.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT)
			.setStorageHandler(config::save);
	}

	/**
	 * The nearest slider position to a config value. The config accepts any value in range (123 nits,
	 * gamma 2.23), but Sodium checks a loaded value against the slider's steps and saves the default
	 * over any value that is not on one. Showing the nearest step instead keeps the config value as it
	 * is: Sodium only writes an option back when the slider is moved.
	 */
	static int snap(int value, int min, int max, int step) {
		int steps = Math.round((Math.clamp(value, min, max) - min) / (float) step);
		return min + steps * step;
	}

	private static boolean hdrOn(net.caffeinemc.mods.sodium.api.config.ConfigState state) {
		return state.readEnumOption(HDR_OUTPUT, DeepColorConfig.HdrOutput.class) == DeepColorConfig.HdrOutput.ON;
	}

	/**
	 * A nits slider whose lowest position means "automatic". Stored as 0, which the config reads as
	 * the value the system reports.
	 */
	private static net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder nitsOption(
		ConfigBuilder builder, String key, int min, int max, int step, int defaultNits, Consumer<Integer> setter, Supplier<Integer> getter, DeepColorConfig config
	) {
		int auto = min - step;
		return builder.createIntegerOption(id(key))
			.setName(Component.translatable("deepcolor.option." + key))
			.setTooltip(Component.translatable("deepcolor.option." + key + ".tooltip"))
			.setRange(auto, max, step)
			.setValueFormatter(value -> value <= auto ? Component.translatable("deepcolor.option.nits.auto") : Component.translatable("deepcolor.option.nits.value", value))
			// The default must sit on a step too: Sodium falls back to it for any value off the steps.
			.setDefaultValue(defaultNits == 0 ? auto : snap(defaultNits, min, max, step))
			.setBinding(value -> setter.accept(value <= auto ? 0 : value), () -> getter.get() == 0 ? auto : snap(getter.get(), min, max, step))
			.setEnabledProvider(state -> hdrOn(state), HDR_OUTPUT)
			.setStorageHandler(config::save);
	}

	private static <E extends Enum<E> & DeepColorConfig.Named> EnumOptionBuilder<E> enumOption(
		ConfigBuilder builder, String key, Class<E> type, E defaultValue, Consumer<E> setter, Supplier<E> getter, DeepColorConfig config
	) {
		return builder.createEnumOption(id(key), type)
			.setName(Component.translatable("deepcolor.option." + key))
			.setTooltip(Component.translatable("deepcolor.option." + key + ".tooltip"))
			.setElementNameProvider(DeepColorConfig.Named::displayName)
			.setDefaultValue(defaultValue)
			.setBinding(setter, getter)
			.setStorageHandler(config::save);
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, path);
	}
}
