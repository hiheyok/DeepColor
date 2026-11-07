package dev.deepcolor.core;

import java.nio.IntBuffer;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
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

	/**
	 * Reads the red channel of the back buffer as 10-bit codes, whatever its real depth is.
	 *
	 * <p>The image is blitted to the top-left corner of the back buffer. Normally both have the same
	 * size, but when the game renders at a size other than the window's pixel size (the client
	 * gametest pins it to 854x480, and on a HiDPI display such as a Retina Mac the window then has
	 * twice as many pixels), the image covers only that corner, so only that corner is read.
	 */
	static int[] readBackBufferRed(int width, int height) {
		int top = Math.max(0, drawableSize(width, height)[1] - height);
		return withDefaultFramebuffer(() -> {
			IntBuffer pixels = MemoryUtil.memAllocInt(width * height);
			// Pack state left behind by other code (the game leaves GL_PACK_ROW_LENGTH set, for one)
			// would change the layout of the result, so tightly packed rows are requested here.
			int rowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
			int skipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
			int skipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
			int alignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
			int packBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
			try {
				GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
				GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
				GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
				GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
				GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
				GL11C.glReadBuffer(GL11C.GL_BACK);
				GL11C.glReadPixels(0, top, width, height, GL11C.GL_RGBA, GL30C.GL_UNSIGNED_INT_2_10_10_10_REV, pixels);
				int[] red = new int[width * height];
				for (int i = 0; i < red.length; i++) {
					red[i] = pixels.get(i) & 0x3FF;
				}
				return red;
			} finally {
				GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, rowLength);
				GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows);
				GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels);
				GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment);
				GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, packBuffer);
				MemoryUtil.memFree(pixels);
			}
		});
	}

	/** Size of the window's back buffer in pixels, falling back to the image size if SDL cannot tell. */
	private static int[] drawableSize(int width, int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer w = stack.mallocInt(1);
			IntBuffer h = stack.mallocInt(1);
			if (SDLVideo.SDL_GetWindowSizeInPixels(Minecraft.getInstance().getWindow().handle(), w, h) && w.get(0) > 0 && h.get(0) > 0) {
				return new int[]{w.get(0), h.get(0)};
			}
		}
		return new int[]{width, height};
	}
}
