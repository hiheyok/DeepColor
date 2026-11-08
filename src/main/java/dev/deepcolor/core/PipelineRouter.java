package dev.deepcolor.core;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.deepcolor.DeepColor;
import dev.deepcolor.mixin.pipeline.RenderPipelineInvoker;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.Util;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Keeps render pipelines matched to their attachments once targets stop being RGBA8.
 *
 * <p>FrontendRenderPass.setPipeline rejects a pipeline whose color format differs from the
 * attachment. Pipelines cannot be sorted into "main target" and "other" in advance, because the
 * same pipeline can draw into both (a GUI item goes to the main target or to the RGBA8 item atlas),
 * so the choice is made here, at bind time:
 *
 * <ul>
 * <li>OpenGL: the format is only checked, never used, so the check is satisfied by a copy of the
 *     compiled record that shares the same GL program. This leaves Iris's program overrides alone.
 * <li>Vulkan: the format is baked into the VkPipeline. PipelineBuilder compiles everything for the
 *     internal format up front (see {@link #upgradeForCompile}), which covers the common case, and
 *     anything that then lands on another format gets a real variant compiled here.
 * </ul>
 *
 * Adapted from RenderPipelineFormatModifier in rrtt217's HDR Mod. Compiling at the single choke
 * point replaces its per-call-site upgrades, including the Sodium-specific mixin.
 */
public final class PipelineRouter {
	private record Source(RenderPipeline pipeline, ShaderSource shaderSource) {
	}

	private record Variant(GpuFormat[] formats, FrontendRenderPipeline compiled) {
	}

	/** Vulkan only. Written from compile threads, read on the render thread. */
	private static final Map<CompiledRenderPipeline, Source> SOURCES = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Render thread only. */
	private static final Map<FrontendRenderPipeline, List<Variant>> VARIANTS = new Reference2ObjectOpenHashMap<>();
	private static final Set<CompiledRenderPipeline> UNROUTABLE = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Set while compiling a variant, whose formats must be kept exactly as they are. */
	private static final ThreadLocal<Boolean> COMPILING_EXACT = ThreadLocal.withInitial(() -> false);

	private PipelineRouter() {
	}

	/** Returns the pipeline to compile on Vulkan: the same pipeline, with RGBA8 replaced. */
	public static RenderPipeline upgradeForCompile(RenderPipeline pipeline) {
		if (COMPILING_EXACT.get() || pipeline.getLocation().getNamespace().equals(DeepColor.MOD_ID)) {
			return pipeline;
		}
		List<ColorTargetState> states = pipeline.getColorTargetStates();
		GpuFormat[] formats = new GpuFormat[states.size()];
		boolean changed = false;
		for (int i = 0; i < formats.length; i++) {
			ColorTargetState state = states.get(i);
			if (state != null) {
				formats[i] = Formats.upgrade(state.format());
				changed |= formats[i] != state.format();
			}
		}
		return changed ? withFormats(pipeline, formats) : pipeline;
	}

	/** Remembers how a compiled pipeline was built so that variants of it can be compiled later. */
	public static void remember(CompiledRenderPipeline compiled, RenderPipeline source, ShaderSource shaderSource) {
		if (!COMPILING_EXACT.get()) {
			SOURCES.put(compiled, new Source(source, shaderSource));
		}
	}

	private static RenderPipeline withFormats(RenderPipeline pipeline, GpuFormat[] formats) {
		List<ColorTargetState> states = pipeline.getColorTargetStates();
		ColorTargetState[] replaced = new ColorTargetState[states.size()];
		for (int i = 0; i < replaced.length; i++) {
			ColorTargetState state = states.get(i);
			replaced[i] = state == null || formats[i] == null ? state : new ColorTargetState(state.blendFunction(), formats[i], state.writeMask());
		}
		return RenderPipelineInvoker.deepcolor$create(
			pipeline.getLocation(),
			pipeline.getShaders(),
			pipeline.getShaderDefines(),
			pipeline.getBindGroupLayouts(),
			replaced,
			pipeline.getDepthStencilState(),
			pipeline.getPolygonMode(),
			pipeline.isCull(),
			pipeline.getVertexFormatBindings().toArray(new VertexFormat[0]),
			pipeline.getPrimitiveTopology(),
			pipeline.pushConstantSize(),
			pipeline.getSortKey()
		);
	}

	/** Runs for every setPipeline, so the matching case must stay allocation free. */
	public static CompiledRenderPipeline route(CompiledRenderPipeline pipeline, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
		if (!(pipeline instanceof FrontendRenderPipeline frontend) || frontend.colorTargetStates().size() != attachments.size()) {
			return pipeline;
		}
		if (matches(frontend, attachments)) {
			return pipeline;
		}
		List<Variant> variants = VARIANTS.get(frontend);
		if (variants != null) {
			for (int i = 0; i < variants.size(); i++) {
				Variant variant = variants.get(i);
				if (sameFormats(variant.formats, attachments)) {
					return variant.compiled;
				}
			}
		}
		if (UNROUTABLE.contains(frontend)) {
			return pipeline;
		}
		dropClosedPipelines();
		GpuFormat[] formats = new GpuFormat[attachments.size()];
		for (int i = 0; i < formats.length; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(i);
			formats[i] = attachment == null ? null : attachment.textureView().texture().getFormat();
		}
		FrontendRenderPipeline variant = OutputState.isOpenGl() ? retargetCheckOnly(frontend, formats) : compileVariant(frontend, formats);
		if (variant == null) {
			UNROUTABLE.add(frontend);
			return pipeline;
		}
		VARIANTS.computeIfAbsent(frontend, key -> new ArrayList<>(2)).add(new Variant(formats, variant));
		return variant;
	}

	private static boolean matches(FrontendRenderPipeline pipeline, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
		List<ColorTargetState> states = pipeline.colorTargetStates();
		for (int i = 0; i < attachments.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(i);
			if (attachment != null) {
				ColorTargetState state = states.get(i);
				// A missing state is a real bug in the caller. Leave it for vanilla to report.
				if (state != null && state.format() != attachment.textureView().texture().getFormat()) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean sameFormats(GpuFormat[] formats, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
		for (int i = 0; i < formats.length; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(i);
			if (attachment != null && attachment.textureView().texture().getFormat() != formats[i]) {
				return false;
			}
		}
		return true;
	}

	private static FrontendRenderPipeline retargetCheckOnly(FrontendRenderPipeline pipeline, GpuFormat[] formats) {
		List<ColorTargetState> states = pipeline.colorTargetStates();
		List<ColorTargetState> replaced = new ArrayList<>(states.size());
		for (int i = 0; i < formats.length; i++) {
			ColorTargetState state = states.get(i);
			replaced.add(state == null || formats[i] == null ? state : new ColorTargetState(state.blendFunction(), formats[i], state.writeMask()));
		}
		return new FrontendRenderPipeline(
			pipeline.name(),
			pipeline.backendRenderPipeline(),
			pipeline.vertexFormats(),
			pipeline.uniformIndices(),
			pipeline.uniforms(),
			Collections.unmodifiableList(replaced),
			pipeline.wantsDepthTexture(),
			pipeline.pushConstantSize()
		);
	}

	private static @Nullable FrontendRenderPipeline compileVariant(FrontendRenderPipeline pipeline, GpuFormat[] formats) {
		Source source = SOURCES.get(pipeline);
		if (source == null) {
			DeepColor.LOGGER.warn("No source recorded for pipeline {}, cannot retarget it", pipeline.name());
			return null;
		}
		COMPILING_EXACT.set(true);
		try {
			CompiledRenderPipeline compiled = RenderSystem.getDevice()
				.compilePipeline(withFormats(source.pipeline, formats), source.shaderSource, Util.backgroundExecutor())
				.join()
				.finishCompile();
			return compiled instanceof FrontendRenderPipeline frontend ? frontend : null;
		} catch (RuntimeException e) {
			DeepColor.LOGGER.warn("Failed to compile a variant of pipeline {}", pipeline.name(), e);
			return null;
		} finally {
			COMPILING_EXACT.set(false);
		}
	}

	/** Variants belong to this class, so nothing else will close them before the device goes away. */
	public static void close() {
		if (!OutputState.isOpenGl()) {
			VARIANTS.values().forEach(variants -> variants.forEach(variant -> variant.compiled.close()));
		}
		VARIANTS.clear();
		SOURCES.clear();
		UNROUTABLE.clear();
	}

	/**
	 * Called when a pipeline is closed, which a resource reload does to every pipeline. Without this,
	 * pipelines whose formats always match (all of them with an RGBA8 internal target) would never hit
	 * the cache miss that runs {@link #dropClosedPipelines}, and every reload would keep the closed
	 * pipelines and their shader sources alive until exit. The router's own variants are closed
	 * here too, and they are never keys, so this does not recurse into anything that matters.
	 */
	public static void forget(CompiledRenderPipeline pipeline) {
		SOURCES.remove(pipeline);
		if (!RenderSystem.isOnRenderThread()) {
			// The render-thread maps are cleaned up on the next cache miss instead.
			return;
		}
		UNROUTABLE.remove(pipeline);
		List<Variant> variants = VARIANTS.remove(pipeline);
		if (variants != null && !OutputState.isOpenGl()) {
			variants.forEach(variant -> variant.compiled.close());
		}
	}

	/** Pipelines the router still holds data for, for tests. */
	public static int trackedPipelines() {
		synchronized (SOURCES) {
			return SOURCES.size() + VARIANTS.size() + UNROUTABLE.size();
		}
	}

	/** Called on a cache miss, which is rare after startup and always follows a shader reload. */
	private static void dropClosedPipelines() {
		VARIANTS.entrySet().removeIf(entry -> {
			if (!entry.getKey().isClosed()) {
				return false;
			}
			if (!OutputState.isOpenGl()) {
				entry.getValue().forEach(variant -> variant.compiled.close());
			}
			return true;
		});
		SOURCES.keySet().removeIf(CompiledRenderPipeline::isClosed);
		UNROUTABLE.removeIf(CompiledRenderPipeline::isClosed);
	}
}
