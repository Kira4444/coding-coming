package com.codingagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 项目 = 一个代码库。 */
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;
    private String name;
    private String repoType;
    private String repoUrl;
    private String localPath;
    private String sandboxImage;
    private String modelOverride;

    // ===== agent 行为配置 =====
    private String agentInstructions;
    private String enabledTools;
    private String approvalPolicy;
    private Integer maxTurns;
    private Long tokenBudget;
    private String excludePaths;

    // ===== 发布到支线仓库（同远程的发布分支）=====
    private String publishBranch;
    private String publishRemote;
    private String publishCredentialType;
    private String publishUsername;
    /** AES-GCM 密文，明文绝不入库 */
    private String publishCredential;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRepoType() {
        return repoType;
    }

    public void setRepoType(String repoType) {
        this.repoType = repoType;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getSandboxImage() {
        return sandboxImage;
    }

    public void setSandboxImage(String sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    public String getModelOverride() {
        return modelOverride;
    }

    public void setModelOverride(String modelOverride) {
        this.modelOverride = modelOverride;
    }

    public String getAgentInstructions() {
        return agentInstructions;
    }

    public void setAgentInstructions(String agentInstructions) {
        this.agentInstructions = agentInstructions;
    }

    public String getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(String enabledTools) {
        this.enabledTools = enabledTools;
    }

    public String getApprovalPolicy() {
        return approvalPolicy;
    }

    public void setApprovalPolicy(String approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
    }

    public Long getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(Long tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public String getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(String excludePaths) {
        this.excludePaths = excludePaths;
    }

    public String getPublishBranch() {
        return publishBranch;
    }

    public void setPublishBranch(String publishBranch) {
        this.publishBranch = publishBranch;
    }

    public String getPublishRemote() {
        return publishRemote;
    }

    public void setPublishRemote(String publishRemote) {
        this.publishRemote = publishRemote;
    }

    public String getPublishCredentialType() {
        return publishCredentialType;
    }

    public void setPublishCredentialType(String publishCredentialType) {
        this.publishCredentialType = publishCredentialType;
    }

    public String getPublishUsername() {
        return publishUsername;
    }

    public void setPublishUsername(String publishUsername) {
        this.publishUsername = publishUsername;
    }

    public String getPublishCredential() {
        return publishCredential;
    }

    public void setPublishCredential(String publishCredential) {
        this.publishCredential = publishCredential;
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
