(() => {
  const root = document.querySelector(".ob");
  const viewport = document.querySelector(".ob__viewport");
  const track = document.querySelector("[data-track]");
  if (!root || !viewport || !track) return;

  const pages = Array.from(root.querySelectorAll(".ob__page"));
  const total = pages.length;
  let step = 0;
  let startX = 0;
  let deltaX = 0;
  let dragging = false;

  const nextLabel = () => (step >= total - 1 ? "شروع" : "بعدی");

  function pageWidth() {
    return viewport.clientWidth || root.clientWidth || window.innerWidth || 1;
  }

  function setOffset(px, animate) {
    track.style.transition = animate ? "" : "none";
    track.style.transform = `translate3d(${-px}px, 0, 0)`;
  }

  function render(animate) {
    root.dataset.step = String(step);
    root.classList.toggle("is-last", step === total - 1);
    setOffset(step * pageWidth(), animate !== false);

    pages.forEach((page, index) => {
      const on = index === step;
      page.classList.toggle("is-on", on);
      page.setAttribute("aria-hidden", on ? "false" : "true");
      if (on) {
        page.querySelectorAll(".ob__art, .ob__copy").forEach((node) => {
          node.style.animation = "none";
          // force restart
          void node.offsetWidth;
          node.style.animation = "";
        });
      }
    });

    root.querySelectorAll(".ob__dot-btn").forEach((dot, index) => {
      const on = index === step;
      dot.classList.toggle("is-on", on);
      if (on) dot.setAttribute("aria-current", "true");
      else dot.removeAttribute("aria-current");
    });

    const next = root.querySelector('[data-action="next"]');
    if (next) next.textContent = nextLabel();
  }

  function go(to, animate) {
    step = Math.max(0, Math.min(total - 1, to));
    render(animate);
  }

  function finish() {
    root.dispatchEvent(
      new CustomEvent("onboarding:done", {
        bubbles: true,
        detail: { skipped: false, step },
      }),
    );
    const payload = JSON.stringify({ skipped: false, step });
    if (typeof window.OnboardingBridge?.complete === "function") {
      window.OnboardingBridge.complete(payload);
    } else if (typeof window.OnboardingBridge?.onFinished === "function") {
      window.OnboardingBridge.onFinished(payload);
    }
  }

  root.addEventListener("click", (event) => {
    const target = event.target.closest("[data-action], [data-goto]");
    if (!target) return;

    if (target.hasAttribute("data-goto")) {
      go(Number(target.getAttribute("data-goto")));
      return;
    }

    if (target.getAttribute("data-action") === "next") {
      if (step >= total - 1) finish();
      else go(step + 1);
    }
  });

  function onPointerDown(event) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    dragging = true;
    startX = event.clientX;
    deltaX = 0;
    track.setPointerCapture?.(event.pointerId);
    setOffset(step * pageWidth() - 0, false);
  }

  function onPointerMove(event) {
    if (!dragging) return;
    deltaX = event.clientX - startX;
    setOffset(step * pageWidth() - deltaX, false);
  }

  function onPointerUp() {
    if (!dragging) return;
    dragging = false;
    const threshold = Math.min(72, pageWidth() * 0.18);
    if (deltaX <= -threshold) go(step + 1);
    else if (deltaX >= threshold) go(step - 1);
    else render(true);
    deltaX = 0;
  }

  track.addEventListener("pointerdown", onPointerDown);
  track.addEventListener("pointermove", onPointerMove);
  track.addEventListener("pointerup", onPointerUp);
  track.addEventListener("pointercancel", onPointerUp);
  track.addEventListener("lostpointercapture", onPointerUp);
  window.addEventListener("resize", () => render(false));

  render(false);
})();
