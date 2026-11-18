/**
 * Install the feed rules in the content script's document. Dispose before applying new settings.
 * @param {Document} document
 * @param {Pick<import('../../../src/features/protection/native').ZenGuardStatus, 'shortsEnabled'>} settings
 * @returns {() => void}
 */
globalThis.installZenModeFeedRules = function (document, settings) {
  const window = document.defaultView;
  if (window.location.origin !== 'https://m.youtube.com' || settings?.shortsEnabled !== true) {
    return () => {};
  }

  const rules = [
    {
      path: /^\/$/,
      selector: 'body > ytm-app#app > ytm-pivot-bar-renderer[role="tablist"]:has(> ytm-pivot-bar-item-renderer > .pivot-w2w[role="tab"][aria-selected="true"])'
        + ' > ytm-pivot-bar-item-renderer:has(> .pivot-shorts[role="tab"])',
    },
    {
      path: /^\/shorts\/[\w-]{11}\/?$/,
      selector: 'body.page-shorts.shorts-carousel > #player-container-id > #player-shorts-container'
        + ' shorts-page.shorts-page > shorts-carousel.ytShortsCarouselHost:has(ytm-reel-player-overlay-renderer)',
    },
  ];
  let frame = null;
  let hidden;

  function restore() {
    if (!hidden) return;
    const { element, display, priority, hadStyle } = hidden;
    hidden = undefined;
    if (element.style.display !== 'none' || element.style.getPropertyPriority('display') !== 'important') return;
    element.style.setProperty('display', display, priority);
    if (!hadStyle && element.style.length === 0) element.removeAttribute('style');
  }

  function update() {
    frame = null;
    const rule = rules.find(({ path }) => path.test(window.location.pathname));
    const matches = rule && !document.querySelector('ytm-app#app > ytm-consent-bump-v2-renderer')
      ? document.querySelectorAll(rule.selector)
      : [];
    const element = matches.length === 1 ? matches[0] : undefined;
    if (element === hidden?.element) return;

    restore();
    if (!element) return;
    const { style } = element;
    const display = style.display;
    const priority = style.getPropertyPriority('display');
    if (display === 'none' && priority === 'important') return;

    hidden = { element, display, priority, hadStyle: element.hasAttribute('style') };
    style.setProperty('display', 'none', 'important');
  }

  function scheduleUpdate() {
    if (frame === null) frame = window.requestAnimationFrame(update);
  }

  const observer = new window.MutationObserver(scheduleUpdate);
  observer.observe(document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['aria-selected', 'class', 'id', 'role'],
  });
  window.addEventListener('popstate', scheduleUpdate);
  update();

  return () => {
    observer.disconnect();
    window.removeEventListener('popstate', scheduleUpdate);
    if (frame !== null) window.cancelAnimationFrame(frame);
    restore();
  };
};
