#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补上独立变异狩猎挖出的高危覆盖缺口。

只加测试，不动 register.tsx——加完再用变异矩阵验这些测试有没有牙齿。
"""
import pathlib

P = pathlib.Path(
    r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status/tests/ssh-status.test.tsx"
)
s = P.read_text(encoding="utf-8")

anchor = "describe('ssh-status pane', () => {"
assert s.count(anchor) == 1, s.count(anchor)

BLOCK = r"""describe('ssh-status must not lie about state', () => {
  test('treats a failure reported only inside result as a failure', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // 外层没标 isError，只有 result.isError。少判一层就会把失败记成在线。
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => ({
      result: { content: [{ type: 'text', text: 'boom' }], isError: true },
      text: 'boom',
    }))

    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'lie-1', name: 'web-02' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /✗/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /●/ })).toBeUndefined()
    await ui.unmount()
  })

  test('treats a denied call as a failure, not a live connection', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // deny 携带的是一句原因，不是 true。写成 `deny === true` 会漏判。
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => ({ deny: 'not permitted here' }))

    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'lie-2', name: 'web-02' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /✗/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /●/ })).toBeUndefined()
    await ui.unmount()
  })

  test('demotes everything when the server says there are no active connections', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, () => answered(connectPayload('web-01')))
    // 服务端空列表是纯文本，不是 JSON。当成「解析不了」就永远不降级，状态条会一直撒谎。
    on('tool.call', { tool: 'mcp__ssh__ssh_list_connections' }, async () =>
      answered('No active connections.'),
    )

    await $.tool.call(CONNECT_INPUT)
    await $.tool.call({ tool: 'mcp__ssh__ssh_list_connections', tool_use_id: 'lie-3' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /SSH/ })).toBeUndefined()
    await ui.unmount()
  })

  test('disconnects by connectionId even when the id is not the name', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // 服务端 id 是 host:port，name 是另一回事——拿 name 去比就永远断不开
    answerConnect(on, input =>
      answered(connectPayload(String(input.name) === 'web-02' ? 'jms.company.com:2222' : 'web-01')),
    )
    answerDisconnect(on, () => answered(JSON.stringify({ message: 'closed' })))

    await $.tool.call(CONNECT_INPUT)
    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'lie-4', name: 'web-02' })
    await $.tool.call({
      tool: 'mcp__ssh__ssh_disconnect',
      tool_use_id: 'lie-4-d',
      connectionId: 'jms.company.com:2222',
    })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /●1 web-01/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /web-02/ })).toBeUndefined()
    await ui.unmount()
  })

  test('a disconnect also cancels an in-flight connect', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    let release: (v: StubResult) => void = () => undefined
    const held = new Promise<StubResult>(resolve => {
      release = resolve
    })
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => held)
    answerDisconnect(on, () => answered(JSON.stringify({ message: 'all closed' })))

    const inflight = $.tool.call(CONNECT_INPUT) as Promise<unknown>
    await clock.settle()
    await $.tool.call({ tool: 'mcp__ssh__ssh_disconnect', tool_use_id: 'lie-5-d' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // 在建连接也得跟着断开，否则永远卡在 … 上
    expect(await ui.find({ type: 'Text', text: /…/ })).toBeUndefined()
    expect(await ui.find({ type: 'Text', text: /SSH/ })).toBeUndefined()
    await ui.unmount()

    release(answered(connectPayload('web-01')))
    await inflight
  })

  test('a reconnect leaves exactly one row — no ghost placeholder', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, () => answered(connectPayload('web-01')))

    await $.tool.call(CONNECT_INPUT)
    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'lie-6-again', name: 'web-01' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // 合并失败的话会剩一条 … 的幽灵行，而 /●1 web-01/ 照样匹配得到
    expect(await ui.find({ type: 'Text', text: /●1 web-01/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /…/ })).toBeUndefined()
    await ui.unmount()
  })
})

