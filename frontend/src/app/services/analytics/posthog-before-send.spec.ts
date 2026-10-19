import { ScalarProps } from './analytics-events';
import { buildBeforeSend, VendorCaptureResult } from './posthog-before-send';

/** Stands in for posthog-js's CaptureResult, so the specs can assert the untouched fields too. */
interface TestPayload extends VendorCaptureResult {
  event: string;
  uuid?: string;
  timestamp?: string;
}

/**
 * The scrub used to run through posthog-js's `sanitize_properties`, which the SDK deprecated.
 * `before_send` can do something its predecessor could not: drop the event. These specs pin both
 * halves of that, because a hook that silently returned nothing would switch analytics off across
 * the whole app without a single failing test anywhere else.
 */
describe('buildBeforeSend', () => {
  const scrub = (props: ScalarProps): ScalarProps => ({ ...props, $current_url: 'scrubbed' });

  it('applies the sanitizer to the payload properties', () => {
    const beforeSend = buildBeforeSend<TestPayload>(scrub);

    const result = beforeSend({
      event: 'goal_changed',
      properties: { $current_url: 'http://app/goals/42?token=abc', action: 'created' },
    });

    expect(result!.properties).toEqual({ $current_url: 'scrubbed', action: 'created' });
  });

  it('keeps the event, and every other field the SDK put on it', () => {
    const beforeSend = buildBeforeSend<TestPayload>(scrub);

    const result = beforeSend({
      uuid: 'abc-123',
      event: 'app_opened',
      timestamp: 'now',
      properties: { platform: 'web' },
    });

    expect(result).toBeTruthy();
    expect(result!.uuid).toBe('abc-123');
    expect(result!.event).toBe('app_opened');
    expect(result!.timestamp).toBe('now');
  });

  it('scrubs the initial person properties, which the SDK builds outside `properties`', () => {
    // posthog-js used to run sanitize_properties a SECOND time over $set_once. Scrubbing only
    // `properties` would put $initial_current_url on a person record, where it outlives the event
    // and no later scrub can reach it.
    // A stand-in for the real sanitizer: it rewrites every URL-ish key, initial ones included.
    const urlScrub = (props: ScalarProps): ScalarProps => {
      const out: ScalarProps = {};
      for (const key of Object.keys(props)) {
        out[key] = key.includes('url') ? 'scrubbed' : props[key];
      }
      return out;
    };
    const beforeSend = buildBeforeSend<TestPayload>(urlScrub);

    const result = beforeSend({
      event: '$identify',
      properties: { $current_url: 'http://app/goals/42' },
      $set_once: { $initial_current_url: 'http://app/beneficiaries/edit/42?token=abc' },
    });

    expect(result!.$set_once).toEqual({ $initial_current_url: 'scrubbed' });
    expect(result!.properties).toEqual({ $current_url: 'scrubbed' });
  });

  it('scrubs person properties on $set too', () => {
    const beforeSend = buildBeforeSend<TestPayload>((props) => ({ ...props, scrubbed: true }));

    const result = beforeSend({ event: '$identify', $set: { signup_month: '2026-11' } });

    expect(result!.$set).toEqual({ signup_month: '2026-11', scrubbed: true });
  });

  it('leaves a payload without person properties alone', () => {
    const beforeSend = buildBeforeSend<TestPayload>(scrub);

    const result = beforeSend({ event: 'app_opened', properties: { platform: 'web' } });

    expect('$set' in result!).toBeFalse();
    expect('$set_once' in result!).toBeFalse();
  });

  it('never drops an ordinary event', () => {
    const beforeSend = buildBeforeSend<TestPayload>((props) => props);

    expect(beforeSend({ event: 'app_opened', properties: {} })).not.toBeNull();
    expect(beforeSend({ event: 'app_opened', properties: { a: 1 } })).not.toBeNull();
  });

  it('does not mutate the payload it was handed', () => {
    const beforeSend = buildBeforeSend<TestPayload>(scrub);
    const original = { event: 'app_opened', properties: { $current_url: 'http://app/x' } };

    beforeSend(original);

    expect(original.properties.$current_url).toBe('http://app/x');
  });

  it('passes a null through, because an earlier hook already dropped that event', () => {
    const sanitize = jasmine.createSpy('sanitize').and.callFake((p: ScalarProps) => p);

    expect(buildBeforeSend<TestPayload>(sanitize)(null)).toBeNull();
    expect(sanitize).not.toHaveBeenCalled();
  });

  it('survives a payload with no properties at all', () => {
    const beforeSend = buildBeforeSend<TestPayload>(scrub);

    const result = beforeSend({ event: 'app_opened' });

    expect(result).toBeTruthy();
    expect(result!.event).toBe('app_opened');
  });

  it('fails closed: a sanitizer that throws drops the event instead of sending it unscrubbed', () => {
    const beforeSend = buildBeforeSend<TestPayload>(() => {
      throw new Error('scrub exploded');
    });

    expect(() => beforeSend({ event: 'goal_changed', properties: { secret: 'raw' } })).not.toThrow();
    expect(beforeSend({ event: 'goal_changed', properties: { secret: 'raw' } })).toBeNull();
  });
});
