package dev.deepcolor.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.gui.GradientScreen;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Final full-screen pass, run just before the image is blitted to the window surface.
 *
 * <p>The target has the real output format, so noise is added in floating point and the attachment
 * write is the one and only quantization step. An RGBA16_FLOAT target would be worse: near white
 * half floats only resolve about half a 10-bit step, which would truncate the noise.
 *
 * <p>For HDR output (Windows scRGB, or EDR on macOS) the target is 16-bit float linear instead, and
 * this pass converts the image to absolute brightness. See {@link Hdr}.
 */
public final class DitherPass {
	private static final int PUSH_CONSTANT_SIZE = 80;
	private static final int TARGET_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final double GOLDEN_RATIO = 0.6180339887498949;
	/** For tests: makes every run of the pass fail, as a shader that does not compile would. */
	private static final boolean SIMULATE_FAILURE = Boolean.getBoolean("deepcolor.test.failDither");
	private static final BindGroupLayout SAMPLERS = BindGroupLayout.builder()
		.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	private static final RenderPipeline PIPELINE_8 = createPipeline("pipeline/dither_8", GpuFormat.RGBA8_UNORM);
	private static final RenderPipeline PIPELINE_10 = createPipeline("pipeline/dither_10", GpuFormat.RGB10A2_UNORM);
	private static final RenderPipeline PIPELINE_SCRGB = createPipeline("pipeline/dither_scrgb", GpuFormat.RGBA16_FLOAT);
	private static @Nullable GpuTexture target;
	private static @Nullable GpuTextureView targetView;
	private static @Nullable GpuTexture noise;
	private static @Nullable GpuTextureView noiseView;
	private static @Nullable TextureTarget flattened;
	private static long frame;
	private static boolean failed;

	private DitherPass() {
	}

	/** Returns the view to present: the dither target, or the input if there is nothing to do. */
	public static GpuTextureView apply(GpuTextureView source) {
		if (failed) {
			OutputProbe.onFramePresented(null, OutputState.bits());
			return source;
		}
		try {
			OutputState.initialize();
			Hdr.logChanges();
			DeepColorConfig config = DeepColorConfig.get();
			int gradientMode = Minecraft.getInstance().gui.screen() instanceof GradientScreen screen ? screen.shaderMode() : 0;
			int bits = OutputState.bits();
			GpuFormat outputFormat = OutputState.isScrgb() ? GpuFormat.RGBA16_FLOAT : formatForBits(bits);
			// scRGB always needs the pass: it is what turns the image into absolute brightness.
			if (!OutputState.isScrgb() && !config.dither && gradientMode == 0 && source.texture().getFormat() == outputFormat) {
				OutputProbe.onFramePresented(null, bits);
				return source;
			}

			frame++;
			ensureTarget(source, outputFormat);
			// The gradient screen always dithers its dithered strip, at the configured strength.
			float strength = config.dither || gradientMode != 0 ? config.ditherStrength : 0.0F;
			float offset = config.temporal ? (float) ((frame * GOLDEN_RATIO) % 1.0) : 0.0F;
			// The shader rounds to the dither depth. Without dither there is no reason to round coarser
			// than the output, and doing so would turn a 10-bit image into 8-bit banding. The gradient
			// screen keeps the dither depth: its dithered strip always dithers, to show what it does.
			int quantizeBits = config.dither || gradientMode != 0 ? OutputState.ditherBits() : bits;
			// The world to expand into HDR highlights, when this frame has one. Taken every frame, so
			// it never carries over to frames without a world.
			GpuTextureView world = HdrExpandPass.takeWorldForFrame();
			render(source, Objects.requireNonNull(targetView), OutputState.isScrgb() ? Output.SCRGB : bits == 10 ? Output.TEN_BIT : Output.EIGHT_BIT,
				quantizeBits, strength, offset, gradientMode, OutputState.isScrgb() ? world : null);
			OutputProbe.onFramePresented(target, bits);
			return Objects.requireNonNull(targetView);
		} catch (RuntimeException e) {
			// Presenting the undithered image is always possible, so never let this take the game down.
			failed = true;
			DeepColor.LOGGER.error("Dither pass failed and is now disabled", e);
			OutputProbe.onFramePresented(null, OutputState.bits());
			return source;
		}
	}

