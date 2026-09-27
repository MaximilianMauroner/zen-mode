import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const service = readFileSync(
  new URL('../modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/ZenGuardAccessibilityService.kt', import.meta.url),
  'utf8',
);

test('YouTube routes the owning accessibility event into conservative pager verification', () => {
  assert.match(service, /handleYouTubeEvent\(event\)/);
  assert.match(service, /event\.source\?\.viewIdResourceName/);
  assert.match(service, /event\.eventType == AccessibilityEvent\.TYPE_VIEW_SCROLLED/);
  assert.match(service, /fromIndex = event\.fromIndex/);
  assert.match(service, /toIndex = event\.toIndex/);
  assert.match(service, /scrollY = event\.scrollY/);
  assert.match(service, /pagerTransitionIndex = pagerTransitionIndex/);
  assert.match(service, /youtubeShortsExitResult\.requested\(stateMachine\.pendingExitPageIndex\(\), SystemClock\.elapsedRealtime\(\)\)/);
  assert.match(service, /youtubeShortsExitResult\.confirmed\(homeSelected, SystemClock\.elapsedRealtime\(\)\)/);
  assert.match(service, /recordYouTubeShortsStats\(exit\.pageIndex, EnforcementStatsOutcome\.SUCCESS\)/);
});
