"""Rasterize a figure authored as HTML with inline SVG (the diagram-design format) into a PNG.

The procedure follows the plugin's export reference: render the HTML itself, wait for fonts, size the SVG to its viewBox and screenshot that element alone. Requires playwright with its chromium build installed (python -m playwright install chromium).
"""
import math
import pathlib
import sys

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError, sync_playwright


def render(src: pathlib.Path, out: pathlib.Path, scale: float = 3) -> None:
    if not math.isfinite(scale) or not 1 <= scale <= 4:
        raise ValueError("PNG scale must be a finite number between 1 and 4")
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(device_scale_factor=scale)
        page.goto(src.resolve().as_uri() + "?motion=static", wait_until="domcontentloaded")
        try:
            page.wait_for_load_state("networkidle", timeout=15000)
        except PlaywrightTimeoutError:
            page.evaluate("window.stop()")
            page.wait_for_timeout(4000)
            print("warning: a network request stalled; captured with fallback typography", file=sys.stderr)
        page.evaluate("document.fonts.ready")
        svg = page.locator("svg").first
        svg.evaluate("""el => {
            const box = el.viewBox.baseVal;
            if (![box.width, box.height].every(n => Number.isFinite(n) && n > 0))
                throw new Error('PNG export requires a nonzero finite viewBox');
            for (const [axis, size] of [['width', box.width], ['height', box.height]])
                for (const prefix of ['', 'min-', 'max-'])
                    el.style.setProperty(prefix + axis, size + 'px', 'important');
        }""")
        svg.evaluate("el => { for (let a = el.parentElement; a; a = a.parentElement) a.style.setProperty('overflow', 'visible', 'important'); }")
        svg.screenshot(path=str(out), omit_background=False)
        browser.close()


if __name__ == "__main__":
    render(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), float(sys.argv[3]) if len(sys.argv) > 3 else 3)
