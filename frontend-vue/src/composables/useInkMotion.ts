import { onBeforeUnmount, onMounted, ref } from 'vue';

/** 纸叠倾斜：鼠标位置写到两个自定义属性，变换本身交给 CSS。 */
export function useInkTilt(strength = 9) {
  const target = ref<HTMLElement | null>(null);
  let frame = 0;

  function apply(event: MouseEvent) {
    const el = target.value;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const px = (event.clientX - rect.left) / rect.width - 0.5;
    const py = (event.clientY - rect.top) / rect.height - 0.5;
    el.style.setProperty('--ink-tilt-y', `${(px * strength).toFixed(2)}deg`);
    el.style.setProperty('--ink-tilt-x', `${(-py * strength).toFixed(2)}deg`);
  }

  function onMove(event: MouseEvent) {
    if (frame) return;
    frame = window.requestAnimationFrame(() => {
      frame = 0;
      apply(event);
    });
  }

  function onLeave() {
    const el = target.value;
    if (!el) return;
    el.style.setProperty('--ink-tilt-y', '0deg');
    el.style.setProperty('--ink-tilt-x', '0deg');
  }

  onMounted(() => {
    const el = target.value;
    if (!el) return;
    el.addEventListener('mousemove', onMove);
    el.addEventListener('mouseleave', onLeave);
  });

  onBeforeUnmount(() => {
    const el = target.value;
    if (frame) window.cancelAnimationFrame(frame);
    if (!el) return;
    el.removeEventListener('mousemove', onMove);
    el.removeEventListener('mouseleave', onLeave);
  });

  return { target };
}

/** 入场观察：没有观察器时直接显示，避免内容被藏住。 */
export function observeInkReveal(root: ParentNode = document): () => void {
  const nodes = Array.from(root.querySelectorAll<HTMLElement>('.ink-reveal'));
  if (typeof IntersectionObserver === 'undefined') {
    nodes.forEach((node) => node.classList.add('is-in'));
    return () => undefined;
  }
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.08, rootMargin: '0px 0px -40px' },
  );
  nodes.forEach((node) => observer.observe(node));
  return () => observer.disconnect();
}
