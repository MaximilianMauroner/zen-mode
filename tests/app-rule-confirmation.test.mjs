import assert from 'node:assert/strict';
import test from 'node:test';
import { getAppRuleChangeAction } from '../src/features/protection/app-rule-presentation.ts';

test('cooldown-only and window-only weakening have distinct, complete consent labels', () => {
  const visit = { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 30 };
  const shorterCooldown = getAppRuleChangeAction('Clock', [visit], { ...visit, cooldownMinutes: 5 });
  assert.notEqual(shorterCooldown, getAppRuleChangeAction('Clock', [visit], visit));
  assert.match(shorterCooldown, /5 minutes with a 5-minute cooldown$/);
  assert.match(shorterCooldown, /30-minute cooldown/);

  const rolling = { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 };
  const shorterWindow = getAppRuleChangeAction('Clock', [rolling], { ...rolling, windowMinutes: 30 });
  assert.notEqual(shorterWindow, getAppRuleChangeAction('Clock', [rolling], rolling));
  assert.match(shorterWindow, /5 minutes per 30-minute window$/);
  assert.match(shorterWindow, /60-minute window/);
});

test('mode replacement names all removed rules and the complete proposed rule', () => {
  const action = getAppRuleChangeAction('Clock', [
    { mode: 'daily', minutes: 15 },
    { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 30 },
  ], { mode: 'rolling', allowanceMinutes: 10, windowMinutes: 60 });
  assert.match(action, /replace Clock's existing rules/);
  assert.match(action, /daily limit of 15 minutes/);
  assert.match(action, /timed visit of 5 minutes with a 30-minute cooldown/);
  assert.match(action, /with a rolling allowance of 10 minutes per 60-minute window$/);
});

for (const { mode, rule, expected } of [
  { mode: 'daily', rule: { mode: 'daily', minutes: 1 }, expected: /a daily limit of 1 minute$/ },
  { mode: 'visit', rule: { mode: 'visit', sessionMinutes: 1, cooldownMinutes: 5 }, expected: /a timed visit of 1 minute with a 5-minute cooldown$/ },
  { mode: 'rolling', rule: { mode: 'rolling', allowanceMinutes: 1, windowMinutes: 15 }, expected: /a rolling allowance of 1 minute per 15-minute window$/ },
]) {
  test(`${mode} one-minute proposal names its duration and remaining consent parameters`, () => {
    assert.match(getAppRuleChangeAction('Clock', [], rule), expected);
  });
}

test('replacement consent names singular stored durations and a plural proposed duration', () => {
  const action = getAppRuleChangeAction('Clock', [
    { mode: 'daily', minutes: 1 },
    { mode: 'visit', sessionMinutes: 1, cooldownMinutes: 5 },
    { mode: 'rolling', allowanceMinutes: 1, windowMinutes: 15 },
  ], { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 30 });
  assert.match(action, /daily limit of 1 minute;/);
  assert.match(action, /timed visit of 1 minute with a 5-minute cooldown;/);
  assert.match(action, /rolling allowance of 1 minute per 15-minute window\)/);
  assert.match(action, /with a timed visit of 5 minutes with a 30-minute cooldown$/);
});
