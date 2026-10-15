package com.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** agent 执行期的预算与截断配置。全部外置，便于按部署规模调。 */
@ConfigurationProperties(prefix = "coding-agent.agent")
public class AgentProperties {

    private int maxTurns = 40;
    private long tokenBudget = 500_000L;
    private int shellOutputMaxLines = 200;
    private int shellOutputMaxChars = 20_000;
    private int fileReadMaxLines = 500;
    private int searchMaxResults = 100;

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public long getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(long tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public int getShellOutputMaxLines() {
        return shellOutputMaxLines;
    }

    public void setShellOutputMaxLines(int shellOutputMaxLines) {
        this.shellOutputMaxLines = shellOutputMaxLines;
    }

    public int getShellOutputMaxChars() {
        return shellOutputMaxChars;
    }

    public void setShellOutputMaxChars(int shellOutputMaxChars) {
        this.shellOutputMaxChars = shellOutputMaxChars;
    }

    public int getFileReadMaxLines() {
        return fileReadMaxLines;
    }

    public void setFileReadMaxLines(int fileReadMaxLines) {
        this.fileReadMaxLines = fileReadMaxLines;
    }

    public int getSearchMaxResults() {
        return searchMaxResults;
    }

    public void setSearchMaxResults(int searchMaxResults) {
        this.searchMaxResults = searchMaxResults;
    }
}
