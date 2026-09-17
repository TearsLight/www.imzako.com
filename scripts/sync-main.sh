#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/1panel/www/java/www.imzako.com}"
SERVICE_NAME="${SERVICE_NAME:-www-imzako-com.service}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/health}"
DEPLOYED_FILE="$APP_DIR/.deployed-main"
JAR_PATH="$APP_DIR/target/imzako-0.0.1-SNAPSHOT.jar"
LOCK_FILE="${LOCK_FILE:-/run/lock/www-imzako-sync.lock}"
BUILD_PARENT="${BUILD_PARENT:-/opt/1panel/www/java}"
FORCE=false
export HOME="${HOME:-/root}"

load_node_runtime() {
    if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
        return
    fi

    export NVM_DIR="${NVM_DIR:-/root/.nvm}"
    if [[ -s "$NVM_DIR/nvm.sh" ]]; then
        # systemd does not load root's interactive shell, so initialize NVM here.
        set +u
        # shellcheck source=/dev/null
        source "$NVM_DIR/nvm.sh"
        nvm use --silent default >/dev/null
        set -u
    fi

    if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
        echo "缺少 Node.js/npm；请安装项目 engines 支持的 Node.js 版本。" >&2
        exit 127
    fi
}

if [[ "${1:-}" == "--force" ]]; then
    FORCE=true
elif [[ $# -gt 0 ]]; then
    echo "用法: $0 [--force]" >&2
    exit 64
fi

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "已有 main 同步任务正在执行，本次跳过。"
    exit 0
fi

cd "$APP_DIR"

if [[ ! -d .git ]]; then
    echo "$APP_DIR 不是 Git 工作区。" >&2
    exit 1
fi

branch=$(git symbolic-ref --quiet --short HEAD || true)
if [[ "$branch" != "main" ]]; then
    echo "生产工作区必须位于 main 分支，当前为 ${branch:-detached HEAD}。" >&2
    exit 1
fi

if [[ ! -f .env ]]; then
    echo "缺少生产环境配置 $APP_DIR/.env。" >&2
    exit 1
fi

load_node_runtime

git fetch --prune origin main
remote_sha=$(git rev-parse --verify origin/main)
current_sha=$(git rev-parse --verify HEAD)
deployed_sha=$(cat "$DEPLOYED_FILE" 2>/dev/null || true)

if [[ "$FORCE" != true && "$remote_sha" == "$current_sha" && "$remote_sha" == "$deployed_sha" ]]; then
    echo "main 无更新，当前版本 $remote_sha。"
    exit 0
fi

short_sha=${remote_sha:0:12}
build_dir=$(mktemp -d "$BUILD_PARENT/www-imzako-build.${short_sha}.XXXXXX")

cleanup() {
    git -C "$APP_DIR" worktree remove --force "$build_dir" >/dev/null 2>&1 || rm -rf -- "$build_dir"
    git -C "$APP_DIR" worktree prune >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "构建 main@$remote_sha"
git worktree add --detach "$build_dir" "$remote_sha"
ln -s "$APP_DIR/.env" "$build_dir/.env"

(
    cd "$build_dir"
    npm ci --prefer-offline --no-audit
    bash ./mvnw -B verify
)

built_jar="$build_dir/target/imzako-0.0.1-SNAPSHOT.jar"
if [[ ! -s "$built_jar" ]]; then
    echo "构建完成但没有生成可执行 JAR。" >&2
    exit 1
fi

mkdir -p "$APP_DIR/target"
previous_jar="$APP_DIR/target/imzako-0.0.1-SNAPSHOT.jar.previous"
next_jar="$APP_DIR/target/imzako-0.0.1-SNAPSHOT.jar.next"

if [[ -f "$JAR_PATH" ]]; then
    cp -a "$JAR_PATH" "$previous_jar"
fi
install -m 0644 "$built_jar" "$next_jar"
mv -f "$next_jar" "$JAR_PATH"

git reset --hard "$remote_sha"
git branch --set-upstream-to=origin/main main >/dev/null

if systemctl restart "$SERVICE_NAME"; then
    healthy=false
    for _ in $(seq 1 40); do
        if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
            healthy=true
            break
        fi
        sleep 1
    done
else
    healthy=false
fi

if [[ "$healthy" != true ]]; then
    echo "新版本健康检查失败，恢复上一版本。" >&2
    if [[ -f "$previous_jar" ]]; then
        mv -f "$previous_jar" "$JAR_PATH"
    fi
    git reset --hard "$current_sha"
    systemctl restart "$SERVICE_NAME" || true
    exit 1
fi

printf '%s\n' "$remote_sha" >"$DEPLOYED_FILE"
rm -f "$previous_jar"
echo "main@$remote_sha 已部署，健康检查通过。"