describe('ssh-status prune policy, deeper', () => {
  test('a fresh failure survives a later prune; an expired one does not', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, input =>
      String(input.name).startsWith('fail-')
        ? errored('boom')
        : answered(connectPayload(String(input.name))),
    )

    // 新鲜的失败要留着——TTL 判反了会立刻删掉
    await $.tool.call({ tool: 'mcp__ssh__ssh_connect', tool_use_id: 'ttl-1', host: 'h', username: 'u', name: 'fail-a' })
    await clock.advance(60 * 1000)
    await $.tool.call({ tool: 'mcp__ssh__ssh_connect', tool_use_id: 'ttl-2', host: 'h', username: 'u', name: 'ok-b' })

    let ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /✗/ })).toBeDefined()
    await ui.unmount()

    // 过期的失败要清掉——TTL 判反了会一直留着
    await clock.advance(31 * 60 * 1000)
    await $.tool.call({ tool: 'mcp__ssh__ssh_connect', tool_use_id: 'ttl-3', host: 'h', username: 'u', name: 'ok-c' })

    ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /✗/ })).toBeUndefined()
    await ui.unmount()
  })

  test('keeps the NEWEST disconnected rows, not the oldest', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, input => answered(connectPayload(String(input.name))))
    answerDisconnect(on, () => answered(JSON.stringify({ message: 'closed' })))

    // 7 条断开，超过 KEEP_DISCONNECTED=5，留下的必须是最近那几条
    for (let i = 0; i < 7; i++) {
      await $.tool.call({
        tool: 'mcp__ssh__ssh_connect',
        tool_use_id: 'disc-n-' + i,
        host: 'h',
        username: 'u',
        name: 'd-' + i,
      })
      await $.tool.call({ tool: 'mcp__ssh__ssh_disconnect', tool_use_id: 'disc-x-' + i })
      await clock.advance(1000)
    }
    // 再触发一次 prune
    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'disc-final',
      host: 'h',
      username: 'u',
      name: 'trigger',
    })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    // 最新的 d-6 / d-5 要在；最早的 d-0 / d-1 该被挤掉
    expect(await ui.find({ type: 'Text', text: /d-6/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /d-0/ })).toBeUndefined()
    await ui.unmount()
  })

  test('over the cap, keeps the OLDEST-but-live and drops the newest live last', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, input => answered(connectPayload(String(input.name))))

    // 45 条全是 connected，同一档内按年龄取舍——留下的必须是最早那 40 条
    for (let i = 0; i < 45; i++) {
      await $.tool.call({
        tool: 'mcp__ssh__ssh_connect',
        tool_use_id: 'cap-r-' + i,
        host: 'h',
        username: 'u',
        name: 'live-' + String(i).padStart(2, '0'),
      })
      await clock.advance(1000)
    }

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // 40 条存活；被挤掉的是最新的那 5 条（live-40..live-44）
    expect(await ui.find({ type: 'Text', text: /●40/ })).toBeDefined()
    await ui.unmount()

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /live-00/ })).toBeDefined()
    expect(await pane.find({ type: 'Text', text: /live-44/ })).toBeUndefined()
    await pane.unmount()
  })
})

describe('ssh-status secret handling, exhaustive', () => {
  test('redacts every occurrence of a secret, not just the first', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, () =>
      errored('auth failed: s3cr3tpw rejected, retried with s3cr3tpw again'),
    )

    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'sec-6',
      host: 'jms.company.com',
      username: 'admin',
      name: 'web-01',
      password: 's3cr3tpw',
    })

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /s3cr3tpw/ })).toBeUndefined()
    await pane.unmount()
  })

  test('strips userinfo from the username field too', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, () => answered(connectPayload('web-01')))

    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'sec-7',
      host: 'jms.company.com',
      username: 'deploy:s3cr3t@realuser',
      name: 'web-01',
    })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /s3cr3t/ })).toBeUndefined()
    await ui.unmount()

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /s3cr3t/ })).toBeUndefined()
    // 不光要「看不见密码」，还得「看见的确实是主机」——半截泄漏靠缺席断言测不出来
    expect(await pane.find({ type: 'Text', text: /realuser/ })).toBeDefined()
    await pane.unmount()
  })

  test('leaves nothing but the bare host after stripping userinfo', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, () => answered(connectPayload('web-01')))

    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'sec-8',
      host: 'deploy:p@ss@10.0.0.5',
      username: 'root',
      name: 'web-01',
    })

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    // 取错 @ 会漏出 ss@10.0.0.5 这种尾巴，缺席断言抓不到，必须断言「就是 10.0.0.5」
    const row = await pane.find({ type: 'Text', text: /10[.]0[.]0[.]5/ })
    expect(row?.text).toContain('10.0.0.5')
    expect(row?.text ?? '').not.toContain('ss@')
    await pane.unmount()
  })
})

describe('ssh-status command and lifecycle', () => {
  test('/ssh-status clear drops dead rows but never live ones', async ($, on) => {
    mock.clock(on, { now: 0 })
    answerCommand(on)
    answerConnectNamed(on)
    answerDisconnect(on, () => answered(JSON.stringify({ closed: ['gone'], message: 'closed' })))

    await $.tool.call(CONNECT_INPUT)
    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'keep-1', name: 'keep-me' })
    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'gone-1', name: 'gone' })
    await $.tool.call({ tool: 'mcp__ssh__ssh_disconnect', tool_use_id: 'gone-d', connectionId: 'gone' })

    await runSshStatus($, 'clear')

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // clear 的判断写成恒真会连活连接一起清掉
    expect(await ui.find({ type: 'Text', text: /keep-me/ })).toBeDefined()
    await ui.unmount()
  })

  test('a connect that rejects ends as failed, never stuck in connecting', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => {
      throw new Error('transport blew up')
    })

    await $.tool.call(CONNECT_INPUT).catch(() => undefined)
    await clock.settle()

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: /✗/ })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: /…/ })).toBeUndefined()
    await ui.unmount()
  })
})

"""

P.write_text(s.replace(anchor, BLOCK + anchor), encoding="utf-8")
print("added high-value teeth-tests")
