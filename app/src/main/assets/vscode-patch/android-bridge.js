/**
 * android-bridge.js
 * Injected into VS Code Web after page load.
 * Hooks into VS Code's internal APIs to redirect:
 *   - Terminal creation → Termux via AndroidBridge
 *   - File system reads/writes → AndroidBridge.readFile / writeFile
 *   - Process execution → AndroidBridge.executeCommand
 *   - LSP servers → AndroidBridge.startLanguageServer
 */

(function() {
    'use strict';

    if (window.__androidBridgeInstalled) return;
    window.__androidBridgeInstalled = true;

    const bridge = window.AndroidBridge;
    if (!bridge) {
        console.error('[AndroidBridge] AndroidBridge not found. Java bridge not injected.');
        return;
    }

    console.log('[AndroidBridge] Installing VS Code Android patches...');

    // ── Public API used by JavascriptBridge.java callbacks ───────────────────
    window.vscodeAndroidBridge = {

        openFile: function(path) {
            // Tell VS Code to open a file
            if (window.vscode && window.vscode.commands) {
                window.vscode.commands.executeCommand('vscode.open',
                    window.vscode.Uri.file(path));
            }
        },

        setWorkspace: function(path) {
            // Set the workspace folder
            if (window.vscode && window.vscode.commands) {
                window.vscode.commands.executeCommand(
                    'vscode.openFolder',
                    window.vscode.Uri.file(path),
                    false
                );
            }
        },

        // Called by Java when an async process produces output
        processCallback: function(callbackId, output, exitCode) {
            const cb = window.__androidProcessCallbacks && window.__androidProcessCallbacks[callbackId];
            if (cb) {
                cb(output, exitCode);
                delete window.__androidProcessCallbacks[callbackId];
            }
        },

        // Called by Java when terminal produces output (for relay)
        terminalOutput: function(sessionId, data) {
            // Forward to the terminal emulator if needed
        }
    };

    window.__androidProcessCallbacks = {};

    // ── File System Provider ──────────────────────────────────────────────────
    // Registers a custom FileSystemProvider with VS Code that reads/writes
    // files via AndroidBridge instead of the browser sandbox.

    function registerFileSystemProvider() {
        if (!window.vscode) return setTimeout(registerFileSystemProvider, 500);

        const vscode = window.vscode;

        class AndroidFileSystemProvider {
            constructor() {
                this._onDidChangeFile = new vscode.EventEmitter();
                this.onDidChangeFile = this._onDidChangeFile.event;
            }

            watch(uri, options) {
                return { dispose: () => {} };
            }

            stat(uri) {
                try {
                    const result = JSON.parse(bridge.stat(uri.fsPath));
                    return {
                        type: result.type === 'directory'
                            ? vscode.FileType.Directory
                            : vscode.FileType.File,
                        ctime: result.mtime || 0,
                        mtime: result.mtime || 0,
                        size: result.size || 0
                    };
                } catch (e) {
                    throw vscode.FileSystemError.FileNotFound(uri);
                }
            }

            readDirectory(uri) {
                try {
                    const entries = JSON.parse(bridge.readDirectory(uri.fsPath));
                    return entries.map(e => [
                        e.name,
                        e.type === 'directory'
                            ? vscode.FileType.Directory
                            : vscode.FileType.File
                    ]);
                } catch (e) {
                    return [];
                }
            }

            createDirectory(uri) {
                bridge.createDirectory(uri.fsPath);
            }

            readFile(uri) {
                const content = bridge.readFile(uri.fsPath);
                if (content === null || content === undefined) {
                    throw vscode.FileSystemError.FileNotFound(uri);
                }
                return new TextEncoder().encode(content);
            }

            writeFile(uri, content, options) {
                const text = new TextDecoder().decode(content);
                bridge.writeFile(uri.fsPath, text);
                this._onDidChangeFile.fire([{
                    type: vscode.FileChangeType.Changed,
                    uri: uri
                }]);
            }

            delete(uri, options) {
                bridge.deleteFile(uri.fsPath);
            }

            rename(oldUri, newUri, options) {
                bridge.rename(oldUri.fsPath, newUri.fsPath);
            }

            copy(source, destination, options) {
                bridge.copy(source.fsPath, destination.fsPath);
            }
        }

        // Register for the 'file' scheme so VS Code uses our provider
        // for all file:// URIs
        try {
            vscode.workspace.registerFileSystemProvider(
                'file',
                new AndroidFileSystemProvider(),
                { isCaseSensitive: true, isReadonly: false }
            );
            console.log('[AndroidBridge] FileSystemProvider registered');
        } catch (e) {
            console.warn('[AndroidBridge] FileSystemProvider already registered or failed:', e);
        }
    }

    // ── Terminal Hook ─────────────────────────────────────────────────────────
    // Intercepts VS Code's "workbench.action.terminal.new" command and
    // tells the Android app to show the native Termux terminal panel.

    function hookTerminalCreation() {
        if (!window.vscode) return setTimeout(hookTerminalCreation, 500);

        const vscode = window.vscode;

        // Override the terminal creation command
        vscode.commands.registerCommand('workbench.action.terminal.new', function() {
            const workspaceFolder = vscode.workspace.workspaceFolders
                && vscode.workspace.workspaceFolders[0]
                && vscode.workspace.workspaceFolders[0].uri.fsPath;
            bridge.openTerminal(workspaceFolder || '/data/data/com.termux/files/home');
        });

        // Also hook the createTerminal API
        const originalCreateTerminal = vscode.window.createTerminal;
        vscode.window.createTerminal = function(options) {
            const workspaceFolder = vscode.workspace.workspaceFolders
                && vscode.workspace.workspaceFolders[0]
                && vscode.workspace.workspaceFolders[0].uri.fsPath;
            bridge.openTerminal(
                (options && options.cwd) || workspaceFolder || '/data/data/com.termux/files/home'
            );
            // Return a fake terminal object so VS Code doesn't crash
            return {
                name: (options && options.name) || 'bash',
                processId: Promise.resolve(0),
                creationOptions: options || {},
                exitStatus: undefined,
                state: { isInteractedWith: true },
                sendText: function(text, addNewLine) {
                    bridge.sendTerminalInput(text + (addNewLine !== false ? '\n' : ''));
                },
                show: function() { bridge.openTerminal(''); },
                hide: function() { bridge.closeTerminal(); },
                dispose: function() { bridge.closeTerminal(); }
            };
        };

        console.log('[AndroidBridge] Terminal hooks installed');
    }

    // ── Environment patch ─────────────────────────────────────────────────────
    // Tells VS Code about the Android/Termux environment so extensions
    // know where to find binaries like node, python, git, etc.

    function patchEnvironment() {
        if (!window.vscode) return setTimeout(patchEnvironment, 500);

        try {
            const env = JSON.parse(bridge.getEnvironment());
            // Patch process.env for extensions that read it
            if (window.process && window.process.env) {
                window.process.env.HOME = env.home;
                window.process.env.PATH = env.path;
                window.process.env.SHELL = env.shell;
                window.process.env.TERM = 'xterm-256color';
                window.process.env.PREFIX = env.termuxPrefix;
            }
            console.log('[AndroidBridge] Environment patched');
        } catch (e) {
            console.warn('[AndroidBridge] Failed to patch environment:', e);
        }
    }

    // ── Platform detection patch ──────────────────────────────────────────────
    // VS Code checks navigator.platform and process.platform.
    // We tell it we're Linux so Linux-compatible features are enabled.

    function patchPlatform() {
        try {
            Object.defineProperty(navigator, 'platform', {
                get: () => 'Linux aarch64'
            });
            if (window.process) {
                window.process.platform = 'linux';
                window.process.arch = 'arm64';
            }
        } catch (e) {}
    }

    // ── Keyboard shortcut fixes ───────────────────────────────────────────────
    // Some VS Code shortcuts don't work on Android because the keyboard
    // sends different keycodes. This normalizes common shortcuts.

    function patchKeyboard() {
        document.addEventListener('keydown', function(e) {
            // Android "Back" button (keyCode 4) = Escape in VS Code
            if (e.keyCode === 4) {
                const escEvent = new KeyboardEvent('keydown', {
                    key: 'Escape', keyCode: 27, which: 27,
                    bubbles: true, cancelable: true
                });
                document.dispatchEvent(escEvent);
                e.preventDefault();
            }
        }, true);
    }

    // ── Init sequence ─────────────────────────────────────────────────────────

    patchPlatform();
    patchKeyboard();

    // Wait for VS Code's workbench to be ready before hooking APIs
    const waitForVSCode = setInterval(function() {
        if (window.vscode || document.querySelector('.monaco-workbench')) {
            clearInterval(waitForVSCode);
            registerFileSystemProvider();
            hookTerminalCreation();
            patchEnvironment();
            console.log('[AndroidBridge] All patches installed successfully');
        }
    }, 300);

    // Timeout after 30s
    setTimeout(function() { clearInterval(waitForVSCode); }, 30000);

})();
