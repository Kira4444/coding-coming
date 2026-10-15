package com.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 沙箱与工作区配置。 */
@ConfigurationProperties(prefix = "coding-agent.sandbox")
public class SandboxProperties {

    /** local / docker */
    private String driver = "local";
    private String defaultImage = "coding-agent/sandbox:latest";
    /** public / none / custom */
    private String network = "public";
    private int cpu = 2;
    private String memory = "4g";
    private int maxConcurrentSessions = 4;

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getDefaultImage() {
        return defaultImage;
    }

    public void setDefaultImage(String defaultImage) {
        this.defaultImage = defaultImage;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public int getCpu() {
        return cpu;
    }

    public void setCpu(int cpu) {
        this.cpu = cpu;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public int getMaxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public void setMaxConcurrentSessions(int maxConcurrentSessions) {
        this.maxConcurrentSessions = maxConcurrentSessions;
    }
}
