#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 5 个漏网的变异补上带牙齿的测试。"""
import pathlib

P = pathlib.Path(
    r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status/tests/ssh-status.test.tsx"
)
s = P.read_text(encoding="utf-8")

anchor = "describe('ssh-status pane', () => {"
assert s.count(anchor) == 1, f"anchor count = {s.count(anchor)}"

BLOCK = r"""describe('ssh-status parsing branches', () => {
  test('reads the reason from ran.text when result carries none', async ($, on) => {
    mock.clock(on, { now: 0 })
    // 只有 text，result 缺席。走不到 text 优先那条分支的话，lastError 会是空的。
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => ({
      isError: true,
      text: 'SSH connection failed: ENOTFOUND via text',
    }))

    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'p-1', name: 'web-02' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await ui.find({ type: 'Text', text: /ENOTFOUND via text/ })).toBeDefined()
    await ui.unmount()
  })

  test('reads the reason from a string result when text is absent', async ($, on) => {
    mock.clock(on, { now: 0 })
    // core 的出错态：result 是错误字符串，text 可能没填。这两个分支必须各测各的，
    // 否则一边被删掉、另一边照常工作，测试根本看不出来。
    on('tool.call', { tool: 'mcp__ssh__ssh_connect' }, async () => ({
      isError: true,
      result: 'SSH connection failed: ENOTFOUND via result',
    }))

    await $.tool.call({ ...CONNECT_INPUT, tool_use_id: 'p-2', name: 'web-02' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await ui.find({ type: 'Text', text: /ENOTFOUND via result/ })).toBeDefined()
    await ui.unmount()
  })

  test('takes the connectionId from JSON even when the regex would truncate it', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // JumpServer 直连语法当 name 时，id 里带 #，正则的字符类里没有它、会被砍成 "root"
    answerConnect(on, () => answered(connectPayload('root#10.1.2.3#admin')))
    const src = { ids: ['root#10.1.2.3#admin'] as string[] | 'error' }
    answerListFrom(on, src)

    await $.tool.call({ ...CONNECT_INPUT, name: 'root#10.1.2.3#admin', tool_use_id: 'p-3' })
    await $.tool.call({ tool: 'mcp__ssh__ssh_list_connections', tool_use_id: 'p-3-list' })

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // 只走正则的话 id 会被截成 root，对账匹配不上、被降级，状态条就不画了
    expect(await ui.find({ type: 'Text', text: /●1/ })).toBeDefined()
    await ui.unmount()
  })
})

describe('ssh-status secret handling, deeper', () => {
  test('redacts a credential passed under an alias key like token', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // SSH 服务器自己定参数名，token/secret/key 这类别名也得抹
    answerConnect(on, () => errored('auth failed with token tok-s3cret9 here'))

    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'sec-4',
      host: 'jms.company.com',
      username: 'admin',
      name: 'web-01',
      token: 'tok-s3cret9',
    })

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /tok-s3cret9/ })).toBeUndefined()
    await pane.unmount()
  })

  test('scrubs user:pass@ echoed in the result text even with no matching arg key', async ($, on) => {
    mock.clock(on, { now: 0 })
    drawFallback(on)
    // 入参里没有 password 字段，但报错文本回显了 userinfo——光按 key 抹是抹不到的
    answerConnect(on, () => errored('cannot reach deploy:s3cr3t@10.0.0.5, check the host'))

    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'sec-5',
      host: '10.0.0.5',
      username: 'deploy',
      name: 'web-01',
    })

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /s3cr3t/ })).toBeUndefined()
    // 主机本身还得看得见，否则报错没法排查
    expect(await pane.find({ type: 'Text', text: /10[.]0[.]0[.]5/ })).toBeDefined()
    await pane.unmount()
  })
})

"""

P.write_text(s.replace(anchor, BLOCK + anchor), encoding="utf-8")
print("added 5 teeth-tests")
