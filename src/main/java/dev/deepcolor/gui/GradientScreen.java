package dev.deepcolor.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.deepcolor.core.OutputProbe;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.Hdr;
import dev.deepcolor.core.OutputState;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Near-black comparison gradient, RGB 0 to 32 from left to right. This screen only draws the labels
 * on black. The gradient itself comes from the dither shader, because the GUI cannot produce values
 * between 8-bit steps. See DitherPass and dither.fsh.
 */
public final class GradientScreen extends Screen {
	private static final int STRIPS = 4;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BLACK = 0xFF000000;

	private final @Nullable Screen parent;
	/** -1 shows all strips, otherwise the strip shown full screen. */
	private int selected = -1;
	private volatile @Nullable String targetReport;
	private volatile @Nullable String backBufferReport;

	public GradientScreen(@Nullable Screen parent) {
		super(Component.literal("Deep Color gradient"));
		this.parent = parent;
	}

	/** Shown with key 5. */
	private static final int STEP_CHART = 4;
	private static final int[] STEP_CODES = {2, 4, 8, 16, 32, 64, 128, 255};

	/** The mode constant read by dither.fsh. */
	public int shaderMode() {
		if (this.selected == STEP_CHART) {
			return 6;
		}
		return this.selected < 0 ? 1 : 2 + this.selected;
	}

	public void select(int strip) {
		this.selected = strip;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, this.width, this.height, BLACK);
	}

	/** Matches hdrPattern in dither.fsh. */
	private static final float[] PATCH_NITS = {0.001F, 0.002F, 0.005F, 0.01F, 0.02F, 0.05F, 0.1F, 0.2F};
	private static final String[] RAMP_TICKS = {"0.0001", "0.001", "0.01", "0.1", "1"};

	private static boolean hdrPattern() {
		return OutputState.isScrgb() && Hdr.active();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (this.selected == STEP_CHART) {
			graphics.text(this.font, Component.translatable("deepcolor.gradient.steps"), 6, 6, WHITE);
			graphics.text(this.font, OutputState.statusLine(), 6, 18, WHITE);
			for (int patch = 0; patch < STEP_CODES.length; patch++) {
				graphics.text(this.font, String.valueOf(STEP_CODES[patch]), this.width * patch / 8 + this.width / 16, this.height * 3 / 4, WHITE);
			}
			graphics.text(this.font, Component.translatable("deepcolor.gradient.help"), 6, this.height - 14, WHITE);
			return;
		}
		if (hdrPattern()) {
			this.extractHdrLabels(graphics);
			return;
		}
		if (this.selected < 0) {
			for (int strip = 0; strip < STRIPS; strip++) {
				int top = this.height * strip / STRIPS;
				graphics.text(this.font, label(strip), 6, top + 6, WHITE);
				if (strip > 0) {
					graphics.fill(0, top, this.width, top + 1, WHITE);
				}
			}
		} else {
			graphics.text(this.font, label(this.selected), 6, 6, WHITE);
		}
		graphics.text(this.font, Component.translatable("deepcolor.gradient.help"), 6, this.height - 14, WHITE);
		int reportY = this.height - 40;
		for (String report : new String[]{this.targetReport, this.backBufferReport}) {
			if (report != null) {
				graphics.text(this.font, report, 6, reportY, WHITE);
				reportY += 12;
			}
		}
	}

	private void extractHdrLabels(GuiGraphicsExtractor graphics) {
		int strips = this.selected < 0 ? STRIPS : 1;
		for (int i = 0; i < strips; i++) {
			int strip = this.selected < 0 ? i : this.selected;
			int top = this.height * i / strips;
			int bottom = this.height * (i + 1) / strips;
			if (i > 0) {
				graphics.fill(0, top, this.width, top + 1, WHITE);
			}
			Component name = strip == 1
				? Component.translatable("deepcolor.gradient.hdr1", String.format(Locale.ROOT, "%.3f", DeepColorConfig.get().hdrShadowFloor))
				: Component.translatable("deepcolor.gradient.hdr" + strip);
			graphics.text(this.font, name, 6, top + 4, WHITE);
			if (strip <= 1) {
				// Ticks every decade, matching the log ramp: 5% black, then 0.0001 to 1 nit.
				for (int decade = 0; decade <= 4; decade++) {
					int x = Math.round(this.width * (0.05F + 0.95F * decade / 4.0F));
					graphics.fill(x, bottom - 6, x + 1, bottom, WHITE);
					String value = RAMP_TICKS[decade];
					graphics.text(this.font, value, Math.min(x + 2, this.width - this.font.width(value) - 2), bottom - 15, WHITE);
				}
			} else if (strip == 2) {
				for (int patch = 0; patch < PATCH_NITS.length; patch++) {
					String value = new java.math.BigDecimal(Float.toString(PATCH_NITS[patch])).stripTrailingZeros().toPlainString();
					graphics.text(this.font, value, this.width * patch / 8 + this.width / 16, bottom - 12, WHITE);
				}
			}
		}
		graphics.text(this.font, Component.translatable("deepcolor.gradient.hdr_help"), 6, this.height - 26, WHITE);
	}

	private static Component label(int strip) {
		return switch (strip) {
			case 2 -> Component.translatable("deepcolor.gradient.strip2", OutputState.bits());
			case 3 -> Component.translatable("deepcolor.gradient.strip3", OutputState.ditherBits());
			default -> Component.translatable("deepcolor.gradient.strip" + strip);
		};
	}

	/** Measures the undithered output-depth strip of the frame that is on screen right now. */
	private void measure() {
		this.selected = -1;
		if (OutputState.isScrgb()) {
			// Float output is never quantized, and with HDR on strip 2 holds the nit patches, not the
			// ramp. A level count would mean nothing.
			this.targetReport = Component.translatable("deepcolor.gradient.measure_float").getString();
			this.backBufferReport = null;
			return;
		}
		OutputProbe.captureDitherTarget(capture -> this.targetReport = describe(capture));
		OutputProbe.captureBackBuffer(capture -> this.backBufferReport = capture == null
			? Component.translatable("deepcolor.gradient.measure_no_back_buffer").getString()
			: describe(capture));
	}

	private static String describe(OutputProbe.Capture capture) {
		// Counted from the top of the screen, strip 2 is the undithered output-depth strip.
		int row = capture.height() - 1 - capture.height() * 5 / 8;
		int[] codes = Arrays.copyOfRange(capture.red(), row * capture.width(), (row + 1) * capture.width());
		long levels = Arrays.stream(codes).distinct().count();
		return String.format(Locale.ROOT, "%s: %d levels (8-bit: 33, 10-bit: 129)", capture.source(), levels);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_0) {
			this.selected = -1;
			return true;
		}
		if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_1 + STRIPS) {
			// Keys 1 to 4 show one strip, key 5 the grey step chart.
			this.selected = key - InputConstants.KEY_1;
			return true;
		}
		if (key == InputConstants.KEY_P) {
			this.measure();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
