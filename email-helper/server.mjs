// The host side of reading real emailed codes in device tests (mootmaker/designs/android-app.md,
// Decision 6 and Q5-A). Codes come only through mootmaker-email-testing's own client, never a
// Kotlin copy of it. Tests on the emulator reach this through `adb reverse tcp:8787 tcp:8787`;
// Maestro runs on the host and reaches it directly.
//
//   GET /account             a fresh, never-used identity: {name, email, password}
//   GET /code?email=<email>  the code emailed to <email>: 200 {code} once it has arrived, 202 while
//                            still waiting, 500 {error} if it never did. The first call starts the
//                            wait, so every call returns at once and clients poll. Neither client
//                            here can hold a request open for the queue's long polls.
//   GET /health              200, once listening
//
// Needs AWS credentials that can read the queue, and SQS_QUEUE_URL (see the workflows that start it).
import http from 'node:http'
import { freshTestAccount, waitForVerificationCode } from 'mootmaker-email-testing'

const port = Number(process.env.EMAIL_HELPER_PORT ?? 8787)
const WAIT_MS = 120_000

/** email -> {code} | {error} | {pending: true}. One wait per address: each test uses a fresh one. */
const waits = new Map()

function startWait(email) {
  const entry = { pending: true }
  waits.set(email, entry)
  waitForVerificationCode(email, WAIT_MS).then(
    (code) => waits.set(email, { code }),
    (error) => waits.set(email, { error: String(error?.message ?? error) }),
  )
  return entry
}

function send(response, status, body) {
  // A Content-Length, so the body is never chunked: the device test reads it over a bare socket.
  const text = JSON.stringify(body)
  response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text), Connection: 'close' })
  response.end(text)
}

http
  .createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://localhost')
    if (url.pathname === '/health') return send(response, 200, { ok: true })
    if (url.pathname === '/account') return send(response, 200, freshTestAccount())
    if (url.pathname === '/code') {
      const email = url.searchParams.get('email')
      if (!email) return send(response, 400, { error: 'email is required' })
      const entry = waits.get(email) ?? startWait(email)
      if (entry.code) {
        // A code is used once. A second request for the same address waits for the next email,
        // such as a reset code after the sign-up code.
        waits.delete(email)
        return send(response, 200, { code: entry.code })
      }
      if (entry.error) {
        waits.delete(email)
        return send(response, 500, { error: entry.error })
      }
      return send(response, 202, { pending: true })
    }
    return send(response, 404, { error: 'not found' })
  })
  .listen(port, '127.0.0.1', () => console.log(`email helper listening on 127.0.0.1:${port}`))
