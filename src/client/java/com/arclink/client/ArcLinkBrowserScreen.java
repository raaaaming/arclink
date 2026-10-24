package com.arclink.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

/**
 * Renders an MCEF (Chromium) browser as a full-screen overlay. Opened by
 * {@link OpenBrowserCommand} in response to a server-pushed {@code open_browser}
 * signal; the URL itself always points at the plugin's own signed-URL file
 * server (see {@link OpenBrowserCommand}), never an arbitrary server-supplied host.
 *
 * <p>Rendering follows Yarn 1.21.1's mapped GL wrapper API directly (Tessellator /
 * BufferBuilder / BufferRenderer) rather than {@link DrawContext}'s higher-level
 * draw calls, because we're blitting an externally-managed GL texture
 * ({@link com.cinemamod.mcef.MCEFRenderer#getTextureID()}) that DrawContext has no
 * blit overload for. This runs while the GUI projection/viewport is already
 * gui-scaled, so vertices in plain screen-space (0..width, 0..height) line up
 * without needing DrawContext's matrix stack.
 *
 * <p><b>2026-09-27 재작성:</b> "슈퍼샘플링(2배 해상도) + GL_LINEAR 강제" 두 차례 시도 모두 사용자가
 * 흐림에 아무 변화가 없다고 확인했고, 결정적으로 동일한 MCEF 2.1.6-1.21.1을 쓰는 사용자의 다른
 * 프로젝트("봉켓몬 카피"의 {@code McefScreen})는 별다른 조치 없이도 선명하게 렌더링된다는 사실이
 * 확인됐다 — 즉 "CEF OSR은 원래 그레이스케일 AA라 흐리다"는 전제 자체가 틀렸다. 이 클래스를 그
 * 검증된 참고 구현과 최대한 동일하게(1배 해상도, {@code createBrowser}의 transparent 플래그,
 * 셰이더/버텍스 포맷) 맞춰 어느 쪽이 실제 원인인지 격리하는 진단 실험으로 재작성했다 — 자세한 대조
 * 내용은 DECISIONS.md "상점 화면이 흐릿하게 보임" 참고.
 */
public final class ArcLinkBrowserScreen extends Screen {

	private final String url;
	private MCEFBrowser browser;
	private int browserWidth;
	private int browserHeight;

	public ArcLinkBrowserScreen(String url) {
		super(Text.literal("ARCLink"));
		this.url = url;
	}

	@Override
	protected void init() {
		browserWidth = pixelWidth();
		browserHeight = pixelHeight();
		// 두 번째 인자(transparent)=true — 검증된 참고 구현(McefScreen)과 동일하게 맞춤. 이 값이
		// 실제로 흐림에 영향을 주는지는 확인된 바 없지만, createBrowser() 호출 자체에서 두 구현이
		// 다르던 유일한 지점이라 격리 실험 차원에서 우선 맞춘다.
		browser = MCEF.createBrowser(url, true, browserWidth, browserHeight);
	}

	private int pixelWidth() {
		return Math.max(1, this.client.getWindow().getFramebufferWidth());
	}

	private int pixelHeight() {
		return Math.max(1, this.client.getWindow().getFramebufferHeight());
	}

	@Override
	public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
		super.resize(client, width, height);
		int w = pixelWidth();
		int h = pixelHeight();
		if (browser != null && (w != browserWidth || h != browserHeight)) {
			browser.resize(w, h);
			browserWidth = w;
			browserHeight = h;
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xFF000000);

		if (browser != null) {
			// 셰이더도 참고 구현과 동일하게 POSITION_TEX(+ setShaderColor 유니폼)로 맞춤 — 원래
			// POSITION_TEX_COLOR(정점마다 색 255,255,255,255)와 수학적으로는 동일해야 하지만,
			// 격리 실험이므로 차이를 하나도 남기지 않는다.
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShader(GameRenderer::getPositionTexProgram);
			RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
			buffer.vertex(0, this.height, 0).texture(0f, 1f);
			buffer.vertex(this.width, this.height, 0).texture(1f, 1f);
			buffer.vertex(this.width, 0, 0).texture(1f, 0f);
			buffer.vertex(0, 0, 0).texture(0f, 0f);
			BufferRenderer.drawWithGlobalProgram(buffer.end());

			RenderSystem.disableBlend();
		}

		super.render(context, mouseX, mouseY, delta);
	}

	/**
	 * 바닐라 메뉴 배경을 끈다. MC 1.21.1의 {@code Screen.render()}는 맨 먼저 이 메서드를 부르고,
	 * 기본 구현은 월드 안에서 {@code applyBlur()}(반경 = 접근성 설정 "메뉴 배경 흐림", 기본 5)로
	 * 메인 프레임버퍼 전체에 블러를 건 뒤 {@code renderDarkening()}으로 반투명 어두운 텍스처
	 * ({@code inworld_menu_background.png})를 덮는다. 브라우저를 먼저 그리고 {@code super.render()}를
	 * 부르는 구조라 그 두 효과가 브라우저 위에 그대로 씌워져 화면이 흐리고 어둡게 보였다
	 * (DECISIONS.md "근본 원인 확정" 참고). 브라우저가 화면 전체를 덮으므로 배경은 필요 없다 —
	 * {@code super.render()}는 나중에 위젯을 붙일 때를 위해 유지하고 배경만 무력화한다.
	 */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// 의도적으로 비움 — 블러/어둡게 하기 없음.
	}

	@Override
	public boolean shouldPause() {
		// A server-pushed UI must not freeze a singleplayer world behind it.
		return false;
	}

	@Override
	public void close() {
		if (browser != null) {
			browser.close();
			browser = null;
		}
		super.close();
	}

	// --- Input forwarding: everything is handed straight to the embedded browser. ---
	//
	// Screen's mouseX/mouseY arrive in Minecraft's GUI-scaled coordinate space (0..this.width,
	// 0..this.height), but the browser's own coordinate space is browserWidth x browserHeight
	// (physical framebuffer pixels) — those only happen to match when Minecraft's GUI Scale is 1.
	// toBrowserX/Y() convert every point so clicks land in the right place regardless of GUI Scale.

	private int toBrowserX(double mouseX) {
		if (this.width <= 0) return 0;
		return (int) Math.round(mouseX * browserWidth / (double) this.width);
	}

	private int toBrowserY(double mouseY) {
		if (this.height <= 0) return 0;
		return (int) Math.round(mouseY * browserHeight / (double) this.height);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (browser != null) {
			browser.sendMousePress(toBrowserX(mouseX), toBrowserY(mouseY), button);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (browser != null) {
			browser.sendMouseRelease(toBrowserX(mouseX), toBrowserY(mouseY), button);
		}
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (browser != null) {
			browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
		}
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (browser != null) {
			browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (browser != null) {
			browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY), verticalAmount, 0);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (browser != null) {
			browser.sendKeyPress(keyCode, scanCode, modifiers);
		}
		// Let Escape still close the overlay (default Screen behavior) instead of swallowing it.
		if (keyCode == InputUtil.GLFW_KEY_ESCAPE) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		return true;
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (browser != null) {
			browser.sendKeyRelease(keyCode, scanCode, modifiers);
		}
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (browser != null) {
			browser.sendKeyTyped(chr, modifiers);
		}
		return true;
	}
}
