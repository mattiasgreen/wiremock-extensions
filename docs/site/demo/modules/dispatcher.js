/**
 * Deep protocol dispatcher seam for WireMock Console.
 * Standardizes multi-protocol execution (HTTP, Kafka, AMQP, Casual XATMI)
 * behind a single zero-DOM interface returning a uniform SettlementResult.
 */
import { doFetch } from './api.js';

/**
 * Dispatches an interactive request across the requested transport protocol.
 *
 * @param {Object} options
 * @param {string} [options.protocol='http'] - 'http' | 'kafka' | 'amqp' | 'casual'
 * @param {string} options.destination - URL, topic, queue, or service name
 * @param {string} [options.method='POST'] - Protocol action or HTTP method
 * @param {Object<string, string>} [options.headers={}] - Key-value headers map
 * @param {string} [options.body=''] - Payload text
 * @param {string} [options.key] - Kafka message key
 * @param {number} [options.partition] - Kafka partition
 * @param {string} [options.correlationId] - AMQP correlation ID
 * @param {string} [options.replyTo] - AMQP reply queue
 * @param {string} [options.bufferType='JSON'] - Casual buffer type
 * @returns {Promise<Object>} normalized SettlementResult
 */
export async function dispatchRequest(options) {
  const protocol = (options.protocol || 'http').toLowerCase();
  const startTime = performance.now();

  if (protocol === 'kafka') {
    return dispatchKafka(options, startTime);
  }
  if (protocol === 'amqp') {
    return dispatchAmqp(options, startTime);
  }
  if (protocol === 'casual') {
    return dispatchCasual(options, startTime);
  }
  return dispatchHttp(options, startTime);
}

async function dispatchKafka(options, startTime) {
  const destination = options.destination || 'orders.events';
  const key = options.key || '';
  const partition = options.partition !== undefined ? options.partition : undefined;
  const bodyText = options.body || '{}';
  const headers = options.headers || {};

  const payload = {
    protocol: 'kafka',
    destination: destination,
    key: key,
    partition: partition,
    headers: headers,
    payload: bodyText
  };

  try {
    const res = await doFetch('/__admin/async/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    const data = await res.json();
    const elapsed = Math.round(performance.now() - startTime);

    const partitionOut = data.partition !== undefined ? data.partition : (partition !== undefined ? partition : 0);
    const offsetOut = data.offset !== undefined ? data.offset : 0;
    const statusText = `ACKNOWLEDGED (Partition ${partitionOut} @ Offset ${offsetOut})`;
    const resBody = JSON.stringify(data, null, 2);
    const resHeaders = [`Protocol: Kafka`, `Topic: ${destination}`, `Key: ${key || '(none)'}`, `Status: Published`];

    return {
      ok: true,
      status: 200,
      statusText: statusText,
      elapsedMs: elapsed,
      responseHeaders: resHeaders,
      responseBody: resBody,
      protocol: 'kafka',
      method: 'PRODUCE',
      destination: destination,
      settlement: {
        partition: partitionOut,
        offset: offsetOut
      },
      banner: {
        text: `✅ Kafka Message Acknowledged: Partition ${partitionOut} @ Offset ${offsetOut} (Topic: "${destination}")`,
        type: 'status-settled'
      }
    };
  } catch (err) {
    const elapsed = Math.round(performance.now() - startTime);
    return {
      ok: false,
      status: 'ERR',
      statusText: err.message,
      elapsedMs: elapsed,
      responseHeaders: [],
      responseBody: 'Publish error: ' + err.message,
      protocol: 'kafka',
      method: 'PRODUCE',
      destination: destination,
      settlement: {},
      banner: {
        text: `❌ Kafka Error: ${err.message}`,
        type: 'status-error'
      }
    };
  }
}

async function dispatchAmqp(options, startTime) {
  const destination = options.destination || 'orders.inbound';
  const correlationId = options.correlationId || '';
  const replyTo = options.replyTo || '';
  const bodyText = options.body || '{}';
  const headers = options.headers || {};

  const payload = {
    protocol: 'amqp',
    destination: destination,
    correlationId: correlationId,
    replyTo: replyTo,
    headers: headers,
    payload: bodyText
  };

  try {
    const res = await doFetch('/__admin/async/publish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
    const data = await res.json();
    const elapsed = Math.round(performance.now() - startTime);

    const isReqRep = data.replyReceived && data.replyPayload;
    const statusText = isReqRep ? 'REPLY RECEIVED' : (data.settlement || 'SETTLED: ACCEPTED');
    const resBody = isReqRep ? data.replyPayload : JSON.stringify(data, null, 2);
    const resHeaders = [
      `Protocol: AMQP 1.0`,
      `Destination: ${destination}`,
      `Correlation-Id: ${data.replyCorrelationId || correlationId || '(none)'}`
    ];
    if (replyTo) resHeaders.push(`Reply-To: ${replyTo}`);

    const bannerText = isReqRep
      ? `📨 Correlated Reply Received from "${data.replyQueue || replyTo}" (CorrelationId: ${data.replyCorrelationId || correlationId})`
      : `✅ AMQP Message Settled: ACCEPTED (Destination: "${destination}")`;

    return {
      ok: true,
      status: 200,
      statusText: statusText,
      elapsedMs: elapsed,
      responseHeaders: resHeaders,
      responseBody: resBody,
      protocol: 'amqp',
      method: 'PUBLISH',
      destination: destination,
      settlement: {
        isReqRep: !!isReqRep,
        disposition: data.settlement || 'Accepted',
        correlationId: data.replyCorrelationId || correlationId
      },
      banner: {
        text: bannerText,
        type: 'status-settled'
      }
    };
  } catch (err) {
    const elapsed = Math.round(performance.now() - startTime);
    return {
      ok: false,
      status: 'ERR',
      statusText: err.message,
      elapsedMs: elapsed,
      responseHeaders: [],
      responseBody: 'Publish error: ' + err.message,
      protocol: 'amqp',
      method: 'PUBLISH',
      destination: destination,
      settlement: {},
      banner: {
        text: `❌ AMQP Error: ${err.message}`,
        type: 'status-error'
      }
    };
  }
}

async function dispatchCasual(options, startTime) {
  const service = options.destination || 'echoService';
  const bufferType = options.bufferType || 'JSON';
  const bodyText = options.body || '{}';
  const headers = options.headers || {};

  try {
    const res = await doFetch('/__admin/casual/call', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        service: service,
        bufferType: bufferType,
        payload: bodyText,
        headers: headers
      })
    });
    const data = await res.json();
    const elapsed = Math.round(performance.now() - startTime);
    const isOk = !data.errorState || data.errorState === 'OK';
    const statusText = isOk ? 'OK (0)' : `${data.errorState} (${data.errorCode || 0})`;
    const resBody = data.payload || '';
    const resHeaders = [
      `Buffer-Type: ${data.bufferType || bufferType}`,
      `Error-State: ${data.errorState || 'OK'}`
    ];
    if (data.headers) {
      Object.entries(data.headers).forEach(([k, v]) => resHeaders.push(`${k}: ${v}`));
    }

    return {
      ok: isOk,
      status: isOk ? 200 : 500,
      statusText: statusText,
      elapsedMs: elapsed,
      responseHeaders: resHeaders,
      responseBody: resBody,
      protocol: 'casual',
      method: 'CALL',
      destination: service,
      settlement: {
        bufferType: data.bufferType || bufferType,
        errorState: data.errorState || 'OK',
        errorCode: data.errorCode || 0
      },
      banner: {
        text: `${isOk ? '✅' : '❌'} Casual Call Settled: ${service} -> ${statusText}`,
        type: isOk ? 'status-settled' : 'status-error'
      }
    };
  } catch (err) {
    const elapsed = Math.round(performance.now() - startTime);
    return {
      ok: false,
      status: 'ERR',
      statusText: err.message,
      elapsedMs: elapsed,
      responseHeaders: [],
      responseBody: 'Call error: ' + err.message,
      protocol: 'casual',
      method: 'CALL',
      destination: service,
      settlement: {},
      banner: {
        text: `❌ Casual Call Error: ${err.message}`,
        type: 'status-error'
      }
    };
  }
}

