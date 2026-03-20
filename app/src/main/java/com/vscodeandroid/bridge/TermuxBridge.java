package com.vscodeandroid.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TermuxBridge {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_SERVICE = "com.termux.app.TermuxService";
    public static final String TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    public static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final int BRIDGE_PORT = 9999;

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger pidCounter = new AtomicInteger(1000);
    private final Map<Integer, Process> runningProcesses = new HashMap<>();
    private Socket bridgeSocket;
    private PrintWriter bridgeWriter;
    private BufferedReader bridgeReader;

    public TermuxBridge(Context context) {
        this.context = context;
    }

    // ─── Install Check ────────────────────────────────────────────────────────

    public boolean isTermuxInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public boolean isTermuxSetupComplete() {
        SharedPreferences prefs = context.getSharedPreferences("vscode_android", Context.MODE_PRIVATE);
        return prefs.getBoolean("termux_setup_complete", false);
    }

    public void markSetupComplete() {
        context.getSharedPreferences("vscode_android", Context.MODE_PRIVATE)
               .edit().putBoolean("termux_setup_complete", true).apply();
    }

    // ─── Setup Script ─────────────────────────────────────────────────────────

    /**
     * Fires the setup.sh script into Termux via RUN_COMMAND intent.
     * setup.sh installs socat, git, nodejs, etc. and starts the bridge.
     */
    public void runSetupScript() {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
            new String[]{"-c", "curl -fsSL https://raw.githubusercontent.com/your-repo/vscode-android/main/scripts/setup.sh | bash"});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_TERMINAL", true);
        context.startService(intent);
    }

    // ─── Connection Test ──────────────────────────────────────────────────────

    public void testConnection(Consumer<Boolean> onResult, Consumer<Exception> onError) {
        executor.execute(() -> {
            try {
                connectToBridge();
                onResult.accept(true);
            } catch (Exception e) {
                onResult.accept(false);
            }
        });
    }

    private void connectToBridge() throws IOException {
        if (bridgeSocket != null && bridgeSocket.isConnected()) return;
        bridgeSocket = new Socket("localhost", BRIDGE_PORT);
        bridgeSocket.setSoTimeout(5000);
        bridgeWriter = new PrintWriter(bridgeSocket.getOutputStream(), true);
        bridgeReader = new BufferedReader(new InputStreamReader(bridgeSocket.getInputStream()));
    }

    // ─── Synchronous Command Execution ───────────────────────────────────────

    /**
     * Runs a command in Termux via the socket bridge and returns stdout.
     * Blocks the calling thread — always call from a background thread.
     */
    public String executeCommandSync(String command, String workingDir) {
        try {
            connectToBridge();
            String dir = (workingDir != null && !workingDir.isEmpty()) ? workingDir : TERMUX_HOME;
            // Send command as JSON to the bridge server
            String request = "{\"type\":\"exec\",\"cmd\":\"" +
                escapeJson(command) + "\",\"cwd\":\"" + escapeJson(dir) + "\"}\n";
            bridgeWriter.print(request);
            bridgeWriter.flush();

            // Read response lines until we get the exit marker
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = bridgeReader.readLine()) != null) {
                if (line.startsWith("__EXIT__")) break;
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── Async Command Execution ──────────────────────────────────────────────

    public int executeCommandAsync(String command, String workingDir, BiConsumer<String, Integer> callback) {
        int pid = pidCounter.getAndIncrement();
        executor.execute(() -> {
            String output = executeCommandSync(command, workingDir);
            callback.accept(output, 0);
        });
        return pid;
    }

    public void killProcess(int pid) {
        Process p = runningProcesses.get(pid);
        if (p != null) {
            p.destroy();
            runningProcesses.remove(pid);
        }
    }

    // ─── Terminal Session ─────────────────────────────────────────────────────

    /**
     * Opens a persistent bash session via socket for the terminal view.
     * Returns a TerminalSession object the TerminalFragment uses.
     */
    public TerminalSocketSession openTerminalSession(String workingDir) {
        return new TerminalSocketSession(workingDir);
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    public void disconnect() {
        try {
            if (bridgeSocket != null) bridgeSocket.close();
        } catch (IOException ignored) {}
        executor.shutdown();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ─── Inner class: persistent terminal socket session ─────────────────────

    public class TerminalSocketSession {
        private Socket socket;
        private OutputStream outputStream;
        private BufferedReader inputReader;
        private volatile boolean running = false;
        private final String workingDir;

        public TerminalSocketSession(String workingDir) {
            this.workingDir = workingDir;
        }

        public void connect(Consumer<String> onOutput, Runnable onConnected, Consumer<Exception> onError) {
            executor.execute(() -> {
                try {
                    // Each terminal session gets its own dedicated socket connection
                    // The bridge server in Termux spawns a new bash process per connection
                    socket = new Socket("localhost", BRIDGE_PORT + 1); // terminal port = 10000
                    socket.setSoTimeout(0); // no timeout for interactive sessions
                    outputStream = socket.getOutputStream();
                    inputReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Set working directory
                    write("cd '" + workingDir.replace("'", "'\\''") + "' && clear\n");
                    running = true;
                    onConnected.run();

                    // Read output loop
                    char[] buf = new char[4096];
                    int n;
                    while (running && (n = inputReader.read(buf)) != -1) {
                        onOutput.accept(new String(buf, 0, n));
                    }
                } catch (Exception e) {
                    onError.accept(e);
                }
            });
        }

        public void write(String input) {
            executor.execute(() -> {
                try {
                    if (outputStream != null) {
                        outputStream.write(input.getBytes());
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        public void disconnect() {
            running = false;
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {}
        }

        public boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }
}
