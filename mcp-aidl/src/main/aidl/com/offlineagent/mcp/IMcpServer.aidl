// On-device Model Context Protocol over binder (spec: docs/MCP_ANDROID_SPEC.md).
package com.offlineagent.mcp;

interface IMcpServer {
    /** Protocol revision of this binding (currently 1). */
    int bindingVersion();
    /**
     * One JSON-RPC 2.0 message in, the JSON-RPC response out (or null for notifications).
     * Called on a binder thread; servers may block while they work (clients call it off the main thread).
     */
    String handle(String jsonRpcMessage);
}
