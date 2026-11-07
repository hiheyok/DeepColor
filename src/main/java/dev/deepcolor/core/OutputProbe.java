package dev.deepcolor.core;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Reads back real output pixels so that "10-bit is active" can be measured instead of assumed.
 *
 * <p>Rows run bottom to top, as in the render targets. Codes are in the range of {@link Capture#bits}.
 */
public final class OutputProbe {
	/**
	 * Red channel codes. source says how far down the pipe the pixels were read. For a float target,
	 * values holds the real red values (scRGB: 1.0 = 80 nits) and red holds them times 1000.
	 */
	public record Capture(String source, int bits, int width, int height, int[] red, float @Nullable [] values) {
		public int code(int x, int y) {
			return this.red[x + y * this.width];
		}

		public float value(int x, int y) {
			return this.values == null ? this.code(x, y) : this.values[x + y * this.width];
		}
	}

	private static final int BUFFER_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

	private static @Nullable Consumer<Capture> pendingTarget;
	private static @Nullable Consumer<Capture> pendingBackBuffer;

	private OutputProbe() {
	}

	/** Captures the dither target on the next frame. Works on both backends. */
	public static void captureDitherTarget(Consumer<Capture> callback) {
		pendingTarget = callback;
	}

	/** Captures the OpenGL back buffer after the next blit. Vulkan swapchain images cannot be read. */
	public static void captureBackBuffer(Consumer<Capture> callback) {
		pendingBackBuffer = callback;
	}

	static void onFramePresented(@Nullable GpuTexture target, int bits) {
		Consumer<Capture> callback = pendingTarget;
		if (callback == null || target == null) {
			return;
		}
		pendingTarget = null;
		int width = target.getWidth(0);
		int height = target.getHeight(0);
		GpuFormat format = target.getFormat();
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Deep Color probe", BUFFER_USAGE, (long) width * height * format.blockSize());
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target, buffer, 0L, () -> {
			int[] red = new int[width * height];
			float[] values = format == GpuFormat.RGBA16_FLOAT ? new float[width * height] : null;
			try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
				ByteBuffer data = view.data();
				int mask = bits == 10 ? 0x3FF : 0xFF;
				for (int i = 0; i < red.length; i++) {
					if (values != null) {
						values[i] = Float.float16ToFloat(data.getShort(i * 8));
						red[i] = Math.round(values[i] * 1000.0F);
					} else {
						red[i] = data.getInt(i * 4) & mask;
					}
				}
			}
			buffer.close();
			callback.accept(new Capture("dither target (" + format + ")", bits, width, height, red, values));
		}, 0);
	}

	/** Runs right after the backend blit, while the frame is still in the back buffer. */
	public static void afterBlit(int width, int height) {
		Consumer<Capture> callback = pendingBackBuffer;
		if (callback == null) {
			return;
		}
		pendingBackBuffer = null;
		if (OutputState.isOpenGl()) {
			callback.accept(new Capture("OpenGL back buffer", 10, width, height, GlOutput.readBackBufferRed(width, height), null));
		}
	}
}
