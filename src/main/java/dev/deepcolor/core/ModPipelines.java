package dev.deepcolor.core;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.deepcolor.DeepColor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Pipeline cache for this mod's own full-screen passes. It reads shaders straight from the mod jar
 * because the first frames (the loading overlay) are presented before resource packs, and therefore
 * vanilla's ShaderManager, are ready.
 */
final class ModPipelines {
	private static @Nullable PipelineCache cache;

	private ModPipelines() {
	}

	static CompiledRenderPipeline get(RenderPipeline pipeline) {
		if (cache == null) {
			cache = new PipelineCache(RenderSystem.getDevice(), new ClasspathShaderSource());
		}
		CompiledRenderPipeline compiled = cache.get(pipeline);
		if (compiled == null) {
			throw new IllegalStateException("Pipeline " + pipeline.getLocation() + " failed to compile");
		}
		return compiled;
	}

	static void close() {
		if (cache != null) {
			cache.close();
			cache = null;
		}
	}

	private static final class ClasspathShaderSource implements ShaderSource {
		@Override
		public @Nullable String getShader(Identifier id, ShaderType type) {
			Identifier file = type.idConverter().idToFile(id);
			String path = "/assets/" + file.getNamespace() + "/" + file.getPath();
			try (InputStream stream = ModPipelines.class.getResourceAsStream(path)) {
				return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				DeepColor.LOGGER.error("Could not read shader {}", path, e);
				return null;
			}
		}

		@Override
		public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
