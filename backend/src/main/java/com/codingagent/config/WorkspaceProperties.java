package com.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 工作区落盘配置。 */
@ConfigurationProperties(prefix = "coding-agent")
public class WorkspaceProperties {

    private String workspaceRoot = "./data/workspaces";

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }
}
