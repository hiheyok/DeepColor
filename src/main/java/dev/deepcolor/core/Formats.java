package dev.deepcolor.core;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import net.minecraft.client.Screenshot;

/** Decides which format replaces RGBA8_UNORM on upgraded render targets. */
public final class Formats {
	private static GpuFormat internal;

	private Formats() {
	}

	public static synchronized GpuFormat internal() {
		if (internal == null) {
			internal = resolveInternal();
		}
		return internal;
	}

	private static GpuFormat resolveInternal() {
		GpuFormat configured = DeepColorConfig.get().internalFormat.format();
		if (configured == GpuFormat.RGBA8_UNORM) {
			return configured;
		}
		// Force these classes to load: their mixins apply on class load, and only then does Hooks
		// know whether they landed. Render targets are the first thing to ask for a format.
		try {
			Class.forName(FrontendRenderPass.class.getName());
			Class.forName(PipelineBuilder.class.getName());
			Class.forName(Screenshot.class.getName());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
		if (Hooks.hasFailed(Hooks.ROUTE_PIPELINE) || Hooks.hasFailed(Hooks.COMPILE_PIPELINE) || Hooks.hasFailed(Hooks.TAKE_SCREENSHOT)) {
			// A non-RGBA8 target without these hooks throws in setPipeline and corrupts screenshots.
			DeepColor.LOGGER.warn("Required hooks are missing, keeping RGBA8_UNORM render targets");
			return GpuFormat.RGBA8_UNORM;
		}
		if (Hdr.requested() && configured != GpuFormat.RGBA16_FLOAT) {
			DeepColor.LOGGER.warn("HDR output is on, but brightness expansion needs internalFormat RGBA16_FLOAT (it is {}). Only the interface brightness will apply.", configured);
		}
		return configured;
	}

	public static GpuFormat upgrade(GpuFormat format) {
		return format == GpuFormat.RGBA8_UNORM ? internal() : format;
	}
}
