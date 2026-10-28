package com.codingagent.web;

import com.codingagent.storage.mapper.SessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话执行闸门必须与「runtime 是否已激活」无关。
 *
 * <p>历史 bug：闸门内嵌在 SessionRuntime 里，而 runtime 要等 activate() 才建出来。
 * 重启后某个会话还没发过消息时，回滚的 tryAcquireRunGate 读到 null 就直接放行——
 * 紧接着 sendMessage 建出 runtime 开跑，两边同时改同一份工作区。
 * 触发顺序很窄（要撞上「重启后第一次操作」），但跨标签/走 API 就是常态。
 *
 * <p>这个测试精确锁住那个不变量：冷会话上并发抢锁，必须只有一个赢。
 * 修复前这里会 N 个全过（每个都拿到空锁）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:session-gate-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "spring.sql.init.continue-on-error=true",
        "coding-agent.secret-key=gate-test-key-not-for-prod",
        "coding-agent.sandbox.driver=local",
})
class SessionGateTest {

    @Autowired
    SessionService sessionService;

    @Autowired
    SessionMapper sessionMapper;

    @Autowired
    com.codingagent.storage.mapper.ProjectMapper projectMapper;

    @TempDir
    Path temp;

    @Test
    void 冷会话并发抢锁只能有一个赢() throws Exception {
        // 刻意用一个从未 activate 过的会话 id —— 这正是 bug 的触发面
        String sessionId = "sess_coldgate_never_activated";

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (sessionService.tryAcquireRunGate(sessionId)) {
                        won.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "线程没就绪");
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程没结束");
        } finally {
            pool.shutdownNow();
        }

        // 修复前：won == 8（每个都拿到「空锁」直接放行）
        assertEquals(1, won.get(), "冷会话上并发抢锁必须只有一个赢");
    }

    @Test
    void 抢到的锁要能正常释放复用() {
        String sessionId = "sess_gate_release_reuse";

        assertTrue(sessionService.tryAcquireRunGate(sessionId), "首次抢锁应成功");
        assertFalse(sessionService.tryAcquireRunGate(sessionId), "未释放时二次抢锁必须失败");

        sessionService.releaseRunGate(sessionId);
        assertTrue(sessionService.tryAcquireRunGate(sessionId), "释放后应能再次抢到");
        sessionService.releaseRunGate(sessionId);
    }

    @Test
    void isRunning与闸门是同一份真相() {
        String sessionId = "sess_gate_isrunning";

        assertFalse(sessionService.isRunning(sessionId), "未抢锁时不在运行");
        assertTrue(sessionService.tryAcquireRunGate(sessionId));
        assertTrue(sessionService.isRunning(sessionId), "抢锁后应显示运行中");
        sessionService.releaseRunGate(sessionId);
        assertFalse(sessionService.isRunning(sessionId), "释放后应显示空闲");
    }

    /**
     * 同步段抛异常时闸门必须归还，否则这个会话的回滚与发消息会被永久卡死。
     *
     * <p>用「工作区已回收」这条真实路径触发 activate() 抛异常——比 mock DB 失败更贴近现场。
     */
    @Test
    void 同步段抛异常后闸门必须归还() {
        // 先造一个 project，否则 session 的 fk_session_project 会挡
        com.codingagent.storage.entity.Project p = new com.codingagent.storage.entity.Project();
        p.setOwnerId(1L);
        p.setName("gate-leak-proj");
        p.setRepoType("LOCAL_PATH");
        p.setLocalPath(temp.toString());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectMapper.insert(p);

        com.codingagent.storage.entity.Session s = new com.codingagent.storage.entity.Session();
        s.setId("sess_gate_leak_check");
        s.setProjectId(p.getId());
        s.setUserId(1L);
        s.setTitle("gate leak");
        s.setWorkspacePath(temp.toString());
        s.setBranchName("coding-agent/gate-leak");
        s.setStatus("ARCHIVED");
        s.setWorkspacePurged(true);          // ← 让 activate() 直接抛「工作区已回收」
        s.setPromptTokens(0L);
        s.setCompletionTokens(0L);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        sessionMapper.insert(s);

        assertThrows(IllegalStateException.class,
                () -> sessionService.sendMessage(s, "hi", 1L),
                "工作区已回收时应抛出");

        // 关键断言：闸门没被泄漏，还能再抢到
        assertTrue(sessionService.tryAcquireRunGate(s.getId()),
                "同步段抛异常后闸门必须归还，否则该会话永久卡死");
        sessionService.releaseRunGate(s.getId());
    }
}
