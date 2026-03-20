#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# VS Code Android - Termux Setup Script
# Run this once inside Termux to set up the bridge between the app and Termux.
# =============================================================================

set -e

BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; exit 1; }
step() { echo -e "\n${BOLD}==> $1${NC}"; }

echo ""
echo -e "${BOLD}VS Code Android - Termux Bridge Setup${NC}"
echo "======================================"
echo ""

# ── Step 1: Update package lists ─────────────────────────────────────────────
step "Updating package lists..."
pkg update -y || warn "Package update had errors, continuing..."

# ── Step 2: Install required packages ────────────────────────────────────────
step "Installing required packages..."

PACKAGES=(
    "socat"       # Socket bridge for command execution
    "netcat-openbsd" # Fallback socket tool
    "git"         # Version control
    "nodejs"      # JavaScript runtime (for JS/TS language servers)
    "python"      # Python runtime
    "openssh"     # SSH support
    "curl"        # HTTP downloads
    "wget"        # HTTP downloads
    "tar"         # Archive extraction
    "which"       # Path resolution
    "procps"      # Process management (ps, kill)
    "coreutils"   # Basic Unix tools
)

for pkg in "${PACKAGES[@]}"; do
    if pkg list-installed 2>/dev/null | grep -q "^$pkg/"; then
        log "$pkg already installed"
    else
        log "Installing $pkg..."
        pkg install -y "$pkg" || warn "Failed to install $pkg, skipping"
    fi
done

# ── Step 3: Create bridge server script ──────────────────────────────────────
step "Creating bridge server..."

mkdir -p "$HOME/.vscode-android"

# Command execution bridge (port 9999)
# Listens for JSON commands, executes them, returns output
cat > "$HOME/.vscode-android/bridge-server.sh" << 'BRIDGE_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Bridge server - handles command execution requests from VS Code Android app
# Protocol: receives {"type":"exec","cmd":"...","cwd":"..."}\n
#           responds with output lines, terminated by __EXIT__<code>

PORT=${BRIDGE_PORT:-9999}

cleanup() {
    kill $(jobs -p) 2>/dev/null
    exit 0
}
trap cleanup SIGTERM SIGINT

echo "[bridge] Command bridge starting on port $PORT"

socat TCP-LISTEN:$PORT,reuseaddr,fork EXEC:"$HOME/.vscode-android/handle-command.sh" &

wait
BRIDGE_EOF
chmod +x "$HOME/.vscode-android/bridge-server.sh"

# Individual command handler
cat > "$HOME/.vscode-android/handle-command.sh" << 'HANDLER_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Reads one JSON command line, executes it, writes output, writes __EXIT__<code>

read -r line
if [ -z "$line" ]; then exit 1; fi

# Parse JSON fields using basic string manipulation (no jq needed)
CMD=$(echo "$line" | sed 's/.*"cmd":"\([^"]*\)".*/\1/' | sed 's/\\n/\n/g' | sed 's/\\"/"/g')
CWD=$(echo "$line" | sed 's/.*"cwd":"\([^"]*\)".*/\1/')

# Set up environment
export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

# Change to working directory
if [ -n "$CWD" ] && [ -d "$CWD" ]; then
    cd "$CWD"
fi

# Execute command and capture output + exit code
eval "$CMD" 2>&1
echo "__EXIT__$?"
HANDLER_EOF
chmod +x "$HOME/.vscode-android/handle-command.sh"

log "Bridge server created"

# ── Step 4: Create terminal session server ────────────────────────────────────
step "Creating terminal session server..."

# Terminal bridge (port 10000)
# Each connection gets a full interactive bash session
cat > "$HOME/.vscode-android/terminal-server.sh" << 'TERM_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Terminal server - each connection spawns a full interactive bash session

PORT=${TERMINAL_PORT:-10000}

cleanup() {
    kill $(jobs -p) 2>/dev/null
    exit 0
}
trap cleanup SIGTERM SIGINT

echo "[terminal] Terminal server starting on port $PORT"

export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TERM="xterm-256color"
export SHELL="$PREFIX/bin/bash"

socat TCP-LISTEN:$PORT,reuseaddr,fork \
    EXEC:"$PREFIX/bin/bash --login",pty,setsid,echo=0 &

wait
TERM_EOF
chmod +x "$HOME/.vscode-android/terminal-server.sh"

log "Terminal server created"

# ── Step 5: Create autostart script ──────────────────────────────────────────
step "Setting up autostart..."

cat > "$HOME/.vscode-android/start.sh" << 'START_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Start all VS Code Android bridge services
# This is called automatically when the app opens a terminal

LOGDIR="$HOME/.vscode-android/logs"
mkdir -p "$LOGDIR"

start_if_not_running() {
    local name="$1"
    local script="$2"
    local port="$3"

    if ! lsof -i :$port > /dev/null 2>&1; then
        nohup bash "$script" > "$LOGDIR/$name.log" 2>&1 &
        sleep 0.5
        echo "Started $name on port $port (PID $!)"
    else
        echo "$name already running on port $port"
    fi
}

start_if_not_running "bridge" "$HOME/.vscode-android/bridge-server.sh" 9999
start_if_not_running "terminal" "$HOME/.vscode-android/terminal-server.sh" 10000

echo "VS Code Android bridge is running"
START_EOF
chmod +x "$HOME/.vscode-android/start.sh"

log "Autostart script created"

# ── Step 6: Add to .bashrc so it auto-starts ─────────────────────────────────
step "Configuring auto-start on Termux launch..."

BASHRC="$HOME/.bashrc"
MARKER="# vscode-android-bridge"

if ! grep -q "$MARKER" "$BASHRC" 2>/dev/null; then
    cat >> "$BASHRC" << 'BASHRC_EOF'

# vscode-android-bridge
# Auto-start VS Code Android bridge servers
if [ -f "$HOME/.vscode-android/start.sh" ]; then
    bash "$HOME/.vscode-android/start.sh" > /dev/null 2>&1
fi
BASHRC_EOF
    log "Auto-start added to .bashrc"
else
    log "Auto-start already in .bashrc"
fi

# ── Step 7: Start the bridge now ─────────────────────────────────────────────
step "Starting bridge servers..."
bash "$HOME/.vscode-android/start.sh"

# ── Step 8: Verify ────────────────────────────────────────────────────────────
step "Verifying setup..."

sleep 1

if lsof -i :9999 > /dev/null 2>&1; then
    log "Command bridge is running on port 9999"
else
    warn "Command bridge may not be running. Check $HOME/.vscode-android/logs/bridge.log"
fi

if lsof -i :10000 > /dev/null 2>&1; then
    log "Terminal server is running on port 10000"
else
    warn "Terminal server may not be running. Check $HOME/.vscode-android/logs/terminal.log"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}Setup complete!${NC}"
echo ""
echo "You can now go back to VS Code Android and tap 'Setup is done → Continue'."
echo ""
echo "Useful commands:"
echo "  bash ~/.vscode-android/start.sh   # Restart bridge servers"
echo "  cat ~/.vscode-android/logs/*.log  # View logs"
echo "  pkg install <package>             # Install packages normally"
echo ""
