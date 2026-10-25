import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { prepareTrainingFeedback } from '../web/training-feedback.mjs'

test('legacy AIRI feedback requires explicit consent and uses stable body-bound retry identity', () => {
  const turn = { turnId: 'turn-test', sessionId: 'session-before-switch' }
  assert.throws(() => prepareTrainingFeedback(turn, 'candidate-a', '', false), /明确同意/)
  const first = prepareTrainingFeedback(turn, 'candidate-a', '先解释原因', true)
  assert.equal(prepareTrainingFeedback(turn, 'candidate-a', '先解释原因', true).requestId, first.requestId)
  assert.notEqual(prepareTrainingFeedback(turn, 'candidate-b', '先解释原因', true).requestId, first.requestId)
  assert.equal(first.sessionId, 'session-before-switch')
  assert.equal(first.body.consent_to_training, true)
})

test('legacy AIRI selection cannot automatically save or assume consent', () => {
  const page = readFileSync(new URL('../web/index.html', import.meta.url), 'utf8')
  assert.match(page, /id="training-feedback-consent" type="checkbox"/)
  assert.doesNotMatch(page, /if \(!trainingModeEnabled\(\)\) await submitTrainingFeedback\(\)/)
  assert.match(page, /'Idempotency-Key': submission\.requestId/)
  assert.match(page, /}, submission\.sessionId\)/)
  assert.match(page, /prepareTrainingFeedback\(submittingTurn, selectedTrainingCandidateId,[\s\S]*trainingFeedbackConsent\.checked\)/)
})
