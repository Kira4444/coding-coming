#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修正两条测试：cap 断言要对齐「按最旧淘汰」的意图，并覆盖到「撞上限后新连接仍显示」。"""
import pathlib

P = pathlib.Path(
    r"C:/Users/50822/.claude/dev-mods/f51a9127-8860-48cf-be1c-a93d7b8eb66d/ssh-status/tests/ssh-status.test.tsx"
)
s = P.read_text(encoding="utf-8")

# ---- 1) cap 测试：意图是「按最旧淘汰」= 留最新的 40；再补一条撞上限后新连接仍出现 ----
old_cap = """  test('over the cap, keeps the OLDEST-but-live and drops the newest live last', async ($, on) => {
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
  })"""

new_cap = """  test('over the cap, evicts the OLDEST live rows and keeps the newest', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, input => answered(connectPayload(String(input.name))))

    // 45 条全是 connected，同一档内按年龄取舍——按最旧淘汰，留下的应是最新那 40 条
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
    expect(await ui.find({ type: 'Text', text: /●40/ })).toBeDefined()
    await ui.unmount()

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /live-44/ })).toBeDefined()
    expect(await pane.find({ type: 'Text', text: /live-00/ })).toBeUndefined()
    await pane.unmount()
  })

  test('a connection made after the cap is hit still shows up', async ($, on) => {
    const clock = mock.clock(on, { now: 0 })
    drawFallback(on)
    answerConnect(on, input => answered(connectPayload(String(input.name))))

    // 先把 40 条塞满
    for (let i = 0; i < 40; i++) {
      await $.tool.call({
        tool: 'mcp__ssh__ssh_connect',
        tool_use_id: 'full-' + i,
        host: 'h',
        username: 'u',
        name: 'base-' + String(i).padStart(2, '0'),
      })
      await clock.advance(1000)
    }

    // 再来一条。connecting 曾经排在 connected 后面，一撞上限占位行就被挤掉，
    // 随后 markRows 找不到它——这条连接会永远不显示。
    await $.tool.call({
      tool: 'mcp__ssh__ssh_connect',
      tool_use_id: 'late-1',
      host: 'h',
      username: 'u',
      name: 'newcomer',
    })
    await clock.advance(1000)

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /newcomer/ })).toBeDefined()
    await pane.unmount()
  })"""

assert old_cap in s, "cap test anchor not found"
s = s.replace(old_cap, new_cap)

# ---- 2) clear 测试：光断言活的还在不够，还得断言死的被清掉了 ----
old_clear = """    await runSshStatus($, 'clear')

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // clear 的判断写成恒真会连活连接一起清掉
    expect(await ui.find({ type: 'Text', text: /keep-me/ })).toBeDefined()
    await ui.unmount()
  })"""

new_clear = """    await runSshStatus($, 'clear')

    const ui = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...BAND })
    // 两面都要断言：活的留下（判断写成恒真会连它一起清掉），死的清掉（什么都不干也照样过）
    expect(await ui.find({ type: 'Text', text: /keep-me/ })).toBeDefined()
    await ui.unmount()

    const pane = await $.ui.mount({ plugin: 'ssh-status', surface: 'terminal', ...PANE })
    expect(await pane.find({ type: 'Text', text: /gone/ })).toBeUndefined()
    await pane.unmount()
  })"""

assert old_clear in s, "clear test anchor not found"
s = s.replace(old_clear, new_clear)

P.write_text(s, encoding="utf-8")
print("cap and clear tests corrected")
