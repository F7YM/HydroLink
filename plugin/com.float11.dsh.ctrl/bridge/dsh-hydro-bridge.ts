/**
 * HydroLink Companion Bridge for DeepSeek Harness (Cordis Plugin)
 * 遵循 DeepSeek Harness 官方插件规范：https://deepseek-harness.github.io/deepseek-harness/develop/basic/
 * 
 * 作用：为智能手表及第三方控制器提供开箱即用的轻量 HTTP REST 接口与工具审批/用户提问拦截队列。
 */

import type { Context } from '@deepseek-ai/cordis'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'

export const name = 'hydro-dsh-bridge'

export const inject = [
  'webServer',
  'sessionController',
  'workspaceRegistry',
  'approval',
  'userQuestions',
]

interface PendingApproval {
  id: string
  sessionId: string
  toolName: string
  callId?: string
  reason?: string
  time: number
  resolve: (outcome: 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable') => void
}

interface PendingQuestion {
  id: string
  sessionId: string
  callId?: string
  questions: any[]
  time: number
  resolve: (answer: any) => void
  reject: (err: any) => void
}

export function apply(ctx: Context) {
  const pendingApprovals = new Map<string, PendingApproval>()
  const pendingQuestions = new Map<string, PendingQuestion>()

  // 1. 监听工具权限审批（approval/request）
  ctx.on('approval/request', async (req) => {
    const id = 'appr_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7)
    const sessionId = (req.agent && req.agent.id) ? String(req.agent.id) : ''
    
    return new Promise<'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'>((resolve) => {
      const item: PendingApproval = {
        id,
        sessionId,
        toolName: req.toolName || 'tool',
        callId: req.callId,
        reason: req.reason,
        time: Date.now(),
        resolve,
      }
      pendingApprovals.set(id, item)

      if (req.signal) {
        req.signal.addEventListener('abort', () => {
          pendingApprovals.delete(id)
          resolve('cancelled')
        }, { once: true })
      }
    })
  })

  // 2. 监听交互提问（user-questions/request）
  ctx.on('user-questions/request', async (req) => {
    const id = 'ques_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7)
    const sessionId = (req.agent && req.agent.id) ? String(req.agent.id) : ''

    return new Promise((resolve, reject) => {
      const item: PendingQuestion = {
        id,
        sessionId,
        callId: req.wait?.callId,
        questions: req.questions || [],
        time: Date.now(),
        resolve,
        reject,
      }
      pendingQuestions.set(id, item)

      if (req.signal) {
        req.signal.addEventListener('abort', () => {
          pendingQuestions.delete(id)
          reject(new Error('aborted'))
        }, { once: true })
      }
    })
  })

  // 3. 注册 HTTP 路由到 DSH WebServer
  ctx.inject(['webServer'], (webCtx) => {
    function json(res: ServerResponse, status: number, data: any) {
      const body = JSON.stringify(data)
      res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Proxy-Authorization',
      })
      res.end(body)
    }

    function readJson(req: IncomingMessage): Promise<any> {
      return new Promise((resolve, reject) => {
        const chunks: Buffer[] = []
        req.on('data', chunk => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)))
        req.on('end', () => {
          try {
            const raw = Buffer.concat(chunks).toString('utf-8')
            resolve(raw ? JSON.parse(raw) : {})
          } catch (e) {
            reject(e)
          }
        })
        req.on('error', reject)
      })
    }

    const route: WebRoute = {
      kind: 'prefix',
      path: '/hydro-dsh',
      handler: async (req, res) => {
        const url = new URL(req.url || '/', 'http://127.0.0.1')
        const pathname = url.pathname

        if (req.method === 'OPTIONS') {
          res.writeHead(200, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Proxy-Authorization',
          })
          res.end()
          return
        }

        // GET /hydro-dsh/ping
        if (req.method === 'GET' && pathname === '/hydro-dsh/ping') {
          const sessions = ctx.sessionController ? await ctx.sessionController.list({}, new AbortController().signal) : null
          json(res, 200, {
            status: 'ok',
            name: 'DeepSeek Harness',
            bridge: 'hydro-dsh-bridge',
            version: '1.0.1',
            sessionCount: sessions ? sessions.items.length : 0,
            pendingApprovals: pendingApprovals.size,
            pendingQuestions: pendingQuestions.size,
          })
          return
        }

        // GET /hydro-dsh/pending
        if (req.method === 'GET' && pathname === '/hydro-dsh/pending') {
          const sid = url.searchParams.get('sessionId')
          const approvals = Array.from(pendingApprovals.values())
            .filter(a => !sid || a.sessionId === sid)
            .map(a => ({
              type: 'approval',
              id: a.id,
              sessionId: a.sessionId,
              toolName: a.toolName,
              callId: a.callId,
              reason: a.reason,
              time: a.time,
            }))
          
          const questions = Array.from(pendingQuestions.values())
            .filter(q => !sid || q.sessionId === sid)
            .map(q => ({
              type: 'question',
              id: q.id,
              sessionId: q.sessionId,
              callId: q.callId,
              questions: q.questions,
              time: q.time,
            }))

          json(res, 200, {
            status: 'ok',
            approvals,
            questions,
          })
          return
        }

        // POST /hydro-dsh/approval/reply
        if (req.method === 'POST' && pathname === '/hydro-dsh/approval/reply') {
          try {
            const body = await readJson(req)
            const id = body.id
            const outcome = body.outcome || 'allowed-once'
            const item = pendingApprovals.get(id)
            if (!item) {
              json(res, 404, { status: 'error', message: 'approval request not found or expired' })
              return
            }
            pendingApprovals.delete(id)
            item.resolve(outcome)
            json(res, 200, { status: 'ok', id, outcome })
          } catch (e: any) {
            json(res, 400, { status: 'error', message: e.message })
          }
          return
        }

        // POST /hydro-dsh/question/reply
        if (req.method === 'POST' && pathname === '/hydro-dsh/question/reply') {
          try {
            const body = await readJson(req)
            const id = body.id
            const answers = body.answers || []
            const item = pendingQuestions.get(id)
            if (!item) {
              json(res, 404, { status: 'error', message: 'question request not found or expired' })
              return
            }
            pendingQuestions.delete(id)
            item.resolve({ answers })
            json(res, 200, { status: 'ok', id })
          } catch (e: any) {
            json(res, 400, { status: 'error', message: e.message })
          }
          return
        }

        json(res, 404, { status: 'error', message: 'not found' })
      },
    }

    webCtx.effect(() => webCtx.webServer.register(route), 'hydro-dsh-bridge: /hydro-dsh routes')
    console.log('[hydro-dsh-bridge] loaded successfully! Routes mounted at /hydro-dsh/*')
  })
}
