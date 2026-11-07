package dev.deepcolor.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Lifts the finished world into the brightness range above SDR white, before the interface is drawn.
 * The main target is 16-bit float, so it can hold those values until the final pass encodes them.
 */
public final class HdrExpandPass {
	private static final int PUSH_CONSTANT_SIZE = 16;
	/** Most a gray at half brightness may gain from highlight expansion. */
	private static final float MIDTONE_GAIN = 0.15F;
	private static final int USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "pipeline/hdr_expand"))
		.withVertexShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "fullscreen"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "hdr_expand"))
		.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withPushConstantSize(PUSH_CONSTANT_SIZE)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.build();

	private static @Nullable GpuTexture scratch;
	private static @Nullable GpuTextureView scratchView;
	private static boolean failed;
	private static boolean warnedFormat;

	private HdrExpandPass() {
	}

	public static void apply(RenderTarget main) {
		DeepColorConfig config = DeepColorConfig.get();
		if (failed || config.hdrMapping == DeepColorConfig.HdrMapping.NONE || !Hdr.active()) {
			return;
		}
		GpuTexture color = main.getColorTexture();
		GpuTextureView colorView = main.getColorTextureView();
		if (color == null || colorView == null) {
			return;
		}
		if (color.getFormat() != GpuFormat.RGBA16_FLOAT) {
			if (!warnedFormat) {
				warnedFormat = true;
				DeepColor.LOGGER.warn("Brightness expansion needs internalFormat RGBA16_FLOAT to store values above SDR white, it is {}", color.getFormat());
			}
			return;
		}
		float boost = Hdr.peakNits() / Hdr.uiNits() - 1.0F;
		if (boost <= 0.001F) {
			return;
		}
		try {
			render(colorView, color, boost, config);
		} catch (RuntimeException e) {
			failed = true;
			DeepColor.LOGGER.error("Brightness expansion failed and is now disabled", e);
		}
	}

	private static void render(GpuTextureView source, GpuTexture destination, float boost, DeepColorConfig config) {
		int width = source.getWidth(0);
		int height = source.getHeight(0);
		GpuDevice device = RenderSystem.getDevice();
		if (scratch == null || scratch.getWidth(0) != width || scratch.getHeight(0) != height) {
			close();
			scratch = device.createTexture(() -> "Deep Color HDR expansion", USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			scratchView = device.createTextureView(scratch);
		}
		// A pass cannot read and write the same texture, so render to scratch and copy back.
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Deep Color HDR expansion", scratchView, Optional.empty())) {
			pass.setPipeline(ModPipelines.get(PIPELINE));
			pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer constants = stack.calloc(PUSH_CONSTANT_SIZE);
				constants.putFloat(boost);
				constants.putFloat(highlightExponent(boost));
				constants.putInt(config.hdrMapping == DeepColorConfig.HdrMapping.HIGHLIGHTS ? 0 : 1);
				constants.putFloat(config.hdrGamma);
				constants.rewind();
				pass.pushConstants(constants);
			}
			pass.draw(3, 1, 0, 0);
		}
		device.createCommandEncoder().copyTextureToTexture(scratch, destination, 0, 0, 0, 0, 0, width, height);
	}

	/**
	 * Gain is 1 + boost * luminance^exponent. Picking the exponent so that luminance 0.5 gains exactly
	 * MIDTONE_GAIN keeps midtones in place however far the peak is above interface white. With 203 and
	 * 1000 nits it is 4.7, with 80 and 1000 nits 6.3. Never below 4, so small boosts stay gentle too.
	 */
	static float highlightExponent(float boost) {
		return (float) Math.max(4.0, Math.log(boost / MIDTONE_GAIN) / Math.log(2.0));
	}

	public static void close() {
		if (scratchView != null) {
			scratchView.close();
			scratchView = null;
		}
		if (scratch != null) {
			scratch.close();
			scratch = null;
		}
	}
}
