package dev.deepcolor.mixin.pipeline;

import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.deepcolor.core.PipelineRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resource reloads close every pipeline and compile new ones, so the router must forget the old ones. */
@Mixin(FrontendRenderPipeline.class)
abstract class FrontendRenderPipelineMixin {
	@Inject(method = "close", at = @At("TAIL"))
	private void deepcolor$forget(CallbackInfo ci) {
		PipelineRouter.forget((FrontendRenderPipeline) (Object) this);
	}
}
