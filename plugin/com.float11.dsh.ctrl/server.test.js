// DeepSeek Harness 控制器插件单元测试
const assert = require('assert');
const fs = require('fs');
const path = require('path');

// 模拟 HydroKit 全局环境
global.kit = {
  storage: {
    data: {},
    async getItem(k) { return this.data[k] || null; },
    async setItem(k, v) { this.data[k] = v; }
  },
  async notify(msg) {
    this.lastNotify = msg;
    return true;
  }
};

const plugin = require('./server.js');

async function runTests() {
  console.log('== 开始执行 DeepSeek Harness 控制器插件测试 ==');

  // 1. 验证清单
  const mf = plugin.manifest();
  assert.strictEqual(mf.package.id, 'com.float11.dsh.ctrl');
  assert.strictEqual(mf.package.name, 'DeepSeek Harness');
  assert.strictEqual(mf.package.version, '1.0.1');
  assert.strictEqual(mf.apps[0].id, 'main');
  console.log('✔ 清单契约校验通过');

  // 2. 未配置状态下的界面渲染
  global.kit.storage.data = {};
  const unconfiguredView = await plugin.view('app', 'main', {});
  assert.strictEqual(unconfiguredView.title, 'DeepSeek Harness');
  assert.strictEqual(unconfiguredView.root.type, 'scroll');
  const unconfiguredTexts = JSON.stringify(unconfiguredView.root);
  assert(unconfiguredTexts.includes('尚未连接 DeepSeek Harness'));
  console.log('✔ 未配置引导页渲染正常');

  // 3. 配置了 baseUrl 但无真实网络时的优雅失败
  global.kit.storage.data['dsh_config'] = {
    baseUrl: 'http://127.0.0.1:39999'
  };
  // 模拟 global.fetch 抛出连接拒绝
  global.fetch = async () => {
    throw new Error('connect ECONNREFUSED 127.0.0.1:39999');
  };
  const failView = await plugin.view('app', 'main', {});
  assert(JSON.stringify(failView).includes('无法连接 DeepSeek Harness'));
  console.log('✔ 网络异常回退展示正常');

  // 4. 模拟 DSH 原生 RPC 成功响应并渲染列表
  global.fetch = async (url, opts) => {
    const urlStr = String(url);
    if (urlStr.includes('/hydro-dsh/pending')) {
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            status: 'ok',
            approvals: [
              { id: 'appr_1', sessionId: 'ses_1', toolName: 'bash', reason: 'rm -rf test' }
            ],
            questions: []
          });
        }
      };
    }
    if (urlStr.includes('/api/session/list')) {
      if (opts && opts.body) {
        const bodyObj = JSON.parse(opts.body);
        assert(bodyObj.payload && bodyObj.payload.args && ('_request' in bodyObj.payload.args), 'session/list must contain _request parameter for Typert descriptor');
      }
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'test_rpc',
            result: {
              ok: true,
              value: {
                items: [
                  {
                    sessionId: 'ses_1',
                    title: '测试会话 1',
                    running: true,
                    updatedAt: Date.now(),
                    cwd: '/home/user/project'
                  },
                  {
                    sessionId: 'ses_2',
                    title: '测试会话 2',
                    running: false,
                    updatedAt: Date.now() - 60000,
                    cwd: '/home/user/docs'
                  }
                ]
              }
            }
          });
        }
      };
    }
    return {
      ok: false,
      status: 404,
      async text() { return 'not found'; }
    };
  };

  const listView = await plugin.view('app', 'main', {});
  assert.strictEqual(listView.title, 'DeepSeek Harness');
  const listJson = JSON.stringify(listView);
  // 校验包含置顶的审批卡片
  assert(listJson.includes('审批: bash'));
  assert(listJson.includes('rm -rf test'));
  // 校验包含两个会话
  assert(listJson.includes('测试会话 1'));
  assert(listJson.includes('测试会话 2'));
  // 校验 FAB 新建按钮存在
  assert(listView.fab && listView.fab.tap === 'new_session');
  console.log('✔ 会话列表及置顶审批卡片渲染正常');

  // 5. 点击进入会话详情并模拟历史消息拉取
  global.fetch = async (url, opts) => {
    const urlStr = String(url);
    if (urlStr.includes('/hydro-dsh/pending')) {
      return {
        ok: true,
        async text() {
          return JSON.stringify({ status: 'ok', approvals: [], questions: [] });
        }
      };
    }
    if (urlStr.includes('/api/session/projections')) {
      if (opts && opts.body) {
        const bodyObj = JSON.parse(opts.body);
        assert(bodyObj.payload && bodyObj.payload.args && ('request' in bodyObj.payload.args), 'session/projections must contain request parameter');
      }
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'test_proj',
            result: {
              ok: true,
              value: { asOfSeq: 10, values: { inbox: { 'next-turn': [], 'next-step': [] }, title: '测试会话 1' } }
            }
          });
        }
      };
    }
    if (urlStr.includes('/api/session/page')) {
      if (opts && opts.body) {
        const bodyObj = JSON.parse(opts.body);
        assert(bodyObj.payload && bodyObj.payload.args && ('request' in bodyObj.payload.args), 'session/page must contain request parameter');
      }
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'test_page',
            result: {
              ok: true,
              value: {
                records: [
                  {
                    event: {
                      type: 'user/message',
                      seq: 1,
                      time: Date.now() - 10000,
                      data: { text: '请帮我写一个快速排序' }
                    }
                  },
                  {
                    event: {
                      type: 'tool/call',
                      seq: 2,
                      time: Date.now() - 8000,
                      data: {
                        id: 'tc_1',
                        name: 'bash',
                        args: { command: 'node -v' }
                      }
                    }
                  },
                  {
                    event: {
                      type: 'tool/result',
                      seq: 3,
                      time: Date.now() - 7000,
                      data: {
                        callId: 'tc_1',
                        output: 'v20.10.0'
                      }
                    }
                  },
                  {
                    event: {
                      type: 'assistant/message',
                      seq: 4,
                      time: Date.now() - 5000,
                      data: {
                        content: [
                          { type: 'text', text: '这是快速排序的实现代码：' }
                        ]
                      }
                    }
                  }
                ],
                hasMore: false
              }
            }
          });
        }
      };
    }
    if (urlStr.includes('/api/session/state')) {
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'test_state',
            result: {
              ok: true,
              value: { running: false }
            }
          });
        }
      };
    }
    if (urlStr.includes('/api/session/events')) {
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'test_events',
            result: {
              ok: true,
              value: {
                records: [
                  {
                    event: {
                      type: 'user/message',
                      time: Date.now() - 10000,
                      data: { text: '请帮我写一个快速排序' }
                    }
                  },
                  {
                    event: {
                      type: 'tool/call',
                      time: Date.now() - 8000,
                      data: {
                        id: 'tc_1',
                        name: 'bash',
                        args: { command: 'node -v' }
                      }
                    }
                  },
                  {
                    event: {
                      type: 'tool/result',
                      time: Date.now() - 7000,
                      data: {
                        callId: 'tc_1',
                        output: 'v20.10.0'
                      }
                    }
                  },
                  {
                    event: {
                      type: 'assistant/message',
                      time: Date.now() - 5000,
                      data: {
                        content: [
                          { type: 'text', text: '这是快速排序的实现代码：' }
                        ]
                      }
                    }
                  }
                ],
                cursor: 10,
                hasMore: false
              }
            }
          });
        }
      };
    }
    return {
      ok: false,
      status: 404,
      async text() { return 'not found'; }
    };
  };

  const detailView = await plugin.view('app', 'main', { handler: 'open_session:ses_1' });
  const detailJson = JSON.stringify(detailView);
  assert(detailJson.includes('请帮我写一个快速排序'));
  assert(detailJson.includes('node -v'));
  assert(detailJson.includes('这是快速排序的实现代码'));
  assert(detailJson.includes('btn_model_ses_1'));
  assert(detailJson.includes('input_prompt_ses_1'));
  console.log('✔ 会话详情流式消息合并与输入控件渲染正常');

  // 6. 验证 safeText 彻底剔除 emoji
  const testEmojiEvent = {
    records: [
      {
        event: {
          type: 'assistant/message',
          time: Date.now(),
          data: {
            content: [
              { type: 'text', text: 'Hello 🐳 DeepSeek 🚀 Harness 🎉 2026' }
            ]
          }
        }
      }
    ]
  };
  global.fetch = async (url) => ({
    ok: true,
    async text() {
      return JSON.stringify({
        type: 'server-response',
        rpcId: 'emoji_test',
        result: { ok: true, value: testEmojiEvent }
      });
    }
  });

  const emojiDetail = await plugin.view('app', 'main', { handler: 'open_session:ses_emoji' });
  const emojiStr = JSON.stringify(emojiDetail);
  assert(emojiStr.includes('DeepSeek'));
  assert(emojiStr.includes('Harness'));
  assert(!emojiStr.includes('🐳'));
  assert(!emojiStr.includes('🚀'));
  assert(!emojiStr.includes('🎉'));
  console.log('✔ Vela 平台 Emoji 彻底剔除安全约束校验通过');

  // 7. 验证可用模型自动拉取与轮流切换
  let selectModelCalledWith = null;
  global.fetch = async (url, opts) => {
    const urlStr = String(url);
    if (urlStr.includes('/api/session/modelCatalog')) {
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'catalog_test',
            result: {
              ok: true,
              value: {
                routableProviders: ['deepseek-official'],
                groups: [
                  {
                    id: 'deepseek-official',
                    name: 'DeepSeek',
                    models: [
                      { id: 'deepseek-flash', name: 'DeepSeek-V4.1-Flash', reasoning: { defaultEffort: 'high' } },
                      { id: 'deepseek-v4-pro', name: 'DeepSeek-V4-Pro', reasoning: { defaultEffort: 'high' } }
                    ]
                  }
                ]
              }
            }
          });
        }
      };
    }
    if (urlStr.includes('/api/session/selectModel')) {
      const bodyObj = JSON.parse(opts.body);
      selectModelCalledWith = bodyObj.payload.args.request;
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'select_test',
            result: { ok: true, value: { selected: selectModelCalledWith } }
          });
        }
      };
    }
    if (urlStr.includes('/api/session/projections')) {
      const activeModel = selectModelCalledWith || { provider: 'deepseek-official', model: 'deepseek-flash' };
      return {
        ok: true,
        async text() {
          return JSON.stringify({
            type: 'server-response',
            rpcId: 'proj_test',
            result: {
              ok: true,
              value: {
                asOfSeq: 12,
                values: {
                  title: '模型切换测试',
                  modelSelection: { next: activeModel }
                }
              }
            }
          });
        }
      };
    }
    return {
      ok: true,
      async text() {
        return JSON.stringify({ type: 'server-response', result: { ok: true, value: { records: [] } } });
      }
    };
  };

  const switchedView = await plugin.view('app', 'main', { handler: 'cycle_model:ses_1:deepseek-flash' });
  assert(selectModelCalledWith, 'session/selectModel 应被调用');
  assert.strictEqual(selectModelCalledWith.model, 'deepseek-v4-pro', '应自动切换到下一个可用模型 deepseek-v4-pro');
  assert.strictEqual(selectModelCalledWith.provider, 'deepseek-official');
  assert.strictEqual(selectModelCalledWith.reasoningEffort, 'high');
  const switchedJson = JSON.stringify(switchedView);
  assert(switchedJson.includes('模型: V4-Pro') || switchedJson.includes('V4-Pro'), '界面按钮应更新为新模型名称');
  console.log('✔ 可用模型自动拉取与轮流切换校验通过');

  console.log('== 所有测试全部通过！ ==');
}

runTests().catch(err => {
  console.error('测试失败:', err);
  process.exit(1);
});