async function dispatchHttp(options, startTime) {
  let url = (options.destination || '/').trim();
  if (!url.startsWith('/') && !url.startsWith('http')) {
    url = '/' + url;
  }
  const method = (options.method || 'GET').toUpperCase();
  const headers = options.headers || {};
  const bodyText = options.body || '';

  const fetchOptions = {
    method: method,
    headers: headers
  };
  if (bodyText && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    fetchOptions.body = bodyText;
  }

  try {
    const res = await doFetch(url, fetchOptions);
    const elapsed = Math.round(performance.now() - startTime);
    const headerLines = [];
    if (res.headers && res.headers.forEach) {
      res.headers.forEach((val, key) => {
        headerLines.push(`${key}: ${val}`);
      });
    }
    const body = await res.text();
    const statusText = res.statusText || (res.status === 200 ? 'OK' : '');

    return {
      ok: res.ok,
      status: res.status,
      statusText: statusText,
      elapsedMs: elapsed,
      responseHeaders: headerLines,
      responseBody: body,
      protocol: 'http',
      method: method,
      destination: url,
      settlement: {},
      banner: {
        text: res.ok
          ? `✅ HTTP ${res.status} ${statusText || 'OK'}`
          : `⚠️ HTTP ${res.status} ${statusText || 'Error'}`,
        type: res.ok ? 'status-settled' : 'status-error'
      }
    };
  } catch (err) {
    const elapsed = Math.round(performance.now() - startTime);
    return {
      ok: false,
      status: 'ERROR',
      statusText: err.message,
      elapsedMs: elapsed,
      responseHeaders: [],
      responseBody: 'Fetch error: ' + err.message,
      protocol: 'http',
      method: method,
      destination: url,
      settlement: {},
      banner: {
        text: `❌ Fetch Error: ${err.message}`,
        type: 'status-error'
      }
    };
  }
}
