package dev.deepcolor.core;

import java.nio.IntBuffer;
import java.util.Locale;
import java.util.function.Supplier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/** Raw GL queries on the default framebuffer. Only call these on the OpenGL backend. */
final class GlOutput {
	record Result(int bits, boolean floating, String description) {
	}

	private GlOutput() {
	}

	/** Runs with the default framebuffer bound, then restores what was bound before. */
	private static <T> T withDefaultFramebuffer(Supplier<T> action) {
		int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
		try {
			return action.get();
		} finally {
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
		}
	}

	private static int attachmentSize(int parameter) {
		return GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER, GL11C.GL_BACK_LEFT, parameter);
	}

	static Result query() {
		return withDefaultFramebuffer(() -> {
			int red = attachmentSize(GL30C.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE);
			int green = attachmentSize(GL30C.GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE);
			int blue = attachmentSize(GL30C.GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE);
			int alpha = attachmentSize(GL30C.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
			boolean floating = attachmentSize(GL30C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE) == GL11C.GL_FLOAT;
			String description = String.format(Locale.ROOT, "GL default framebuffer R%dG%dB%dA%d%s", red, green, blue, alpha, floating ? " float (scRGB)" : "");
			return new Result(Math.min(red, Math.min(green, blue)), floating, description);
		});
	}

	/** Reads the red channel of the back buffer as 10-bit codes, whatever its real depth is. */
	static int[] readBackBufferRed(int width, int height) {
		return withDefaultFramebuffer(() -> {
			IntBuffer pixels = MemoryUtil.memAllocInt(width * height);
			try {
				GL11C.glReadBuffer(GL11C.GL_BACK);
				GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL30C.GL_UNSIGNED_INT_2_10_10_10_REV, pixels);
				int[] red = new int[width * height];
				for (int i = 0; i < red.length; i++) {
					red[i] = pixels.get(i) & 0x3FF;
				}
				return red;
			} finally {
				MemoryUtil.memFree(pixels);
			}
		});
	}
}
