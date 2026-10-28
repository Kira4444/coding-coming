package com.codingagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 轮次检查点 = rewind 的回滚点。
 *
 * <p>每个 agent 轮次结束时（AssistantCommitted 落库后）把工作区代码状态快照一次，
 * 记下「该轮结束时的 HEAD」与「快照 commit」。回滚到第 N 轮就是把代码切回
 * 第 N 个检查点记录的状态。
 *
 * <p>turn=0 是会话初始状态，在首条消息发送前建立（幂等）。
 */
@TableName("turn_checkpoint")
public class TurnCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Integer turn;
    /** 该轮结束时工作区 HEAD（含 agent 用 git_commit 打的普通提交） */
    private String headSha;
    /** 快照 commit 的 sha，其树 = 该轮结束时的工作区状态（含未提交改动） */
    private String snapshotSha;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public String getHeadSha() {
        return headSha;
    }

    public void setHeadSha(String headSha) {
        this.headSha = headSha;
    }

    public String getSnapshotSha() {
        return snapshotSha;
    }

    public void setSnapshotSha(String snapshotSha) {
        this.snapshotSha = snapshotSha;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
