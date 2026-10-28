package com.codingagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 一次回滚/恢复操作日志。
 *
 * <p>每行记录一次状态切换的完整两端：切换前状态（backup_ref 快照 + backup_head_sha +
 * 当时活跃轮次）与切换动作（rolled_turns / unrolled_turns 两个轮次集合）。
 * 「恢复」= 把某一行的切换精确反向执行，并把自己也记成新的一行，
 * 所以恢复本身也可被撤销（= 重做）。数据永不删除。
 */
@TableName("session_rewind")
public class SessionRewind {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Long userId;
    /** REWIND=回滚到某轮 / RECOVER=撤销某一步（恢复） */
    private String kind;
    /** 操作前的时间线尖端轮次 */
    private Integer fromTurn;
    /** 操作后的时间线尖端轮次 */
    private Integer toTurn;
    /** 被本次撤销的操作 id（kind=RECOVER 时有值） */
    private Long undoneOpId;
    /** 本次翻转为「已回滚」的轮次，JSON int 数组；空数组写 "[]"，不写 NULL */
    private String rolledTurns;
    /** 本次翻转为「活跃」的轮次，JSON int 数组 */
    private String unrolledTurns;
    /** 操作前状态备份的 git ref 名（refs/coding-agent/backup/&lt;sessionId&gt;/&lt;opId&gt;） */
    private String backupRef;
    /** 备份快照 commit sha，其树 = 操作前的工作区状态 */
    private String backupSnapshotSha;
    /** 操作前的工作区 HEAD */
    private String backupHeadSha;
    /** PENDING / APPLIED / RESTORED / FAILED */
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getFromTurn() {
        return fromTurn;
    }

    public void setFromTurn(Integer fromTurn) {
        this.fromTurn = fromTurn;
    }

    public Integer getToTurn() {
        return toTurn;
    }

    public void setToTurn(Integer toTurn) {
        this.toTurn = toTurn;
    }

    public Long getUndoneOpId() {
        return undoneOpId;
    }

    public void setUndoneOpId(Long undoneOpId) {
        this.undoneOpId = undoneOpId;
    }

    public String getRolledTurns() {
        return rolledTurns;
    }

    public void setRolledTurns(String rolledTurns) {
        this.rolledTurns = rolledTurns;
    }

    public String getUnrolledTurns() {
        return unrolledTurns;
    }

    public void setUnrolledTurns(String unrolledTurns) {
        this.unrolledTurns = unrolledTurns;
    }

    public String getBackupRef() {
        return backupRef;
    }

    public void setBackupRef(String backupRef) {
        this.backupRef = backupRef;
    }

    public String getBackupSnapshotSha() {
        return backupSnapshotSha;
    }

    public void setBackupSnapshotSha(String backupSnapshotSha) {
        this.backupSnapshotSha = backupSnapshotSha;
    }

    public String getBackupHeadSha() {
        return backupHeadSha;
    }

    public void setBackupHeadSha(String backupHeadSha) {
        this.backupHeadSha = backupHeadSha;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
