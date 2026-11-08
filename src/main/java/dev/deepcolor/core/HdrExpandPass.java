package dev.deepcolor.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Prepares the world for HDR brightness expansion, which the final pass applies when the frame is
 * shown. The main target itself keeps the plain SDR image throughout, so everything that reads it
 * (screenshots, world icons, the menu blur) sees what it would without HDR.
 *
 * <p>Runs once the world is finished and before the interface is drawn: it keeps a copy of the world
 * and sets the main target's alpha to 0. Interface drawn afterwards blends over the world and raises
 * alpha by how much it covers. The final pass then knows, per pixel, how much of it is world and
 * adds that share of the expansion. Overlays that only darken the world without touching alpha
 * (the vignette) are found by comparing with the copy, and darken the expanded world the same way.
 */
public final class HdrExpandPass {
	/** Most a gray at half brightness may gain from highlight expansion. */
	private static final float MIDTONE_GAIN = 0.15F;
	private static final int WORLD_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
	private static final RenderPipeline MARK_WORLD = RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
		.withLocation(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "pipeline/world_alpha"))
		.withVertexShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "fullscreen"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(DeepColor.MOD_ID, "world_alpha"))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALPHA))
		.build();

	/** The world as it was when the interface started drawing. */
	private static @Nullable GpuTexture world;
	private static @Nullable GpuTextureView worldView;
	/** Set when the current frame has a world to expand; cleared when the frame is shown. */
	private static boolean pending;
	private static float boost;
	private static DeepColorConfig.HdrMapping mapping = DeepColorConfig.HdrMapping.HIGHLIGHTS;
	private static boolean failed;
	private static boolean warnedFormat;

	private HdrExpandPass() {
	}

	/** After the post effects, before the interface. Only reached on frames that render a world. */
	public static void apply(RenderTarget main) {
		pending = false;
		DeepColorConfig config = DeepColorConfig.get();
		// Without the final pass, alpha 0 would reach the window, so never mark the world then.
		if (failed || DitherPass.failed() || config.hdrMapping == DeepColorConfig.HdrMapping.NONE || !Hdr.active()) {
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
		float frameBoost = Hdr.peakNits() / Hdr.uiNits() - 1.0F;
		if (frameBoost <= 0.001F) {
			return;
		}
		try {
			int width = color.getWidth(0);
			int height = color.getHeight(0);
			GpuDevice device = RenderSystem.getDevice();
			if (world == null || world.getWidth(0) != width || world.getHeight(0) != height) {
				close();
				world = device.createTexture(() -> "Deep Color world for HDR expansion", WORLD_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
				worldView = device.createTextureView(world);
			}
			device.createCommandEncoder().copyTextureToTexture(color, world, 0, 0, 0, 0, 0, width, height);
			try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Deep Color mark world", colorView, Optional.empty())) {
				pass.setPipeline(ModPipelines.get(MARK_WORLD));
				pass.draw(3, 1, 0, 0);
			}
			pending = true;
			boost = frameBoost;
			mapping = config.hdrMapping;
		} catch (RuntimeException e) {
			failed = true;
			DeepColor.LOGGER.error("Brightness expansion failed and is now disabled", e);
		}
	}

	/**
	 * The menu blur spreads the world's alpha 0 across the blurred image, which no longer lines up
	 * with the world copy. Such a frame shows its world without expansion, behind the menu.
	 */
	public static void onBlur() {
		pending = false;
	}

	/** The world copy, when the frame being shown has one to expand. Clears the frame's state. */
	static @Nullable GpuTextureView takeWorldForFrame() {
		boolean ready = pending;
		pending = false;
		return ready ? worldView : null;
	}

	static float boost() {
		return boost;
	}

	static float exponent() {
		return highlightExponent(boost);
	}

	static DeepColorConfig.HdrMapping mapping() {
		return mapping;
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
		pending = false;
		if (worldView != null) {
			worldView.close();
			worldView = null;
		}
		if (world != null) {
			world.close();
			world = null;
		}
	}
}
