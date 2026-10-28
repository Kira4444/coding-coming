package com.codingagent.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.codingagent.git.GitSnapshotService;
import com.codingagent.storage.entity.Message;
import com.codingagent.storage.entity.SessionRewind;
import com.codingagent.storage.entity.TurnCheckpoint;
import com.codingagent.storage.mapper.MessageMapper;
import com.codingagent.storage.mapper.SessionRewindMapper;
import com.codingagent.storage.mapper.TurnCheckpointMapper;
import com.codingagent.web.dto.Dtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 会话回滚 / 恢复（rewind）——对话与代码绑在一起回退。
 *
 * <p>核心不变量：
 * <ul>
 *   <li><b>回滚点 = agent 轮次。</b>回滚到第 N 轮 = 保留 1..N 轮、撤销 N+1 轮及之后。</li>
 *   <li><b>可恢复。</b>回滚不删数据：git 建备份 ref，消息只打「已回滚」标，
 *       随时能恢复回来。</li>
 *   <li><b>代码与对话绑一起。</b>选一个点，git 切到那轮结束状态 + 消息截到那轮。
 *       不提供只回其一。</li>
 * </ul>
 *
 * <p>分层：本类管业务语义与事务；{@link SessionController} 只做鉴权和 DTO 转换；
 * git 快照/切换的脏活在 {@link GitSnapshotService}。
 */
@Service
public class RewindService {

    private static final Logger log = LoggerFactory.getLogger(RewindService.class);

    /** prompt / assistantSummary 展示截断长度 */
    private static final int SUMMARY_MAX = 200;

    private final MessageMapper messageMapper;
    private final TurnCheckpointMapper turnCheckpointMapper;
    private final SessionRewindMapper sessionRewindMapper;
    private final GitSnapshotService gitSnapshotService;
    private final SessionService sessionService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    /**
     * 回滚/恢复自身的互斥闸门（按会话）。
     *
     * <p>与 {@link SessionService#tryAcquireRunGate}（agent 的 running 闸门）互补：
     * 那边挡住「回滚 vs 跑 agent」，这边挡住「回滚 vs 回滚」——
     * 会话未激活（无 runtime）时 agent 闸门放行，这边仍要串行化。
     */
    private final ConcurrentHashMap<String, AtomicBoolean> opGates = new ConcurrentHashMap<>();

