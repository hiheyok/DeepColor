package dev.deepcolor.core;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import dev.deepcolor.config.DeepColorConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/** Opt-in test-only benchmark. No instrumentation or baseline shader is shipped in the mod jar. */
public final class HdrPerformanceBenchmark {
    private static final int DRAWS = 16;
    private static final int ROUNDS = 20;
    private static final BindGroupLayout SAMPLERS = BindGroupLayout.builder()
        .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("VignetteSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private record Mode(String name, boolean baseline, float contrast, float noise, float floor, boolean pulses) {}
    private static final Mode[] MODES = {
        new Mode("pre_changes", true, 0, 0, 0, false),
        new Mode("off", false, 0, 0, 0, false),
        new Mode("contrast_50", false, .5F, 0, 0, false),
        new Mode("noise_2", false, 0, 2, 0, false),
        new Mode("noise_8", false, 0, 8, 0, false),
        new Mode("smooth_preset", false, 0, 2, .01F, true),
        new Mode("all_aggressive", false, .95F, 8, .05F, true)
    };
    private static final List<Long> FRAMES = new ArrayList<>();
    private static boolean collecting;
    private static long previousFrame;

    /** Called only from the test mixin, at the start of renderFrame; samples actual frame cadence. */
    public static void frame() {
        if (!collecting) return;
        long now = System.nanoTime();
        if (previousFrame != 0) FRAMES.add(now - previousFrame);
        previousFrame = now;
    }

    public static void run(ClientGameTestContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commit", System.getProperty("deepcolor.bench.commit", "unknown"));
        result.put("baseline", System.getProperty("deepcolor.bench.baseline", "d0a216b") + ": historical HDR shader");
        result.put("method", "16 serial completed draws per sample, 20 interleaved samples per mode/scene/resolution");
        result.put("device", context.computeOnClient(mc -> RenderSystem.getDevice().getDeviceInfo().name()));
        result.put("backend", context.computeOnClient(mc -> RenderSystem.getDevice().getDeviceInfo().backendName()));
        result.put("gpu_samples", rows);
        System.out.println("[DeepColorBench] BEGIN " + result.get("device") + " " + result.get("backend"));
        context.runOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.pauseOnLostFocus = false;
            mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        if (!context.computeOnClient(mc -> Hdr.active())) throw new IllegalStateException("Benchmark requires active HDR");
        // Ensure the production pass/noise texture has been initialized before retrieving its texture.
        context.waitTicks(40);
        for (int[] size : new int[][] {{1920, 1080}, {2560, 1440}}) {
            for (String scene : List.of("near_black", "mixed", "sub_floor")) {
                Fixture fixture = context.computeOnClient(mc -> new Fixture(size[0], size[1], scene));
                try {
                    context.runOnClient(mc -> fixture.warm());
                    for (int round = 0; round < ROUNDS; round++) {
                        // Rotate and reverse order, keeping old/new/feature measurements interleaved.
                        for (int i = 0; i < MODES.length; i++) {
                            Mode mode = MODES[Math.floorMod(round + (round % 2 == 0 ? i : -i), MODES.length)];
                            int sample = round;
                            rows.add(context.computeOnClient(mc -> fixture.measure(mode, sample)));
                        }
                    }
                    System.out.println("[DeepColorBench] GPU " + size[0] + "x" + size[1] + " " + scene + " done");
                } finally { context.runOnClient(mc -> fixture.close()); }
            }
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        result.put("frame_samples", frames);
        try (var world = context.worldBuilder().create()) {
        world.getServer().runCommand("time set midnight");
        world.getServer().runCommand("gamerule minecraft:advance_time false");
        world.getServer().runCommand("tp @p 0.5 -60 0.5 45 30");
        world.getConnection().waitForClientboundPackets();
        world.getConnection().waitForChunksRender();
        context.setScreen(() -> null);
        context.waitTicks(60);
        for (int round = 0; round < 4; round++) {
            for (int i = 1; i < MODES.length; i++) {
                Mode mode = MODES[1 + Math.floorMod(round + (round % 2 == 0 ? i - 1 : 1 - i), MODES.length - 1)];
                context.runOnClient(mc -> configure(mode));
                context.waitTicks(10);
                context.runOnClient(mc -> { FRAMES.clear(); previousFrame = 0; collecting = true; });
                context.waitFor(mc -> FRAMES.size() >= 180, 600);
                List<Long> sample = context.computeOnClient(mc -> { collecting = false; return new ArrayList<>(FRAMES); });
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("mode", mode.name); row.put("round", round); row.put("frame_ns", sample);
                row.put("scene", "stationary_night_world");
                row.put("throttle", context.computeOnClient(mc -> mc.getFramerateLimitTracker().getThrottleReason().toString()));
                row.put("render_width", context.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width));
                row.put("render_height", context.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().height));
                frames.add(row);
                System.out.println("[DeepColorBench] FRAME " + mode.name + " round " + round + " done");
            }
        }
        }
        context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        context.waitTicks(3);
        try { Files.writeString(Path.of("hdr-performance.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
        catch (Exception e) { throw new RuntimeException(e); }
        System.out.println("[DeepColorBench] COMPLETE hdr-performance.json");
    }

    private static void configure(Mode mode) {
        DeepColorConfig config = DeepColorConfig.get();
        config.hdrShadowContrast = mode.contrast; config.hdrShadowContrastRange = 2;
        config.hdrShadowDither = mode.noise; config.hdrShadowFloor = mode.floor;
        config.hdrFloorDither = mode.pulses; config.dither = true; config.temporal = true;
        config.hdrUiNits = 203; config.hdrPeakNits = 1000; config.hdrGamma = 2.2F; config.hdrGamutStretch = 0;
    }

    private static RenderPipeline pipeline(String shader, String id) {
        return RenderPipeline.builder(RenderPipeline.builder().buildSnippet())
            .withLocation(Identifier.fromNamespaceAndPath("deepcolor", "pipeline/perf_" + id))
            .withVertexShader(Identifier.fromNamespaceAndPath("deepcolor", "fullscreen"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("deepcolor", shader))
            .withBindGroupLayout(SAMPLERS).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withPushConstantSize(112)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL)).build();
    }

    private static final class Fixture implements AutoCloseable {
        final int width, height; final String scene;
        final GpuTexture source, destination, noise;
        final GpuTextureView input, output, noiseView;
        final CompiledRenderPipeline oldPipeline, currentPipeline;
        final GpuQueryPool queries;
        boolean timestamps;
        final ByteBuffer constants = MemoryUtil.memAlloc(112).order(ByteOrder.nativeOrder());
        Fixture(int width, int height, String scene) {
            this.width = width; this.height = height; this.scene = scene;
            var device = RenderSystem.getDevice();
            source = device.createTexture("perf input", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            destination = device.createTexture("perf output", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            input = device.createTextureView(source); output = device.createTextureView(destination);
            noise = device.createTexture("perf blue noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 64, 64, 1, 1);
            noiseView = device.createTextureView(noise);
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 8);
            try {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    float u = (x + .5F) / width;
                    float c = scene.equals("near_black") ? u * 32 / 255 : scene.equals("sub_floor") ? (.25F + u) / 255 : u;
                    pixels.putShort(Float.floatToFloat16(c)).putShort(Float.floatToFloat16(c * .9F)).putShort(Float.floatToFloat16(c * .7F)).putShort(Float.floatToFloat16(1));
                }
                pixels.flip(); device.createCommandEncoder().writeToTexture(source, pixels, 0, 0, 0, 0, width, height);
            } finally { MemoryUtil.memFree(pixels); }
            pixels = MemoryUtil.memAlloc(64 * 64 * 4);
            try {
                for (int rank : BlueNoise.generateRanks()) pixels.put((byte)(rank >> 8)).put((byte)rank).put((byte)0).put((byte)255);
                pixels.flip(); device.createCommandEncoder().writeToTexture(noise, pixels, 0, 0, 0, 0, 64, 64);
            } finally { MemoryUtil.memFree(pixels); }
            oldPipeline = ModPipelines.get(pipeline("perf_hdr_baseline", "old"));
            currentPipeline = ModPipelines.get(pipeline("dither", "current"));
            timestamps = !OutputState.isOpenGl() || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_timer_query;
            queries = timestamps ? device.createTimestampQueryPool(2) : null;
            drain();
            verify();
        }
        void verify() {
            draw(MODES[1], 0);
            var device = RenderSystem.getDevice();
            var buffer = device.createBuffer(() -> "perf validation", com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_READ | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST, (long) width * height * 8);
            device.createCommandEncoder().copyTextureToBuffer(destination, buffer, 0, () -> {}, 0);
            drain();
            try (buffer; var mapped = buffer.map(true, false)) {
                float got = Float.float16ToFloat(mapped.data().getShort(((height / 2) * width + width / 2) * 8));
                float u = (width / 2 + .5F) / width;
                float c = scene.equals("near_black") ? u * 32 / 255 : scene.equals("sub_floor") ? (.25F + u) / 255 : u;
                double expected = Math.pow(c, 2.2) * 203 / Hdr.unitNits();
                if (!(got > 0) || Math.abs(got - expected) > Math.max(1e-7, expected * .01)) throw new IllegalStateException("Invalid fixture output: " + got + " expected " + expected);
            }
        }
        void draw(Mode mode, int index) {
            var device = RenderSystem.getDevice();
            try (var pass = device.createCommandEncoder().createRenderPass(() -> "HDR performance", output, Optional.empty())) {
                pass.setPipeline(mode.baseline ? oldPipeline : currentPipeline);
                var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.setUniform("InSampler", input, nearest); pass.setUniform("NoiseSampler", noiseView, nearest);
                pass.setUniform("WorldSampler", noiseView, nearest); pass.setUniform("VignetteSampler", noiseView, nearest);
                ByteBuffer p = constants.clear();
                p.putFloat(1F/1023).putFloat(1F/65535).putFloat(1).putFloat((index * .61803398875F) % 1);
                p.putInt(0).putInt(0).putInt(1).putFloat(2.2F);
                p.putFloat(203 / Hdr.unitNits()).putFloat(1000 / Hdr.unitNits()).putInt(1).putFloat(mode.floor / Hdr.unitNits());
                p.putFloat(0).putInt(0).putFloat(Hdr.unitNits()).putFloat(0).putFloat(0).putInt(0).putInt(0);
                p.putFloat(0).putFloat(0).putFloat(0).putFloat(mode.noise).putFloat(mode.contrast).putFloat(2).putInt(mode.pulses ? 1 : 0).putFloat(0).putInt(0);
                p.flip(); pass.pushConstants(p); pass.draw(3, 1, 0, 0);
            }
        }
        void warm() { for (Mode mode : MODES) for (int i = 0; i < 48; i++) draw(mode, i); drain(); }
        Map<String, Object> measure(Mode mode, int round) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            drain();
            long start = System.nanoTime();
            double gpuNs = 0;
            boolean validGpu = timestamps;
            for (int i = 0; i < DRAWS; i++) {
                if (timestamps) encoder.writeTimestamp(queries, 0);
                draw(mode, i + round * DRAWS);
                if (timestamps) encoder.writeTimestamp(queries, 1);
                // Finish every draw: prevent tile GPUs from coalescing overwritten output passes.
                drain();
                if (timestamps) {
                    long first = queries.getValue(0).orElse(0), last = queries.getValue(1).orElse(0);
                    if (last > first) gpuNs += (last - first) * RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
                    else {
                        validGpu = false; timestamps = false;
                        System.out.println("[DeepColorBench] GPU timestamps unavailable; synchronized wall timing only");
                    }
                }
            }
            long end = System.nanoTime();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("width", width); row.put("height", height); row.put("scene", scene); row.put("mode", mode.name); row.put("round", round);
            row.put("draws", DRAWS); row.put("synchronized_ns_per_draw", (end - start) / (double) DRAWS);
            if (validGpu) row.put("gpu_ns_per_draw", gpuNs / DRAWS);
            return row;
        }
        public void close() { drain(); MemoryUtil.memFree(constants); if (queries != null) queries.close(); input.close(); output.close(); noiseView.close(); source.close(); destination.close(); noise.close(); }
    }
    private static void drain() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var fence = encoder.createFence()) { encoder.submit(); if (!fence.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("GPU fence timeout"); }
        if (OutputState.isOpenGl()) org.lwjgl.opengl.GL11.glFinish();
    }
}
