package dev.deepcolor.gui;

import dev.deepcolor.config.DeepColorConfig;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** HDR shadow controls kept on a separate screen so the main options still fit small GUI sizes. */
public final class DeepColorHdrShadowsScreen extends Screen {
	private final Screen parent;

	public DeepColorHdrShadowsScreen(Screen parent) {
		super(Component.translatable("deepcolor.config.hdr_shadows.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 155;
		int y = Math.max(32, this.height / 6);
		int index = 0;
		for (Control control : new Control[] {Control.CONTRAST, Control.RANGE, Control.NOISE, Control.GAMMA, Control.FLOOR}) {
			this.addRenderableWidget(new ShadowSlider(x + index % 2 * 160, y + index / 2 * 24, 150, control, DeepColorConfig.get()));
			index++;
		}
		this.addRenderableWidget(net.minecraft.client.gui.components.CycleButton.onOffBuilder(DeepColorConfig.get().hdrFloorDither)
			.create(x + 160, y + 48, 150, 20, Component.translatable("deepcolor.option.hdr_floor_dither"),
				(button, value) -> DeepColorConfig.get().hdrFloorDither = value));
		this.addRenderableWidget(Button.builder(Component.translatable("deepcolor.config.gradient"),
			button -> this.minecraft.gui.setScreen(new GradientScreen(this))).bounds(x, y + 80, 310, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width / 2 - 100, y + 104, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		DeepColorConfig.get().save();
		this.minecraft.gui.setScreen(this.parent);
	}

	enum Control {
		CONTRAST("hdr_shadow_contrast", 19),
		RANGE("hdr_shadow_contrast_range", DeepColorConfig.SHADOW_CONTRAST_RANGE_STEPS.length - 1),
		NOISE("hdr_shadow_dither", (int) (DeepColorConfig.MAX_HDR_SHADOW_DITHER * 2)),
		GAMMA("hdr_gamma", Math.round((DeepColorConfig.MAX_GAMMA - DeepColorConfig.MIN_GAMMA) * 20) + 1),
		FLOOR("hdr_shadow_floor", DeepColorConfig.SHADOW_FLOOR_STEPS.length - 1),
		WHITE("hdr_ui_nits", DeepColorConfig.MAX_UI_NITS - DeepColorConfig.MIN_UI_NITS);

		final String key;
		final int steps;

		Control(String key, int steps) {
			this.key = key;
			this.steps = steps;
		}

		double position(DeepColorConfig config) {
			if (this == CONTRAST) {
				return config.hdrShadowContrast / DeepColorConfig.MAX_HDR_SHADOW_CONTRAST;
			}
			if (this == NOISE) {
				return config.hdrShadowDither / DeepColorConfig.MAX_HDR_SHADOW_DITHER;
			}
			if (this == WHITE) {
				return (Math.max(DeepColorConfig.MIN_UI_NITS, config.hdrUiNits) - DeepColorConfig.MIN_UI_NITS) / (double) this.steps;
			}
			if (this == GAMMA) {
				return config.hdrGamma == 0 ? 0 : (Math.round((config.hdrGamma - DeepColorConfig.MIN_GAMMA) * 20) + 1) / (double) this.steps;
			}
			float[] ranges = this == FLOOR ? DeepColorConfig.SHADOW_FLOOR_STEPS : DeepColorConfig.SHADOW_CONTRAST_RANGE_STEPS;
			float value = this == FLOOR ? config.hdrShadowFloor : config.hdrShadowContrastRange;
			int best = 0;
			for (int i = 1; i < ranges.length; i++) {
				if (Math.abs(ranges[i] - value) < Math.abs(ranges[best] - value)) {
					best = i;
				}
			}
			return best / (double) this.steps;
		}
	}

	static final class ShadowSlider extends AbstractSliderButton {
		private final Control control;
		private final DeepColorConfig config;

		ShadowSlider(int x, int y, int width, Control control, DeepColorConfig config) {
			super(x, y, width, 20, CommonComponents.EMPTY, control.position(config));
			this.control = control;
			this.config = config;
			this.setTooltip(Tooltip.create(Component.translatable("deepcolor.option." + control.key + ".tooltip")));
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			Component amount = switch (this.control) {
				case CONTRAST -> this.config.hdrShadowContrast == 0 ? Component.translatable("deepcolor.option.hdr_shadow_floor.off")
					: Component.literal(Math.round(this.config.hdrShadowContrast * 100) + "%");
				case RANGE -> Component.literal(new java.math.BigDecimal(Float.toString(this.config.hdrShadowContrastRange)).stripTrailingZeros().toPlainString());
				case GAMMA -> this.config.hdrGamma == 0 ? Component.translatable("deepcolor.option.hdr_gamma.srgb")
					: Component.literal(String.format(Locale.ROOT, "%.2f", this.config.hdrGamma));
				case WHITE -> this.config.hdrUiNits == 0 ? Component.translatable("deepcolor.option.nits.auto") : Component.literal(String.valueOf(this.config.hdrUiNits));
				case FLOOR -> Component.literal(new java.math.BigDecimal(Float.toString(this.config.hdrShadowFloor)).stripTrailingZeros().toPlainString());
				case NOISE -> this.config.hdrShadowDither == 0 ? Component.translatable("deepcolor.option.hdr_shadow_floor.off")
					: Component.literal(String.format(Locale.ROOT, "%.1f", this.config.hdrShadowDither));
			};
			this.setMessage(Component.translatable("deepcolor.option." + this.control.key + ".value", amount));
		}

		@Override
		protected void applyValue() {
			int step = (int) Math.round(this.value * this.control.steps);
			switch (this.control) {
				case CONTRAST -> this.config.hdrShadowContrast = step * 5 / 100.0F;
				case RANGE -> this.config.hdrShadowContrastRange = DeepColorConfig.SHADOW_CONTRAST_RANGE_STEPS[step];
				case NOISE -> this.config.hdrShadowDither = step / 2.0F;
				case GAMMA -> this.config.hdrGamma = step == 0 ? 0 : DeepColorConfig.MIN_GAMMA + (step - 1) / 20.0F;
				case FLOOR -> this.config.hdrShadowFloor = DeepColorConfig.SHADOW_FLOOR_STEPS[step];
				case WHITE -> this.config.hdrUiNits = DeepColorConfig.MIN_UI_NITS + step;
			}
		}
	}
}
