#!/bin/bash

# ==============================================================================
# SES AI Matcher (Java Spring Boot 3.x + Angular 17+)
# One-Click Launch Script
# ==============================================================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "🚀 Starting SES AI Matcher from: $PROJECT_DIR"

# 1. Detect Java 21
if [ -d "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
fi

if [ -z "$JAVA_HOME" ]; then
    echo "❌ Error: JAVA_HOME is not set and Java 21 was not found automatically."
    exit 1
fi
echo "☕ Using Java: $JAVA_HOME"

# 2. Detect Maven
MVN_BIN=""
if command -v mvn &> /dev/null; then
    MVN_BIN="mvn"
elif [ -f "$HOME/.m2/wrapper/dists/apache-maven-3.9.14/db91789b/bin/mvn" ]; then
    MVN_BIN="$HOME/.m2/wrapper/dists/apache-maven-3.9.14/db91789b/bin/mvn"
else
    echo "❌ Error: Maven executable not found."
    exit 1
fi
echo "📦 Using Maven: $MVN_BIN"

# 3. Check Node & npm
if ! command -v node &> /dev/null || ! command -v npm &> /dev/null; then
    echo "❌ Error: Node.js or npm is not installed."
    exit 1
fi
echo "🟢 Using Node: $(node -v) / npm: $(npm -v)"

# Function to clean up on exit
cleanup() {
    echo ""
    echo "🛑 Shutting down backend and frontend..."
    kill $(jobs -p) 2>/dev/null
    exit
}
trap cleanup SIGINT SIGTERM EXIT

# 4. Free any stale processes on ports 8080 and 4200
lsof -ti:8080,4200 | xargs kill -9 2>/dev/null || true

# 5. Load environment variables from .env if present
if [ -f "$PROJECT_DIR/.env" ]; then
    echo "🔐 Loading environment variables from .env..."
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

# 6. Launch Spring Boot Backend
echo ""
echo "=================================================="
echo "▶️  Launching Spring Boot Backend (Port 8080)..."
echo "=================================================="
cd "$PROJECT_DIR/backend"
$MVN_BIN spring-boot:run &
BACKEND_PID=$!

# 5. Launch Angular Frontend
echo ""
echo "=================================================="
echo "▶️  Launching Angular Frontend (Port 4200)..."
echo "=================================================="
cd "$PROJECT_DIR/frontend"

if [ ! -d "node_modules" ]; then
    echo "📥 Installing frontend dependencies (npm install)..."
    npm install
fi

export NG_CLI_ANALYTICS=false
npx ng serve --port 4200 &
FRONTEND_PID=$!

echo ""
echo "=================================================="
echo "✅ SES AI Matcher is running!"
echo "👉 Angular UI:    http://localhost:4200"
echo "👉 Spring API:    http://localhost:8080/api/matches"
echo "👉 H2 Console:    http://localhost:8080/h2-console"
echo "=================================================="
echo "Press Ctrl+C to stop all servers."

wait
