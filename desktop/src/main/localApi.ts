import crypto from 'node:crypto'
import http from 'node:http'
import https from 'node:https'
import type { ClientRequest } from 'node:http'
import type { TLSSocket } from 'node:tls'

export type Endpoint = {
  base: string
  tls: boolean
  cert?: Buffer
}

type Method = 'GET' | 'POST' | 'DELETE'

function buildOptions(
  ep: Endpoint,
  method: Method,
  path: string,
  headers: Record<string, string> = {}
): https.RequestOptions {
  const url = new URL(path, ep.base)
  const options: https.RequestOptions = {
    method,
    hostname: url.hostname,
    port: url.port,
    path: url.pathname + url.search,
    headers
  }
  if (url.protocol === 'https:') {
    // BoringSSL 不把自签叶证书当信任锚，链路校验必然失败；改用指纹固定。
    options.rejectUnauthorized = false
  }
  return options
}

function pinOf(cert: Buffer): string {
  return new crypto.X509Certificate(cert).fingerprint256.replace(/:/g, '').toUpperCase()
}

function peerTrusted(socket: TLSSocket, ep: Endpoint): boolean {
  const expected = ep.cert ? pinOf(ep.cert) : ''
  const cert = socket.getPeerCertificate()
  const actual = (cert?.fingerprint256 ?? '').replace(/:/g, '').toUpperCase()
  return expected !== '' && actual === expected
}

export function request<T = unknown>(
  ep: Endpoint,
  method: Method,
  path: string,
  body?: unknown
): Promise<T> {
  const payload = body === undefined ? undefined : JSON.stringify(body)
  const headers: Record<string, string> = {}
  if (payload !== undefined) {
    headers['content-type'] = 'application/json'
    headers['content-length'] = String(Buffer.byteLength(payload))
  }
  const options = buildOptions(ep, method, path, headers)
  const lib = ep.tls ? https : http

  return new Promise<T>((resolve, reject) => {
    const req = lib.request(options, (res) => {
      if (ep.tls && !peerTrusted(req.socket as TLSSocket, ep)) {
        res.resume()
        req.destroy(new Error('证书指纹不匹配'))
        reject(new Error('证书指纹不匹配'))
        return
      }
      const chunks: Buffer[] = []
      res.on('data', (chunk) => chunks.push(chunk as Buffer))
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let data: unknown = null
        try {
          data = JSON.parse(text)
        } catch {
          data = text
        }
        const code = res.statusCode ?? 0
        if (code < 200 || code >= 300) {
          const err = data as { code?: string; msg?: string } | null
          reject(new Error(err?.code ? `${err.code}: ${err.msg ?? ''}` : `HTTP ${code}`))
          return
        }
        resolve(data as T)
      })
    })
    req.setTimeout(30_000, () => req.destroy(new Error('请求超时')))
    req.on('error', reject)
    if (payload !== undefined) req.write(payload)
    req.end()
  })
}

export type EventHandlers = {
  onEvent: (event: unknown) => void
  onOpen?: () => void
  onClose?: (err?: Error) => void
}

export function openEventStream(ep: Endpoint, handlers: EventHandlers): ClientRequest {
  const options = buildOptions(ep, 'GET', '/v1/events', { accept: 'text/event-stream' })
  const lib = ep.tls ? https : http
  let finished = false
  const finish = (err?: Error): void => {
    if (finished) return
    finished = true
    handlers.onClose?.(err)
  }

  const req = lib.request(options, (res) => {
    if (ep.tls && !peerTrusted(req.socket as TLSSocket, ep)) {
      res.resume()
      finish(new Error('证书指纹不匹配'))
      return
    }
    if (res.statusCode !== 200) {
      res.resume()
      finish(new Error(`HTTP ${res.statusCode ?? 0}`))
      return
    }
    handlers.onOpen?.()
    let buffer = ''
    res.setEncoding('utf8')
    res.on('data', (chunk: string) => {
      buffer += chunk
      let index = buffer.indexOf('\n')
      while (index >= 0) {
        const line = buffer.slice(0, index).replace(/\r$/, '')
        buffer = buffer.slice(index + 1)
        if (line.startsWith('data: ')) {
          try {
            handlers.onEvent(JSON.parse(line.slice(6)))
          } catch {
            // 忽略坏帧，事件流继续
          }
        }
        index = buffer.indexOf('\n')
      }
    })
    res.on('end', () => finish())
    res.on('close', () => finish())
    res.on('error', (err) => finish(err))
  })

  req.on('error', (err) => finish(err))
  req.end()
  return req
}
