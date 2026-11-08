package dev.deepcolor.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Lifts the finished world into the brightness range above SDR white, before the interface is drawn.
 * The main target is 16-bit float, so it can hold those values until the final pass encodes them.
 *
 * <p>Screenshots read the main target later and are 8-bit SDR, where the expanded world would clip
 * or blow out. So the world before the expansion is kept, and {@link #forReadback} gives back the
 * main target with the expansion undone wherever the interface did not draw over it.
 */
public final class HdrExpandPass {
	private static final int PUSH_CONSTANT_SIZE = 16;
	/** Most a gray at half brightness may gain from highlight expansion. */
	private static final float MIDTONE_GAIN = 0.15F;
	private static final int WORLD_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
	private static final int READBACK_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final BindGroupLayout READBACK_SAMPLERS = BindGroupLayout.builder()
		.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "pipeline/hdr_expand"))
		.withVertexShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "fullscreen"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "hdr_expand"))
		.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withPushConstantSize(PUSH_CONSTANT_SIZE)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.build();
	private static final RenderPipeline READBACK_PIPELINE = RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "pipeline/hdr_readback"))
		.withVertexShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "fullscreen"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "hdr_readback"))
		.withBindGroupLayout(READBACK_SAMPLERS)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withPushConstantSize(PUSH_CONSTANT_SIZE)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.build();

	/** The world as it was before the last expansion. */
	private static @Nullable GpuTexture world;
	private static @Nullable GpuTextureView worldView;
	private static @Nullable GpuTexture readback;
	private static @Nullable GpuTextureView readbackView;
	/** The main target the world copy belongs to, and the settings it was expanded with. */
	private static @Nullable GpuTexture expandedTarget;
	private static float lastBoost;
	private static DeepColorConfig.HdrMapping lastMapping = DeepColorConfig.HdrMapping.HIGHLIGHTS;
	private static float lastGamma;
	private static boolean failed;
	private static boolean warnedFormat;

	private HdrExpandPass() {
	}

	public static void apply(RenderTarget main) {
		// Set again below once this frame is expanded. A frame left as it is needs no undoing.
		expandedTarget = null;
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
			expandedTarget = color;
			lastBoost = boost;
			lastMapping = config.hdrMapping;
			lastGamma = config.hdrGamma;
		} catch (RuntimeException e) {
			expandedTarget = null;
			failed = true;
			DeepColor.LOGGER.error("Brightness expansion failed and is now disabled", e);
		}
	}

	private static void render(GpuTextureView main, GpuTexture mainTexture, float boost, DeepColorConfig config) {
		int width = main.getWidth(0);
		int height = main.getHeight(0);
		GpuDevice device = RenderSystem.getDevice();
		if (world == null || world.getWidth(0) != width || world.getHeight(0) != height) {
			closeWorld();
			world = device.createTexture(() -> "Deep Color world before HDR expansion", WORLD_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			worldView = device.createTextureView(world);
		}
		// A pass cannot read and write the same texture. Copying the world aside first, then
		// expanding from it back into the main target, also keeps the original for screenshots.
		device.createCommandEncoder().copyTextureToTexture(mainTexture, world, 0, 0, 0, 0, 0, width, height);
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Deep Color HDR expansion", main, Optional.empty())) {
			pass.setPipeline(ModPipelines.get(PIPELINE));
			pass.setUniform("InSampler", Objects.requireNonNull(worldView), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pushConstants(pass, boost, config.hdrMapping, config.hdrGamma);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void pushConstants(RenderPass pass, float boost, DeepColorConfig.HdrMapping mapping, float gamma) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer constants = stack.calloc(PUSH_CONSTANT_SIZE);
			constants.putFloat(boost);
			constants.putFloat(highlightExponent(boost));
			constants.putInt(mapping == DeepColorConfig.HdrMapping.HIGHLIGHTS ? 0 : 1);
			constants.putFloat(gamma);
			constants.rewind();
			pass.pushConstants(constants);
		}
	}

	/**
	 * The given view for an SDR readback (screenshots, world icons): when it is the main target the
	 * world was last expanded in, a copy with the expansion undone wherever the world shows, also
	 * where the interface only darkens it (vignette, menu and chat backgrounds). The interface keeps
	 * its own pixels. World behind a colored translucent overlay stays expanded, since it cannot be
	 * told apart from the overlay. Anything else comes back unchanged.
	 */
	public static GpuTextureView forReadback(GpuTextureView view) {
		if (expandedTarget == null || view.texture() != expandedTarget || worldView == null
			|| view.getWidth(0) != worldView.getWidth(0) || view.getHeight(0) != worldView.getHeight(0)) {
			return view;
		}
		int width = view.getWidth(0);
		int height = view.getHeight(0);
		GpuDevice device = RenderSystem.getDevice();
		try {
			if (readback == null || readback.getWidth(0) != width || readback.getHeight(0) != height) {
				closeReadback();
				readback = device.createTexture(() -> "Deep Color HDR readback", READBACK_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
				readbackView = device.createTextureView(readback);
			}
			try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Deep Color HDR readback", Objects.requireNonNull(readbackView), Optional.empty())) {
				pass.setPipeline(ModPipelines.get(READBACK_PIPELINE));
				var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
				pass.setUniform("InSampler", view, nearest);
				pass.setUniform("WorldSampler", worldView, nearest);
				pushConstants(pass, lastBoost, lastMapping, lastGamma);
				pass.draw(3, 1, 0, 0);
			}
			return readbackView;
		} catch (RuntimeException e) {
			// An overexposed screenshot is still better than none.
			DeepColor.LOGGER.error("Could not undo the HDR expansion for a screenshot", e);
			return view;
		}
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
		closeWorld();
		closeReadback();
	}

	private static void closeWorld() {
		expandedTarget = null;
		if (worldView != null) {
			worldView.close();
			worldView = null;
		}
		if (world != null) {
			world.close();
			world = null;
		}
	}

	private static void closeReadback() {
		if (readbackView != null) {
			readbackView.close();
			readbackView = null;
		}
		if (readback != null) {
			readback.close();
			readback = null;
		}
	}
}
