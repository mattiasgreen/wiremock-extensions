package com.github.mattiasgreen.wiremock.examples.banking;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

/**
 * Headless demo cursor motion and spotlight helper for Playwright video tours.
 * <p>
 * Injects a sleek synthetic cursor overlay with click ripples and provides smooth
 * gliding motion (150-250ms) to give viewers visual causality without sluggishness.
 */
public class DemoCursorHelper {

    private static final int DEFAULT_STEPS = 14;
    private static final int HOVER_SETTLE_MS = 120;

    private static final String CURSOR_INIT_SCRIPT =
            """
            (() => {
              const CURSOR_ID = '__demo_playwright_cursor__';
              function ensureCursor() {
                if (document.getElementById(CURSOR_ID)) return;
                const container = document.createElement('div');
                container.id = CURSOR_ID;
                container.style.display = 'none';
                container.innerHTML = `
                  <style>
                    #__demo_playwright_cursor__ {
                      position: fixed;
                      top: 0;
                      left: 0;
                      width: 18px;
                      height: 18px;
                      border-radius: 50%;
                      background: rgba(37, 99, 235, 0.85);
                      border: 2px solid #ffffff;
                      box-shadow: 0 0 10px rgba(59, 130, 246, 0.7), 0 2px 6px rgba(0, 0, 0, 0.4);
                      pointer-events: none;
                      z-index: 2147483647;
                      transform: translate(-50%, -50%);
                      transition: transform 0.12s ease-out, background-color 0.12s ease-out, box-shadow 0.12s ease-out;
                    }
                    #__demo_playwright_cursor__.active {
                      transform: translate(-50%, -50%) scale(0.82);
                      background: rgba(29, 78, 216, 0.95);
                    }
                    #__demo_playwright_cursor__.spotlight {
                      transform: translate(-50%, -50%) scale(1.6);
                      background: rgba(234, 179, 8, 0.75);
                      border-color: #fef08a;
                      box-shadow: 0 0 18px rgba(234, 179, 8, 0.9), 0 0 30px rgba(234, 179, 8, 0.5);
                    }
                    .__demo_ripple__ {
                      position: fixed;
                      border-radius: 50%;
                      border: 2px solid rgba(59, 130, 246, 0.85);
                      background: rgba(59, 130, 246, 0.25);
                      pointer-events: none;
                      z-index: 2147483646;
                      transform: translate(-50%, -50%) scale(0.2);
                      animation: __demo_ripple_anim__ 0.35s cubic-bezier(0, 0, 0.2, 1) forwards;
                    }
                    @keyframes __demo_ripple_anim__ {
                      to {
                        transform: translate(-50%, -50%) scale(2.4);
                        opacity: 0;
                      }
                    }
                  </style>
                `;
                document.documentElement.appendChild(container);

                window.addEventListener('mousemove', (e) => {
                  container.style.display = 'block';
                  container.style.left = e.clientX + 'px';
                  container.style.top = e.clientY + 'px';
                }, { passive: true, capture: true });

                window.addEventListener('mousedown', (e) => {
                  container.classList.add('active');
                  const rip = document.createElement('div');
                  rip.className = '__demo_ripple__';
                  rip.style.left = e.clientX + 'px';
                  rip.style.top = e.clientY + 'px';
                  rip.style.width = '26px';
                  rip.style.height = '26px';
                  document.documentElement.appendChild(rip);
                  setTimeout(() => rip.remove(), 400);
                }, { passive: true, capture: true });

                window.addEventListener('mouseup', () => {
                  container.classList.remove('active');
                }, { passive: true, capture: true });
              }

              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', ensureCursor);
              } else {
                ensureCursor();
              }
            })();
            """;

    private final Page page;
    private double currentX = 640;
    private double currentY = 400;

    public DemoCursorHelper(Page page) {
        this.page = page;
    }

    /**
     * Injects the cursor and ripple overlay script into the browser context.
     * Must be called before navigating pages.
     */
    public static void attachToContext(BrowserContext context) {
        context.addInitScript(CURSOR_INIT_SCRIPT);
    }

    /**
     * Smoothly glides the cursor to the target element's center.
     */
    public void glideTo(Locator locator) {
        glideTo(locator, DEFAULT_STEPS, HOVER_SETTLE_MS);
    }

    /**
     * Smoothly glides the cursor to the target element with custom steps and settle time.
     */
    public void glideTo(Locator locator, int steps, int settleMs) {
        locator.scrollIntoViewIfNeeded();
        BoundingBox box = locator.boundingBox();
        if (box == null) {
            return;
        }
        double targetX = box.x + box.width / 2.0;
        double targetY = box.y + box.height / 2.0;

        page.mouse().move(targetX, targetY, new Mouse.MoveOptions().setSteps(steps));
        currentX = targetX;
        currentY = targetY;

        if (settleMs > 0) {
            page.waitForTimeout(settleMs);
        }
    }

    /**
     * Glides to the target element and clicks it, triggering visual cursor ripples.
     */
    public void glideAndClick(Locator locator) {
        glideTo(locator, DEFAULT_STEPS, HOVER_SETTLE_MS);
        locator.click();
    }

    /**
     * Glides to a select element, clicks it to highlight, and selects the specified option.
     */
    public void glideAndSelect(Locator locator, String value) {
        glideTo(locator, DEFAULT_STEPS, HOVER_SETTLE_MS);
        locator.selectOption(value);
        page.waitForTimeout(HOVER_SETTLE_MS);
    }

    /**
     * Momentarily spotlights an element with a glowing aura to draw viewer attention.
     */
    public void spotlight(Locator locator, int durationMs) {
        glideTo(locator, DEFAULT_STEPS, 60);
        page.evaluate(
                "() => { const c = document.getElementById('__demo_playwright_cursor__'); if (c) c.classList.add('spotlight'); }");
        page.waitForTimeout(durationMs);
        page.evaluate(
                "() => { const c = document.getElementById('__demo_playwright_cursor__'); if (c) c.classList.remove('spotlight'); }");
    }

    public double getCurrentX() {
        return currentX;
    }

    public double getCurrentY() {
        return currentY;
    }
}
