package dev.deepcolor.mixin.pipeline;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Collection;
import java.util.Map;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderPipeline.class)
public interface RenderPipelineInvoker {
	@Invoker("<init>")
	static RenderPipeline deepcolor$create(
		Identifier location,
		Map<ShaderType, Identifier> shaders,
		ShaderDefines shaderDefines,
		Collection<BindGroupLayout> bindGroupLayouts,
		ColorTargetState[] colorTargetStates,
		DepthStencilState depthStencilState,
		PolygonMode polygonMode,
		boolean cull,
		VertexFormat[] vertexFormatPerBuffer,
		PrimitiveTopology primitiveTopology,
		int pushConstantSize,
		int sortKey
	) {
		throw new AssertionError();
	}
}
