package dev.deepcolor.gui;

import dev.deepcolor.config.DeepColorConfig;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** Used through Mod Menu. With Sodium installed the same options also appear on Sodium's page. */
public final class DeepColorConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 150;
	private static final int GAP = 10;
	private static final int ROW_HEIGHT = 24;
	private static final int SECTION_GAP = 12;
	/** Six rows of options, two section gaps, and the Done button. */
	private static final int CONTENT_HEIGHT = 6 * ROW_HEIGHT + 2 * SECTION_GAP + 20;
	/** The restart notice is drawn this far above the bottom edge, and nothing may reach into it. */
	private static final int FOOTER_HEIGHT = 20;
	/** Below the title. */
	private static final int MIN_TOP = 24;

	private final @Nullable Screen parent;

	public DeepColorConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("deepcolor.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		DeepColorConfig config = DeepColorConfig.get();
		int left = this.width / 2 - WIDGET_WIDTH - GAP / 2;
		int right = this.width / 2 + GAP / 2;
		// A sixth of the way down looks best, but at small GUI sizes (a height of 240) that pushes the
		// Done button into the restart notice. Move up as far as needed, but not into the title.
		int y = Math.max(MIN_TOP, Math.min(this.height / 6, this.height - FOOTER_HEIGHT - CONTENT_HEIGHT));

		this.addRenderableWidget(CycleButton.onOffBuilder(config.dither)
			.create(left, y, WIDGET_WIDTH, 20, Component.translatable("deepcolor.option.dither"), (button, value) -> config.dither = value));
		this.addRenderableWidget(new StrengthSlider(right, y, config));
		y += ROW_HEIGHT;
		this.addRenderableWidget(enumButton(left, y, "dither_bits", DeepColorConfig.DitherBits.values(), config.ditherBits, value -> config.ditherBits = value));
		this.addRenderableWidget(CycleButton.onOffBuilder(config.temporal)
			.create(right, y, WIDGET_WIDTH, 20, Component.translatable("deepcolor.option.temporal"), (button, value) -> config.temporal = value));
		y += ROW_HEIGHT;
		this.addRenderableWidget(enumButton(left, y, "noise_pattern", DeepColorConfig.NoisePattern.values(), config.noisePattern, value -> config.noisePattern = value));
		this.addRenderableWidget(Button.builder(Component.translatable("deepcolor.config.gradient"), button -> this.minecraft.gui.setScreen(new GradientScreen(this)))
			.bounds(right, y, WIDGET_WIDTH, 20)
			.build());
		y += ROW_HEIGHT + SECTION_GAP;
		this.addRenderableWidget(enumButton(left, y, "output_bits", DeepColorConfig.OutputBits.values(), config.outputBits, value -> config.outputBits = value));
		this.addRenderableWidget(enumButton(right, y, "internal_format", DeepColorConfig.InternalFormat.values(), config.internalFormat, value -> config.internalFormat = value));
		y += ROW_HEIGHT;
		this.addRenderableWidget(enumButton(left, y, "hdr_output", DeepColorConfig.HdrOutput.values(), config.hdrOutput, value -> config.hdrOutput = value));
		this.addRenderableWidget(enumButton(right, y, "hdr_mapping", DeepColorConfig.HdrMapping.values(), config.hdrMapping, value -> config.hdrMapping = value));
		y += ROW_HEIGHT;
		this.addRenderableWidget(new GammaSlider(left, y, config));
		this.addRenderableWidget(new ShadowDitherSlider(right, y, config));
		y += ROW_HEIGHT + SECTION_GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width / 2 - 100, y, 200, 20)
			.build());
	}

	private static <E extends Enum<E> & DeepColorConfig.Named> CycleButton<E> enumButton(
		int x, int y, String key, E[] values, E current, java.util.function.Consumer<E> setter
	) {
		return CycleButton.<E>builder(DeepColorConfig.Named::displayName, current)
			.withValues(List.of(values))
			.create(x, y, WIDGET_WIDTH, 20, Component.translatable("deepcolor.option." + key), (button, value) -> setter.accept(value));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.translatable("deepcolor.config.restart"), this.width / 2, footerY(this.height), 0xFFA0A0A0);
	}

	/** Top of the restart notice. */
	public static int footerY(int height) {
		return height - FOOTER_HEIGHT + 4;
	}

	@Override
	public void onClose() {
		DeepColorConfig.get().save();
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Same scale as the Sodium slider: steps of 0.05, the lowest position is the sRGB curve. */
	private static final class GammaSlider extends AbstractSliderButton {
		private static final int STEPS = Math.round((DeepColorConfig.MAX_GAMMA - DeepColorConfig.MIN_GAMMA) * 20) + 1;
		private final DeepColorConfig config;

		private GammaSlider(int x, int y, DeepColorConfig config) {
			super(x, y, WIDGET_WIDTH, 20, CommonComponents.EMPTY, position(config.hdrGamma));
			this.config = config;
			this.updateMessage();
		}

		private static double position(float gamma) {
			return gamma == DeepColorConfig.SRGB_CURVE ? 0.0 : (Math.round((gamma - DeepColorConfig.MIN_GAMMA) * 20) + 1) / (double) STEPS;
		}

		@Override
		protected void updateMessage() {
			Component value = this.config.hdrGamma == DeepColorConfig.SRGB_CURVE
				? Component.translatable("deepcolor.option.hdr_gamma.srgb")
				: Component.literal(String.format(Locale.ROOT, "%.2f", this.config.hdrGamma));
			this.setMessage(Component.translatable("deepcolor.option.hdr_gamma.value", value));
		}

		@Override
		protected void applyValue() {
			int step = (int) Math.round(this.value * STEPS);
			this.config.hdrGamma = step == 0 ? DeepColorConfig.SRGB_CURVE : DeepColorConfig.MIN_GAMMA + (step - 1) / 20.0F;
		}
	}

	private static final class ShadowDitherSlider extends AbstractSliderButton {
		private final DeepColorConfig config;

		private ShadowDitherSlider(int x, int y, DeepColorConfig config) {
			super(x, y, WIDGET_WIDTH, 20, CommonComponents.EMPTY, config.hdrShadowDither / DeepColorConfig.MAX_HDR_SHADOW_DITHER);
			this.config = config;
			this.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("deepcolor.option.hdr_shadow_dither.tooltip")));
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			Component amount = this.config.hdrShadowDither == 0.0F ? Component.translatable("deepcolor.option.hdr_shadow_floor.off")
				: Component.literal(String.format(Locale.ROOT, "%.1f", this.config.hdrShadowDither));
			this.setMessage(Component.translatable("deepcolor.option.hdr_shadow_dither.value", amount));
		}

		@Override
		protected void applyValue() {
			this.config.hdrShadowDither = Math.round(this.value * DeepColorConfig.MAX_HDR_SHADOW_DITHER * 2) / 2.0F;
		}
	}

	private static final class StrengthSlider extends AbstractSliderButton {
		private final DeepColorConfig config;

		private StrengthSlider(int x, int y, DeepColorConfig config) {
			super(x, y, WIDGET_WIDTH, 20, CommonComponents.EMPTY, config.ditherStrength / DeepColorConfig.MAX_STRENGTH);
			this.config = config;
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable("deepcolor.option.strength.value", String.format(Locale.ROOT, "%.2f", this.config.ditherStrength)));
		}

		@Override
		protected void applyValue() {
			// Steps of 0.05 match the Sodium slider.
			float raw = (float) this.value * DeepColorConfig.MAX_STRENGTH;
			this.config.ditherStrength = Mth.clamp(Math.round(raw * 20.0F) / 20.0F, 0.0F, DeepColorConfig.MAX_STRENGTH);
		}
	}
}
