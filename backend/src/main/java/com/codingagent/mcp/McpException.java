package com.codingagent.mcp;

/** MCP 通信失败。 */
public class McpException extends RuntimeException {

    private final Integer rpcCode;

    public McpException(String message) {
        super(message);
        this.rpcCode = null;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.rpcCode = null;
    }

    public McpException(String message, Integer rpcCode) {
        super(message);
        this.rpcCode = rpcCode;
    }

    /** JSON-RPC 错误码，未知时为 null */
    public Integer rpcCode() {
        return rpcCode;
    }
}
