import assert from 'node:assert/strict';
import test from 'node:test';
import {
  alternateChallenge,
  createChallenge,
  isChallengeConfirmed,
  startChallengeSession,
  submitChallengeAnswer,
} from '../src/features/protection/weakening-challenge.ts';
import { feedWeakeningAction } from '../src/features/protection/feed-weakening.ts';

test('generated questions are moderate, answerable, and vary by family', () => {
  for (const kind of ['arithmetic', 'ordering', 'attention']) {
    const prompts = new Set();
    for (let index = 0; index < 30; index++) {
      const challenge = createChallenge(kind);
      prompts.add(challenge.prompt);
      const answer = Number(challenge.answer);
      assert.ok(Number.isInteger(answer) && answer >= 0 && answer <= 100);
    }
    assert.ok(prompts.size > 10, `${kind} should not use a tiny question bank`);
  }
});

test('three mixed questions, retry, alternate and final consent stay separate', () => {
  let session = startChallengeSession();
  assert.equal(new Set(session.kinds).size, 3);
  assert.equal(isChallengeConfirmed(session, true), false);
  const wrong = submitChallengeAnswer(session, '-999');
  assert.equal(wrong.correct, false);
  assert.equal(wrong.session.step, 0);
  assert.equal(wrong.session.phase, 'challenge');
  assert.notEqual(alternateChallenge(wrong.session).challenge.kind, wrong.session.challenge.kind);
  session = wrong.session;
  for (let index = 0; index < 3; index++) {
    const result = submitChallengeAnswer(session, session.challenge.answer);
    assert.equal(result.correct, true);
    session = result.session;
  }
  assert.equal(session.phase, 'confirm');
  assert.equal(isChallengeConfirmed(session, false), false, 'cancel leaves protection unchanged');
  assert.equal(isChallengeConfirmed(session, true), true);
});

test('only weaker feed edits need the shared gate', () => {
  const stored = {
    shortsEnabled: true, youtubeHomeEnabled: true, xHomeEnabled: true, xVideosEnabled: true,
    xHomeMinutes: 5, instagramWaitSeconds: 30, instagramReelsMinutes: 5,
    instagramHomeMinutes: 5, instagramExploreBlocked: true,
  };
  assert.equal(feedWeakeningAction(stored, { ...stored, shortsEnabled: false }), 'disable the YouTube Shorts limit');
  assert.equal(feedWeakeningAction(stored, { ...stored, xHomeMinutes: 10 }), 'increase the X Home interval to 10 minutes');
  assert.equal(feedWeakeningAction(stored, { ...stored, instagramWaitSeconds: 15 }), 'shorten the Instagram Reels pause to 15 seconds');
  assert.equal(feedWeakeningAction(stored, { ...stored, instagramExploreBlocked: false }), 'disable Instagram Explore blocking');
  assert.equal(feedWeakeningAction(stored, { ...stored, xHomeMinutes: 1 }), null);
});