    public RewindService(MessageMapper messageMapper,
                         TurnCheckpointMapper turnCheckpointMapper,
                         SessionRewindMapper sessionRewindMapper,
                         GitSnapshotService gitSnapshotService,
                         SessionService sessionService,
                         ObjectMapper mapper,
                         PlatformTransactionManager txManager) {
        this.messageMapper = messageMapper;
        this.turnCheckpointMapper = turnCheckpointMapper;
        this.sessionRewindMapper = sessionRewindMapper;
        this.gitSnapshotService = gitSnapshotService;
        this.sessionService = sessionService;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ═══════════════ 1. 列出可回滚点 ═══════════════

    /**
     * 会话的全部可回滚点（= 检查点），按 turn 降序，turn=0 恒在最后。
     *
     * <p>纯查库，工作区已回收也照常返回——只是回滚类接口会 409。
     * 没有检查点的轮（流中途 Failed、异常中断）不出现：不能承诺还原到一个没快照的状态。
     */
    public Dtos.RewindPointsResponse rewindPoints(com.codingagent.storage.entity.Session session) {
        String sid = session.getId();
        List<Message> messages = allMessages(sid);
        List<TurnCheckpoint> checkpoints = turnCheckpointMapper.selectList(
                Wrappers.<TurnCheckpoint>lambdaQuery()
                        .eq(TurnCheckpoint::getSessionId, sid)
                        .orderByAsc(TurnCheckpoint::getTurn));

        List<Dtos.RewindPointView> points = new ArrayList<>();
        for (TurnCheckpoint cp : checkpoints) {
            points.add(toPointView(cp, messages));
        }
        // turn 升序排出来后天然「最新在上、0 在最后」——按降序返回给前端
        points.sort((a, b) -> Integer.compare(b.turn(), a.turn()));

        return new Dtos.RewindPointsResponse(
                currentTurnOf(sid),
                sessionService.isRunning(sid),
                Boolean.TRUE.equals(session.getWorkspacePurged()),
                points);
    }

    private Dtos.RewindPointView toPointView(TurnCheckpoint cp, List<Message> messages) {
        int turn = cp.getTurn();
        String prompt = null;
        String summary = null;
        int count = 0;
        // turn=0 是「会话初始状态」：无消息行，恒视为活跃时间线的根
        boolean active = (turn == 0);
        Long lastUserId = null;

        for (Message m : messages) {
            // messageCount / active 按存的 turn 严格相等；NULL turn 的旧数据视为第 0 轮，
            // 只参与 prompt 的「之前最近一条 USER」搜索，不参与计数
            if (m.getTurn() != null && m.getTurn() == turn) {
                count++;
                if (!Boolean.TRUE.equals(m.getRolledBack())) {
                    active = true;
                }
                if ("ASSISTANT".equals(m.getRole()) && summary == null) {
                    summary = truncate(m.getContent());
                }
            }
            if ("USER".equals(m.getRole()) && turn > 0) {
                int t = m.getTurn() == null ? 0 : m.getTurn();
                // 该轮所属 run 的用户输入 = 该 turn（含）之前最近一条 USER 消息
                if (t <= turn && (lastUserId == null || m.getId() > lastUserId)) {
                    lastUserId = m.getId();
                    prompt = truncate(m.getContent());
                }
            }
        }

        String headSha = cp.getHeadSha();
        String shortSha = headSha != null && headSha.length() >= 7
                ? headSha.substring(0, 7) : headSha;
        return new Dtos.RewindPointView(
                turn, prompt, summary, count, headSha, shortSha, cp.getCreatedAt(), active);
    }

    // ═══════════════ 2. 执行回滚 ═══════════════

    public Dtos.RewindOpView rewind(com.codingagent.storage.entity.Session session,
                                    Dtos.RewindRequest req, Long userId) {
        if (req == null || req.turn() == null) {
            throw new IllegalArgumentException("turn 不能为空");
        }
        int target = req.turn();
        if (target < 0) {
            throw new IllegalArgumentException("turn 不能低于 0");
        }
        return withGate(session, () -> doRewind(session, target, req.expectedTipTurn(), userId));
    }

    private Dtos.RewindOpView doRewind(com.codingagent.storage.entity.Session session,
                                       int target, Integer expectedTipTurn, Long userId) {
        String sid = session.getId();
        requireNotPurged(session);

        int tip = currentTurnOf(sid);
        checkTip(expectedTipTurn, tip);

        TurnCheckpoint cp = turnCheckpointMapper.selectOne(
                Wrappers.<TurnCheckpoint>lambdaQuery()
                        .eq(TurnCheckpoint::getSessionId, sid)
                        .eq(TurnCheckpoint::getTurn, target));
        if (cp == null) {
            throw new IllegalArgumentException("回滚目标不存在: turn=" + target);
        }

        // 翻转永远以 turn 为单位（整轮同进同出），不可能只翻一半 assistant/tool 配对。
        // NULL turn 的旧数据两条都不命中 → 永不被回滚，恒活跃（视为第 0 轮的一部分）。
        List<Integer> toRoll = activeTurnsAbove(sid, target);       // turn > N 的活跃轮 → 打掉
        List<Integer> toUnroll = rolledTurnsUpTo(sid, target);      // turn <= N 的空洞 → 补回
        if (toRoll.isEmpty() && toUnroll.isEmpty()) {
            throw new IllegalArgumentException("无需回滚：目标已是当前状态");
        }

        SessionRewind op = beginOp(session, userId, "REWIND", tip, target, null,
                toRoll, toUnroll);

        // 代码两步，目标 = 该轮结束时的检查点快照。
        // ⚠ 不能 reset --hard <snapshot_sha>：快照 commit 在独立父链上，
        // 会把会话分支指到快照链，发布/git log/diff 全乱。
        try {
            gitSnapshotService.restore(Path.of(session.getWorkspacePath()),
                    cp.getHeadSha(), cp.getSnapshotSha());
            flipTurns(sid, toUnroll, toRoll);
            finishOp(op, "APPLIED");
        } catch (Exception e) {
            failOp(op, e, true);
            throw new IllegalStateException("回滚失败: " + e.getMessage());
        }
        log.info("[{}] 回滚到 turn={}（撤销 {}），op={}", sid, target, toRoll, op.getId());
        return toOpView(op);
    }

    // ═══════════════ 3. 操作日志 ═══════════════

    /** 全部操作记录，按 id 降序（最新在上）。前端由此找「可恢复的操作」。 */
    public List<Dtos.RewindOpView> listOps(com.codingagent.storage.entity.Session session) {
        List<SessionRewind> rows = sessionRewindMapper.selectList(
                Wrappers.<SessionRewind>lambdaQuery()
                        .eq(SessionRewind::getSessionId, session.getId())
                        .orderByDesc(SessionRewind::getId));
        List<Dtos.RewindOpView> result = new ArrayList<>();
        for (SessionRewind row : rows) {
            result.add(toOpView(row));
        }
        return result;
    }

    // ═══════════════ 4. 恢复（撤销某一步） ═══════════════

    /**
     * 撤销 opId 这一步，回到它执行<b>之前</b>的状态。
     *
     * <p>opId 可以是 REWIND（= 恢复回滚前）也可以是 RECOVER（= 重做，回到恢复前），
     * 同一端点反复调用即可在两个状态间来回切换，无死角。
     */
    public Dtos.RewindOpView recover(com.codingagent.storage.entity.Session session,
                                     Long opId, Dtos.RecoverRequest req, Long userId) {
        Integer expectedTipTurn = req == null ? null : req.expectedTipTurn();
        return withGate(session, () -> doRecover(session, opId, expectedTipTurn, userId));
    }

    private Dtos.RewindOpView doRecover(com.codingagent.storage.entity.Session session,
                                        Long opId, Integer expectedTipTurn, Long userId) {
        String sid = session.getId();
        requireNotPurged(session);

        SessionRewind x = sessionRewindMapper.selectById(opId);
        if (x == null || !sid.equals(x.getSessionId())) {
            throw new IllegalArgumentException("操作不存在: " + opId);
        }
        // 栈纪律：只允许撤销「其后不存在更晚的未撤销记录」的操作。
        // 更晚的记录是 RESTORED（已撤销）或 FAILED（未生效，什么都没改）时不挡路。
        // PARTIAL 挡路——它是不一致态，必须先恢复它才谈得上继续。
        Long later = sessionRewindMapper.selectCount(
                Wrappers.<SessionRewind>lambdaQuery()
                        .eq(SessionRewind::getSessionId, sid)
                        .gt(SessionRewind::getId, opId)
                        .notIn(SessionRewind::getStatus, "RESTORED", "FAILED"));
        if (later != null && later > 0) {
            throw new IllegalStateException("请先撤销更晚的操作");
        }
        // PARTIAL（动了一半的不一致态）也必须能恢复——它正是靠这一步来修复的：
        // beginOp 的反转数学对任意半完成状态都成立，恢复 = 把代码和消息都拉回备份态。
        // 只认 APPLIED 的话，git 成功而 DB 失败那种半截操作就没救了。
        if (!"APPLIED".equals(x.getStatus()) && !"PARTIAL".equals(x.getStatus())) {
            throw new IllegalStateException("该操作已被撤销");
        }

        int tip = currentTurnOf(sid);
        checkTip(expectedTipTurn, tip);

        // 目标必须用 X 的备份快照：X 执行前的真实状态（可能含未提交改动、
        // 或孤儿轮留下的中间状态）只有备份快照是精确的，不能用 checkpoint(X.fromTurn) 代替。
        // APPLIED 行理应有备份；缺了就明确报错而不是拿空 sha 去跑 git。
        if (x.getBackupHeadSha() == null || x.getBackupSnapshotSha() == null) {
            throw new IllegalStateException("该操作缺少状态备份，无法恢复: " + opId);
        }

        // ── 对 X 的精确反转 ──
        // newUnrolled = X 当时标掉的，现在放回来
        // newRolled   = X 当时放回来的收回去 + X 之后新增的分歧轮次让位
        List<Integer> newUnrolled = parseTurns(x.getRolledTurns());
        Set<Integer> rolled = new LinkedHashSet<>(parseTurns(x.getUnrolledTurns()));
        rolled.addAll(activeTurnsAbove(sid, x.getFromTurn()));
        List<Integer> newRolled = new ArrayList<>(rolled);
        // 两集合天然不相交：前者当前已回滚/后者当前活跃，顺序无所谓

        SessionRewind op = beginOp(session, userId, "RECOVER", tip, x.getFromTurn(), x.getId(),
                newRolled, newUnrolled);

        // 代码切到 X 的备份快照（见上：绝不能用 checkpoint(X.fromTurn) 代替）
        try {
            gitSnapshotService.restore(Path.of(session.getWorkspacePath()),
                    x.getBackupHeadSha(), x.getBackupSnapshotSha());
            flipTurns(sid, newUnrolled, newRolled);
            x.setStatus("RESTORED");
            x.setUpdatedAt(Instant.now());
            sessionRewindMapper.updateById(x);
            finishOp(op, "APPLIED");
        } catch (Exception e) {
            failOp(op, e, true);
            throw new IllegalStateException("恢复失败: " + e.getMessage());
        }
        log.info("[{}] 恢复 op={}（{}→{}），新 op={}", sid, opId, tip, x.getFromTurn(), op.getId());
        return toOpView(op);
    }

    // ═══════════════ 操作日志的两端 ═══════════════

    /**
     * 备份当前状态并插一行 PENDING 操作日志。
     *
     * <p>流程：先插行拿 id → 建备份 ref → 回填 backup_ref。
     * 备份先行：全程任何时刻都有完整状态快照可回，任何一步失败整行标 FAILED、可重试。
     */
    private SessionRewind beginOp(com.codingagent.storage.entity.Session session, Long userId,
                                  String kind, int fromTurn, int toTurn, Long undoneOpId,
                                  List<Integer> rolledTurns, List<Integer> unrolledTurns) {
        String sid = session.getId();
        Path ws = Path.of(session.getWorkspacePath());

        // 快照 commit 的父提交 = 当时 HEAD + 上一快照，与轮次检查点同构——
        // 被回滚掉的提交因此永远从备份 ref 可达，不会被 gc 掉
        GitSnapshotService.Snapshot backup = gitSnapshotService.snapshot(
                ws, sid, GitSnapshotService.snapRef(sid),
                "coding-agent backup before " + kind.toLowerCase() + " to turn " + toTurn);

        SessionRewind op = new SessionRewind();
        op.setSessionId(sid);
        op.setUserId(userId);
        op.setKind(kind);
        op.setFromTurn(fromTurn);
        op.setToTurn(toTurn);
        op.setUndoneOpId(undoneOpId);
        op.setRolledTurns(writeTurns(rolledTurns));
        op.setUnrolledTurns(writeTurns(unrolledTurns));
        op.setBackupSnapshotSha(backup.snapshotSha());
        op.setBackupHeadSha(backup.headSha());
        op.setStatus("PENDING");
        op.setCreatedAt(Instant.now());
        op.setUpdatedAt(Instant.now());
        sessionRewindMapper.insert(op);

        try {
            // 备份 ref 永不覆盖：refs/coding-agent/backup/<sessionId>/<opId>
            String ref = GitSnapshotService.backupRef(sid, op.getId());
            gitSnapshotService.updateRef(ws, ref, backup.snapshotSha());
            op.setBackupRef(ref);
            sessionRewindMapper.updateById(op);
        } catch (RuntimeException e) {
            // 备份没落位就不许继续切换——标 FAILED 让栈纪律不被半成品行卡住，可原样重试
            failOp(op, e, false);
            throw e;
        }
        return op;
    }

    private void finishOp(SessionRewind op, String status) {
        op.setStatus(status);
        op.setUpdatedAt(Instant.now());
        sessionRewindMapper.updateById(op);
    }

    /**
     * @param mutated 是否已经开始改动状态（git restore / flipTurns 已经跑过）。
     *                两种失败的处置完全不同：<b>没动过</b>的可以直接重试；
     *                <b>动了一半</b>的是不一致态（代码切了消息没切，或反之），
     *                必须走 {@link #doRecover} 用备份快照拉回来——因为 {@link #beginOp}
     *                的反转数学对任意半完成状态都成立，恢复就是修复路径。
     *                不区分的话，恢复会去动那些「什么都没改」的操作，反而把回滚给做了。
     */
    private void failOp(SessionRewind op, Exception e, boolean mutated) {
        log.warn("[{}] 操作 {} 失败{}: {}", op.getSessionId(), op.getId(),
                mutated ? "（状态已改动一半）" : "", e.getMessage());
        try {
            finishOp(op, mutated ? "PARTIAL" : "FAILED");
        } catch (Exception ignored) {
            // 标记失败本身再失败就随它去——操作行还在，可原样重试（各步骤幂等）
        }
    }

    // ═══════════════ 消息翻转（一个 DB 事务） ═══════════════

    /**
     * 以整轮为单位翻转「已回滚」标。空集合跳过（避免 {@code IN ()} 语法错误）。
     *
     * <p>只打标不删行：界面灰显可见，模型历史由 {@code loadHistory} 过滤——
     * 同一张表两条筛选，别互相复用。
     */
    private void flipTurns(String sessionId, List<Integer> unrollTurns, List<Integer> rollTurns) {
        tx.executeWithoutResult(status -> {
            if (!unrollTurns.isEmpty()) {
                messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                        .set(Message::getRolledBack, false)
                        .eq(Message::getSessionId, sessionId)
                        .eq(Message::getRolledBack, true)
                        .in(Message::getTurn, unrollTurns));
            }
            if (!rollTurns.isEmpty()) {
                messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                        .set(Message::getRolledBack, true)
                        .eq(Message::getSessionId, sessionId)
                        .eq(Message::getRolledBack, false)
                        .in(Message::getTurn, rollTurns));
            }
        });
    }

    // ═══════════════ 查询辅助 ═══════════════

    private List<Message> allMessages(String sessionId) {
        return messageMapper.selectList(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getSessionId, sessionId)
                        .orderByAsc(Message::getId));
    }

    /** 当前活跃时间线尖端 = MAX(turn) WHERE rolled_back=0（无则 0） */
    private int currentTurnOf(String sessionId) {
        int max = 0;
        for (Message m : allMessages(sessionId)) {
            if (!Boolean.TRUE.equals(m.getRolledBack())
                    && m.getTurn() != null && m.getTurn() > max) {
                max = m.getTurn();
            }
        }
        return max;
    }

    /** 当前活跃且 turn &gt; afterTurn 的轮次（升序） */
    private List<Integer> activeTurnsAbove(String sessionId, int afterTurn) {
        Set<Integer> turns = new LinkedHashSet<>();
        for (Message m : allMessages(sessionId)) {
            if (!Boolean.TRUE.equals(m.getRolledBack())
                    && m.getTurn() != null && m.getTurn() > afterTurn) {
                turns.add(m.getTurn());
            }
        }
        return new ArrayList<>(turns);
    }

    /** 已回滚且 turn &lt;= upToTurn 的轮次（升序）——回滚时的「空洞」 */
    private List<Integer> rolledTurnsUpTo(String sessionId, int upToTurn) {
        Set<Integer> turns = new LinkedHashSet<>();
        for (Message m : allMessages(sessionId)) {
            if (Boolean.TRUE.equals(m.getRolledBack())
                    && m.getTurn() != null && m.getTurn() <= upToTurn) {
                turns.add(m.getTurn());
            }
        }
        return new ArrayList<>(turns);
    }

    private void requireNotPurged(com.codingagent.storage.entity.Session session) {
        if (Boolean.TRUE.equals(session.getWorkspacePurged())) {
            throw new IllegalStateException("工作区已回收，只能回看历史");
        }
    }

    private void checkTip(Integer expectedTipTurn, int tip) {
        if (expectedTipTurn != null && expectedTipTurn != tip) {
            throw new IllegalStateException("时间线已变化，请刷新后重试");
        }
    }

    // ═══════════════ 互斥闸门 ═══════════════

    /**
     * 回滚/恢复与 agent 运行互斥。
     *
     * <p>对进行中的 run 的处置是<b>拒绝</b>（409）而不是先中断：中断会在工具执行半途
     * 留下不一致的工作区，而「等当前轮次结束」只多等一会儿，换来可预期的一致性。
     * 与 sendMessage 互相挡住同一会话。
     */
    private Dtos.RewindOpView withGate(com.codingagent.storage.entity.Session session,
                                       Supplier<Dtos.RewindOpView> work) {
        AtomicBoolean gate = opGates.computeIfAbsent(session.getId(), k -> new AtomicBoolean(false));
        if (!gate.compareAndSet(false, true)) {
            throw new IllegalStateException("该会话正在执行中，请等当前轮次结束");
        }
        if (!sessionService.tryAcquireRunGate(session.getId())) {
            gate.set(false);
            throw new IllegalStateException("该会话正在执行中，请等当前轮次结束");
        }
        try {
            return work.get();
        } finally {
            sessionService.releaseRunGate(session.getId());
            gate.set(false);
        }
    }

    // ═══════════════ DTO 转换 ═══════════════

    private Dtos.RewindOpView toOpView(SessionRewind op) {
        List<Integer> rolled = parseTurns(op.getRolledTurns());
        // rolledMessageCount = 本次翻掉的消息行数（按 turn 集合数出来即可：
        // 行永不删除，行数与操作执行时一致）
        int rolledMessageCount = 0;
        if (!rolled.isEmpty()) {
            Long c = messageMapper.selectCount(
                    Wrappers.<Message>lambdaQuery()
                            .eq(Message::getSessionId, op.getSessionId())
                            .in(Message::getTurn, rolled));
            rolledMessageCount = c == null ? 0 : c.intValue();
        }
        return new Dtos.RewindOpView(
                op.getId(), op.getKind(), op.getFromTurn(), op.getToTurn(), op.getUndoneOpId(),
                rolled, parseTurns(op.getUnrolledTurns()), rolledMessageCount,
                op.getBackupRef(), op.getStatus(), op.getCreatedAt());
    }

    /** JSON int 数组 → List。NULL / 空串 / 解析失败一律按 [] 容错。 */
    private List<Integer> parseTurns(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Integer[] arr = mapper.readValue(json, Integer[].class);
            return new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            log.warn("解析轮次集合失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** List → JSON int 数组。空集合写 "[]"，不写 NULL。 */
    private String writeTurns(List<Integer> turns) {
        List<Integer> sorted = new ArrayList<>(turns == null ? List.of() : turns);
        sorted.sort(Integer::compareTo);
        try {
            return mapper.writeValueAsString(sorted);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= SUMMARY_MAX ? s : s.substring(0, SUMMARY_MAX);
    }
}
