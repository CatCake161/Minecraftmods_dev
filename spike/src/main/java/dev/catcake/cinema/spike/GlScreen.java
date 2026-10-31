package dev.catcake.cinema.spike;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

final class GlScreen implements AutoCloseable {
	private final int width;
	private final int height;
	private final long window;
	private final GLFWErrorCallback errorCallback;
	private int texture;
	private int textureWidth = -1;
	private int textureHeight = -1;

	GlScreen(int width, int height, boolean visible) {
		this.width = width;
		this.height = height;

		errorCallback = GLFWErrorCallback.createPrint(System.err);
		errorCallback.set();
		if (!glfwInit()) {
			throw new IllegalStateException("glfwInit() failed");
		}

		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
		glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

		window = glfwCreateWindow(width, height, "cinema spike", NULL, NULL);
		if (window == NULL) {
			throw new IllegalStateException("glfwCreateWindow() failed");
		}

		glfwMakeContextCurrent(window);
		GL.createCapabilities();
		glfwSwapInterval(0);

		texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
	}

	long window() {
		return window;
	}

	void upload(ByteBuffer bgr, int frameWidth, int frameHeight) {
		bgr.rewind();
		glBindTexture(GL_TEXTURE_2D, texture);
		if (frameWidth != textureWidth || frameHeight != textureHeight) {
			textureWidth = frameWidth;
			textureHeight = frameHeight;
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frameWidth, frameHeight, 0,
				GL12.GL_BGR, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		}
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
			GL12.GL_BGR, GL_UNSIGNED_BYTE, bgr);
	}

	void draw() {
		glViewport(0, 0, width, height);
		glDisable(GL_DEPTH_TEST);
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT);

		glMatrixMode(GL_PROJECTION);
		glLoadIdentity();
		glOrtho(0, width, height, 0, -1, 1);
		glMatrixMode(GL_MODELVIEW);
		glLoadIdentity();

		glEnable(GL_TEXTURE_2D);
		glBindTexture(GL_TEXTURE_2D, texture);
		glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		glBegin(GL_QUADS);
		glTexCoord2f(0.0f, 1.0f);
		glVertex2f(0, 0);
		glTexCoord2f(1.0f, 1.0f);
		glVertex2f(width, 0);
		glTexCoord2f(1.0f, 0.0f);
		glVertex2f(width, height);
		glTexCoord2f(0.0f, 0.0f);
		glVertex2f(0, height);
		glEnd();
		glDisable(GL_TEXTURE_2D);

		glfwSwapBuffers(window);
		glfwPollEvents();
	}

	boolean shouldClose() {
		return glfwWindowShouldClose(window);
	}

	void pollEvents() {
		glfwPollEvents();
	}

	@Override
	public void close() {
		if (texture != 0) {
			glDeleteTextures(texture);
			texture = 0;
		}
		glfwDestroyWindow(window);
		glfwTerminate();
		errorCallback.free();
	}
}
