package dev.deepcolor.mixin.pipeline;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.deepcolor.core.PipelineRouter;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
	@Shadow
	@Final
	private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private CompiledRenderPipeline deepcolor$routePipeline(CompiledRenderPipeline pipeline) {
		return PipelineRouter.route(pipeline, this.colorAttachments);
	}
}
