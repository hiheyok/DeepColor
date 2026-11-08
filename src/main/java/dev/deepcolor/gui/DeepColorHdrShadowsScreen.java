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
		int x = this.width / 2 - 125;
		int y = Math.max(32, Math.min(this.height / 6, this.height - 148));
		for (Control control : Control.values()) {
			this.addRenderableWidget(new ShadowSlider(x, y, control, DeepColorConfig.get()));
			y += 24;
		}
		y += 8;
		this.addRenderableWidget(Button.builder(Component.translatable("deepcolor.config.gradient"),
			button -> this.minecraft.gui.setScreen(new GradientScreen(this))).bounds(x, y, 250, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width / 2 - 100, y + 24, 200, 20).build());
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

	private enum Control {
		CONTRAST("hdr_shadow_contrast", 19),
		RANGE("hdr_shadow_contrast_range", DeepColorConfig.SHADOW_CONTRAST_RANGE_STEPS.length - 1),
		NOISE("hdr_shadow_dither", (int) (DeepColorConfig.MAX_HDR_SHADOW_DITHER * 2));

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
			float[] ranges = DeepColorConfig.SHADOW_CONTRAST_RANGE_STEPS;
			int best = 0;
			for (int i = 1; i < ranges.length; i++) {
				if (Math.abs(ranges[i] - config.hdrShadowContrastRange) < Math.abs(ranges[best] - config.hdrShadowContrastRange)) {
					best = i;
				}
			}
			return best / (double) this.steps;
		}
	}

	private static final class ShadowSlider extends AbstractSliderButton {
		private final Control control;
		private final DeepColorConfig config;

		private ShadowSlider(int x, int y, Control control, DeepColorConfig config) {
			super(x, y, 250, 20, CommonComponents.EMPTY, control.position(config));
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
			}
		}
	}
}
