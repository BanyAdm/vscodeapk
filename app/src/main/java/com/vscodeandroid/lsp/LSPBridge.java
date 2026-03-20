package com.vscodeandroid.lsp;

import android.content.Context;

import com.vscodeandroid.bridge.TermuxBridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages Language Server Protocol (LSP) servers running inside Termux.
 * Each language server runs as a real process in Termux and communicates
 * with VS Code via stdio or TCP.
 *
 * VS Code's built-in language client connects to these servers automatically
 * once they're started, giving full IntelliSense, go-to-definition, etc.
 */
public class LSPBridge {

    private final Context context;
    private final TermuxBridge termuxBridge;

    // language -> port mapping for running servers
    private final Map<String, Integer> runningServers = new HashMap<>();
    private int nextPort = 2090;

    // Language server install commands (run once via pkg in Termux)
    private static final Map<String, String> INSTALL_COMMANDS = new HashMap<String, String>() {{
        put("javascript", "npm install -g typescript-language-server typescript");
        put("typescript", "npm install -g typescript-language-server typescript");
        put("python", "pip install python-lsp-server");
        put("java", "pkg install openjdk-21 && " +
            "curl -L https://github.com/eclipse/eclipse.jdt.ls/releases/latest/download/jdt-language-server.tar.gz | tar -xz -C ~/lsp/java");
        put("rust", "rustup component add rust-analyzer");
        put("go", "go install golang.org/x/tools/gopls@latest");
        put("cpp", "pkg install clangd");
        put("html", "npm install -g vscode-langservers-extracted");
        put("css", "npm install -g vscode-langservers-extracted");
        put("json", "npm install -g vscode-langservers-extracted");
        put("bash", "npm install -g bash-language-server");
        put("lua", "pkg install lua-language-server");
    }};

    // Language server start commands
    private static final Map<String, String> START_COMMANDS = new HashMap<String, String>() {{
        put("javascript", "typescript-language-server --stdio");
        put("typescript", "typescript-language-server --stdio");
        put("python", "pylsp");
        put("rust", "rust-analyzer");
        put("go", "gopls");
        put("cpp", "clangd");
        put("html", "vscode-html-language-server --stdio");
        put("css", "vscode-css-language-server --stdio");
        put("json", "vscode-json-language-server --stdio");
        put("bash", "bash-language-server start");
        put("lua", "lua-language-server");
    }};

    public LSPBridge(Context context, TermuxBridge termuxBridge) {
        this.context = context;
        this.termuxBridge = termuxBridge;
    }

    /**
     * Starts a language server for the given language.
     * Returns the port the server is listening on.
     * VS Code Web connects to this port via the JS patch.
     */
    public int startServer(String language, String projectRoot) {
        if (runningServers.containsKey(language)) {
            return runningServers.get(language);
        }

        String startCmd = START_COMMANDS.get(language);
        if (startCmd == null) return -1;

        int port = nextPort++;
        // Wrap the LSP server with socat to expose it over TCP
        String wrappedCmd = "socat TCP-LISTEN:" + port + ",reuseaddr,fork EXEC:'" +
            startCmd.replace("'", "'\\''") + "' &";

        termuxBridge.executeCommandAsync(wrappedCmd, projectRoot, (output, exitCode) -> {});
        runningServers.put(language, port);
        return port;
    }

    public void stopServer(String language) {
        if (!runningServers.containsKey(language)) return;
        int port = runningServers.get(language);
        termuxBridge.executeCommandAsync(
            "fuser -k " + port + "/tcp 2>/dev/null", null, (o, e) -> {});
        runningServers.remove(language);
    }

    public boolean isServerRunning(String language) {
        return runningServers.containsKey(language);
    }

    public String getInstallCommand(String language) {
        return INSTALL_COMMANDS.getOrDefault(language, null);
    }

    public void stopAllServers() {
        for (String lang : runningServers.keySet()) {
            stopServer(lang);
        }
    }
}