	/** True once the pass has failed and switched itself off. */
	public static boolean failed() {
		return failed;
	}

	/**
	 * Returns an RGBA8 copy of a target, for code that reads pixels back as RGBA8. Readbacks are
	 * queued in order, so one reused target is safe even with several captures in a frame.
	 *
	 * <p>Screenshots and world icons come through here, so it follows the same rule as {@link #apply}:
	 * a failing dither pass must never take the game down. Once the pass has failed, the copy is made
	 * with the game's own blit, which only lacks the dither.
	 */
	public static RenderTarget flattenForReadback(RenderTarget target) {
		// The main target holds the plain SDR image also with HDR on (the expansion happens only when
		// the frame is shown, see HdrExpandPass), so it can be read as it is.
		GpuTextureView source = target.getColorTextureView();
		if (source == null || source.texture().getFormat() == GpuFormat.RGBA8_UNORM) {
			return target;
		}
		if (flattened == null || flattened.width != target.width || flattened.height != target.height) {
			if (flattened != null) {
				flattened.destroyBuffers();
			}
			flattened = new TextureTarget("Deep Color readback", target.width, target.height, GpuFormat.RGBA8_UNORM, null);
		}
		GpuTextureView destination = Objects.requireNonNull(flattened.getColorTextureView());
		if (!failed) {
			try {
				DeepColorConfig config = DeepColorConfig.get();
				render(source, destination, Output.EIGHT_BIT, 8, config.dither ? config.ditherStrength : 0.0F, 0.0F, 0, null);
				return flattened;
			} catch (RuntimeException e) {
				failed = true;
				DeepColor.LOGGER.error("Dither pass failed and is now disabled", e);
			}
		}
		try {
			// The game's plain screen copy. It does not blend, so pixels with alpha below 1 keep their
			// color, as they do in the normal conversion (RenderTarget.blitAndBlendToTexture would
			// multiply them by alpha).
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Deep Color readback copy", destination, Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.draw(3, 1, 0, 0);
			}
			return flattened;
		} catch (RuntimeException e) {
			// A wrong-looking screenshot is still better than a crash.
			DeepColor.LOGGER.error("Could not convert the image to RGBA8 for a screenshot", e);
			return target;
		}
	}

	private static float lsb(int bits) {
		return 1.0F / ((1 << bits) - 1);
	}

	private enum Output {
		EIGHT_BIT(8, PIPELINE_8),
		TEN_BIT(10, PIPELINE_10),
		SCRGB(16, PIPELINE_SCRGB);

		final int bits;
		final RenderPipeline pipeline;

		Output(int bits, RenderPipeline pipeline) {
			this.bits = bits;
			this.pipeline = pipeline;
		}
	}

	private static void render(GpuTextureView source, GpuTextureView destination, Output output, int ditherBits, float strength, float noiseOffset, int mode,
		@Nullable GpuTextureView world) {
		if (SIMULATE_FAILURE) {
			throw new IllegalStateException("Simulated dither pass failure (deepcolor.test.failDither)");
		}
		ensureNoise();
		var compiled = ModPipelines.get(output.pipeline);
		DeepColorConfig config = DeepColorConfig.get();
		boolean hdrActive = output == Output.SCRGB && Hdr.active();

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Deep Color dither", destination, Optional.empty())) {
			pass.setPipeline(compiled);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			pass.setUniform("InSampler", source, nearest);
			pass.setUniform("NoiseSampler", Objects.requireNonNull(noiseView), nearest);
			boolean expand = world != null && hdrActive;
			// Every sampler must be bound; the noise stands in when there is no world to expand.
			pass.setUniform("WorldSampler", expand ? world : noiseView, nearest);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer constants = stack.calloc(PUSH_CONSTANT_SIZE);
				constants.putFloat(lsb(ditherBits));
				constants.putFloat(lsb(output.bits));
				// Float output is never dithered in the real image, only in the gradient screen's strip.
				constants.putFloat(strength);
				constants.putFloat(noiseOffset);
				constants.putInt(mode);
				constants.putInt(config.noisePattern == DeepColorConfig.NoisePattern.BLUE_NOISE ? 0 : 1);
				constants.putInt(output == Output.SCRGB ? 1 : 0);
				constants.putFloat(config.hdrGamma);
				constants.putFloat(hdrActive ? Hdr.uiNits() / Hdr.unitNits() : 1.0F);
				constants.putFloat(hdrActive ? Hdr.peakNits() / Hdr.unitNits() : 1.0F);
				constants.putInt(hdrActive ? 1 : 0);
				constants.putFloat(hdrActive ? config.hdrShadowFloor / Hdr.unitNits() : 0.0F);
				constants.putFloat(hdrActive ? config.hdrGamutStretch : 0.0F);
				constants.putInt(config.hdrGamutTarget.ordinal());
				constants.putFloat(Hdr.unitNits());
				constants.putFloat(expand ? HdrExpandPass.boost() : 0.0F);
				constants.putFloat(expand ? HdrExpandPass.exponent() : 0.0F);
				constants.putInt(!expand ? 0 : HdrExpandPass.mapping() == DeepColorConfig.HdrMapping.HIGHLIGHTS ? 1 : 2);
				constants.rewind();
				pass.pushConstants(constants);
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	private static GpuFormat formatForBits(int bits) {
		return bits == 10 ? GpuFormat.RGB10A2_UNORM : GpuFormat.RGBA8_UNORM;
	}

	private static void ensureTarget(GpuTextureView source, GpuFormat format) {
		int width = source.getWidth(0);
		int height = source.getHeight(0);
		if (target != null && target.getWidth(0) == width && target.getHeight(0) == height && target.getFormat() == format) {
			return;
		}
		closeTarget();
		GpuDevice device = RenderSystem.getDevice();
		target = device.createTexture(() -> "Deep Color dither target", TARGET_USAGE, format, width, height, 1, 1);
		targetView = device.createTextureView(target);
	}

	private static void closeTarget() {
		if (targetView != null) {
			targetView.close();
			targetView = null;
		}
		if (target != null) {
			target.close();
			target = null;
		}
	}

	private static void ensureNoise() {
		if (noise == null) {
			createNoiseTexture();
		}
	}

	/** RGBA8 holds a 12-bit rank exactly in two bytes, and every backend can sample it. */
	private static void createNoiseTexture() {
		long start = System.nanoTime();
		int[] ranks = BlueNoise.generateRanks();
		GpuDevice device = RenderSystem.getDevice();
		int size = BlueNoise.SIZE;
		noise = device.createTexture(() -> "Deep Color blue noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
		noiseView = device.createTextureView(noise);
		ByteBuffer pixels = MemoryUtil.memAlloc(size * size * 4);
		try {
			for (int rank : ranks) {
				pixels.put((byte) (rank >> 8)).put((byte) rank).put((byte) 0).put((byte) 0xFF);
			}
			pixels.flip();
			device.createCommandEncoder().writeToTexture(noise, pixels, 0, 0, 0, 0, size, size);
		} finally {
			MemoryUtil.memFree(pixels);
		}
		DeepColor.LOGGER.info("Generated {}x{} blue noise in {} ms", size, size, (System.nanoTime() - start) / 1_000_000);
	}

	private static RenderPipeline createPipeline(String location, GpuFormat format) {
		return RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
			.withLocation(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, location))
			.withVertexShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "fullscreen"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "dither"))
			.withBindGroupLayout(SAMPLERS)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withPushConstantSize(PUSH_CONSTANT_SIZE)
			.withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
			.build();
	}

	public static void close() {
		closeTarget();
		if (flattened != null) {
			flattened.destroyBuffers();
			flattened = null;
		}
		if (noiseView != null) {
			noiseView.close();
			noiseView = null;
		}
		if (noise != null) {
			noise.close();
			noise = null;
		}
		HdrExpandPass.close();
		ModPipelines.close();
	}
}
