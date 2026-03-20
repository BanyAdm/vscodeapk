package com.vscodeandroid.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;

import com.vscodeandroid.filesystem.FileSystemBridge;
import com.vscodeandroid.lsp.LSPBridge;
import com.vscodeandroid.ui.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * This class is exposed to VS Code's JavaScript as `window.AndroidBridge`.
 * Every method annotated with @JavascriptInterface can be called from JS like:
 *   window.AndroidBridge.openTerminal('/home/user/project')
 */
public class JavascriptBridge {

    private final Context context;
    private final MainActivity mainActivity;
    private final FileSystemBridge fileSystemBridge;
    private final TermuxBridge termuxBridge;
    private final LSPBridge lspBridge;

    public JavascriptBridge(Context context, MainActivity mainActivity) {
        this.context = context;
        this.mainActivity = mainActivity;
        this.termuxBridge = new TermuxBridge(context);
        this.fileSystemBridge = new FileSystemBridge(context, termuxBridge);
        this.lspBridge = new LSPBridge(context, termuxBridge);
    }

    // ─── Terminal ────────────────────────────────────────────────────────────

    /**
     * Called when VS Code wants to open a new terminal panel.
     * This is hooked into VS Code's workbench.action.terminal.new command.
     */
    @JavascriptInterface
    public void openTerminal(String workingDirectory) {
        mainActivity.runOnUiThread(() -> {
            mainActivity.showTerminal();
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                mainActivity.terminalFragment.setWorkingDirectory(workingDirectory);
            }
        });
    }

    @JavascriptInterface
    public void closeTerminal() {
        mainActivity.runOnUiThread(mainActivity::hideTerminal);
    }

    @JavascriptInterface
    public void sendTerminalInput(String input) {
        mainActivity.terminalFragment.sendInput(input);
    }

    // ─── File System ─────────────────────────────────────────────────────────

    /**
     * Read a file. Returns file contents as a string.
     * VS Code calls this via the custom FileSystemProvider registered in the JS patch.
     */
    @JavascriptInterface
    public String readFile(String path) {
        return fileSystemBridge.readFile(path);
    }

    @JavascriptInterface
    public void writeFile(String path, String content) {
        fileSystemBridge.writeFile(path, content);
    }

    @JavascriptInterface
    public void deleteFile(String path) {
        fileSystemBridge.deleteFile(path);
    }

    @JavascriptInterface
    public void createDirectory(String path) {
        fileSystemBridge.createDirectory(path);
    }

    @JavascriptInterface
    public String readDirectory(String path) {
        // Returns JSON array of {name, type} objects
        return fileSystemBridge.readDirectory(path);
    }

    @JavascriptInterface
    public boolean fileExists(String path) {
        return fileSystemBridge.fileExists(path);
    }

    @JavascriptInterface
    public String stat(String path) {
        // Returns JSON with {size, mtime, type}
        return fileSystemBridge.stat(path);
    }

    @JavascriptInterface
    public void rename(String oldPath, String newPath) {
        fileSystemBridge.rename(oldPath, newPath);
    }

    @JavascriptInterface
    public void copy(String source, String destination) {
        fileSystemBridge.copy(source, destination);
    }

    // ─── Process Execution ───────────────────────────────────────────────────

    /**
     * Run a shell command via Termux and return output.
     * Used by extensions and tasks.
     */
    @JavascriptInterface
    public String executeCommand(String command, String workingDir) {
        return termuxBridge.executeCommandSync(command, workingDir);
    }

    @JavascriptInterface
    public int executeCommandAsync(String command, String workingDir, String callbackId) {
        // Returns a process ID, output sent back via JS callback
        return termuxBridge.executeCommandAsync(command, workingDir, (output, exitCode) -> {
            String escaped = output.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            String js = "window.vscodeAndroidBridge.processCallback('" + callbackId +
                        "', '" + escaped + "', " + exitCode + ");";
            mainActivity.runOnUiThread(() ->
                mainActivity.binding.vscodeWebview.evaluateJavascript(js, null));
        });
    }

    @JavascriptInterface
    public void killProcess(int pid) {
        termuxBridge.killProcess(pid);
    }

    // ─── Language Server Protocol ─────────────────────────────────────────────

    @JavascriptInterface
    public int startLanguageServer(String language, String projectRoot) {
        // Starts the LSP server in Termux, returns the port it's running on
        return lspBridge.startServer(language, projectRoot);
    }

    @JavascriptInterface
    public void stopLanguageServer(String language) {
        lspBridge.stopServer(language);
    }

    // ─── Git ─────────────────────────────────────────────────────────────────

    @JavascriptInterface
    public String gitStatus(String repoPath) {
        return termuxBridge.executeCommandSync("git status --porcelain", repoPath);
    }

    @JavascriptInterface
    public String gitLog(String repoPath, int limit) {
        return termuxBridge.executeCommandSync(
            "git log --oneline -" + limit, repoPath);
    }

    @JavascriptInterface
    public String gitDiff(String repoPath, String filePath) {
        return termuxBridge.executeCommandSync(
            "git diff -- '" + filePath + "'", repoPath);
    }

    // ─── Environment ─────────────────────────────────────────────────────────

    @JavascriptInterface
    public String getEnvironment() {
        try {
            JSONObject env = new JSONObject();
            env.put("home", "/data/data/com.termux/files/home");
            env.put("termuxPrefix", "/data/data/com.termux/files/usr");
            env.put("shell", "/data/data/com.termux/files/usr/bin/bash");
            env.put("path", "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets");
            env.put("platform", "android");
            return env.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String getInstalledPackages() {
        // Returns list of packages installed in Termux
        return termuxBridge.executeCommandSync("pkg list-installed 2>/dev/null", null);
    }

    // ─── File System Intercept ────────────────────────────────────────────────

    /**
     * Called by VSCodeWebView.shouldInterceptRequest for custom filesystem URIs
     */
    public WebResourceResponse handleFileSystemRequest(String url) {
        try {
            String path = url.replace("vscode-android-fs://", "/");
            String content = fileSystemBridge.readFile(path);
            return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                null, new ByteArrayInputStream(new byte[0]));
        }
    }
}
