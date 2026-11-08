package dev.deepcolor.mixin.pipeline;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import dev.deepcolor.core.PipelineRouter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Every pipeline in the game, from any mod, is compiled here. On Vulkan the color format is baked
 * in, so this is where RGBA8 becomes the internal format. Both backends preserve coverage alpha
 * for additive nausea.
 */
@Mixin(PipelineBuilder.class)
abstract class PipelineBuilderMixin {
	@Shadow
	@Final
	private GpuDeviceBackend backendDevice;

	@WrapMethod(method = "compilePipeline")
	private CompletableFuture<CompiledRenderPipeline.Pending> deepcolor$compilePipeline(
		RenderPipeline pipeline, ShaderSource shaderSource, Executor executor, Operation<CompletableFuture<CompiledRenderPipeline.Pending>> original
	) {
		RenderPipeline prepared = PipelineRouter.preserveOverlayCoverage(pipeline);
		if (!(this.backendDevice instanceof VulkanDevice)) {
			return original.call(prepared, shaderSource, executor);
		}
		return original.call(PipelineRouter.upgradeForCompile(prepared), shaderSource, executor).thenApply(pending -> () -> {
			CompiledRenderPipeline compiled = pending.finishCompile();
			if (compiled != null) {
				PipelineRouter.remember(compiled, prepared, shaderSource);
			}
			return compiled;
		});
	}
}
