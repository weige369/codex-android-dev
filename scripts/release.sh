#!/bin/bash
# =============================================================================
# Codex Android — Release 自动化脚本
# =============================================================================
# 功能：
#   1. 版本号自动递增（versionCode + versionName）
#   2. 生成 Debug / Release / Nightly APK
#   3. APK 签名验证
#   4. 生成变更日志
#   5. 可选：ADB 安装到设备
#
# 用法：
#   ./scripts/release.sh              # 构建 Release APK
#   ./scripts/release.sh debug        # 构建 Debug APK
#   ./scripts/release.sh nightly      # 构建 Nightly APK
#   ./scripts/release.sh install      # 构建并安装到设备
#   ./scripts/release.sh bump         # 递增版本号
# =============================================================================

set -euo pipefail

# ---- 配置 ----
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"
OUTPUT_DIR="$PROJECT_DIR/output"
CHANGELOG_FILE="$PROJECT_DIR/CHANGELOG.md"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log()    { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()   { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()  { echo -e "${RED}[ERROR]${NC} $1"; }

# ---- 读取当前版本 ----
read_current_version() {
    local vc=$(grep 'versionCode' "$APP_BUILD_GRADLE" | grep -oP '\d+')
    local vn=$(grep 'versionName' "$APP_BUILD_GRADLE" | grep -oP '"[^"]*"' | tr -d '"')
    echo "$vc|$vn"
}

# ---- 递增版本号 ----
bump_version() {
    local current=$(read_current_version)
    local vc=$(echo "$current" | cut -d'|' -f1)
    local vn=$(echo "$current" | cut -d'|' -f2)
    local new_vc=$((vc + 1))

    # 从 versionName 提取主版本号并递增
    # 格式: X.Y.Z+build
    local base=$(echo "$vn" | sed 's/+.*//')
    local new_vn="${base}+${new_vc}"

    warn "当前: versionCode=$vc, versionName=$vn"
    log  "更新: versionCode=$new_vc, versionName=$new_vn"

    # 使用 sed 更新
    sed -i "s/versionCode = $vc/versionCode = $new_vc/" "$APP_BUILD_GRADLE"
    sed -i "s/versionName = \"$vn\"/versionName = \"$new_vn\"/" "$APP_BUILD_GRADLE"

    log "版本号已更新"
}

# ---- 检查签名配置 ----
check_signing() {
    if [ -f "$PROJECT_DIR/local.properties" ]; then
        if grep -q "RELEASE_STORE_FILE" "$PROJECT_DIR/local.properties"; then
            log "检测到签名配置 (local.properties)"
            return 0
        fi
    fi
    warn "未检测到签名配置，将使用 Debug 签名"
    return 1
}

# ---- 构建 ----
build_apk() {
    local build_type="${1:-release}"
    local task_name

    mkdir -p "$OUTPUT_DIR"

    case "$build_type" in
        debug)    task_name="assembleDebug" ;;
        nightly)  task_name="assembleNightly" ;;
        release)  task_name="assembleRelease" ;;
        *) error "未知构建类型: $build_type"; exit 1 ;;
    esac

    log "开始构建: $task_name"

    cd "$PROJECT_DIR"

    # 检查 Gradle wrapper
    local gradle_cmd
    if [ -f "./gradlew" ]; then
        gradle_cmd="./gradlew"
    elif command -v gradle &>/dev/null; then
        gradle_cmd="gradle"
    else
        error "未找到 Gradle，请确保 gradlew 存在或 gradle 在 PATH 中"
        exit 1
    fi

    $gradle_cmd "$task_name" --no-daemon --stacktrace

    # 复制 APK 到 output 目录
    local apk_src="app/build/outputs/apk/$build_type/codex-android-${build_type}.apk"
    if [ -f "$apk_src" ]; then
        local version=$(read_current_version | cut -d'|' -f2)
        local apk_dst="$OUTPUT_DIR/codex-android-${version}-${build_type}.apk"
        cp "$apk_src" "$apk_dst"
        log "APK 已生成: $apk_dst"
        log "文件大小: $(du -h "$apk_dst" | cut -f1)"
    else
        warn "APK 未在预期位置找到: $apk_src"
        warn "尝试查找..."
        find app/build -name "*.apk" -type f 2>/dev/null | while read -r apk; do
            log "找到 APK: $apk"
            cp "$apk" "$OUTPUT_DIR/"
        done
    fi
}

# ---- APK 签名验证 ----
verify_apk() {
    local apk_path="${1:-}"
    if [ -z "$apk_path" ]; then
        apk_path=$(find "$OUTPUT_DIR" -name "*.apk" -type f | sort -r | head -1)
    fi

    if [ ! -f "$apk_path" ]; then
        error "未找到 APK 文件"
        return 1
    fi

    log "验证 APK 签名: $apk_path"

    if command -v apksigner &>/dev/null; then
        apksigner verify --verbose "$apk_path" 2>&1
    elif command -v keytool &>/dev/null && command -v unzip &>/dev/null; then
        unzip -p "$apk_path" META-INF/*.RSA META-INF/*.DSA 2>/dev/null | keytool -printcert 2>/dev/null || warn "无法提取签名信息"
    else
        warn "apksigner / keytool 不可用，跳过签名验证"
    fi
}

# ---- 安装到设备 ----
install_to_device() {
    local apk_path="${1:-}"
    if [ -z "$apk_path" ]; then
        apk_path=$(find "$OUTPUT_DIR" -name "*-debug.apk" -type f | sort -r | head -1)
    fi

    if [ ! -f "$apk_path" ]; then
        error "未找到 APK 文件，请先构建"
        return 1
    fi

    if ! command -v adb &>/dev/null; then
        error "未找到 adb"
        return 1
    fi

    log "安装到设备: $apk_path"
    adb install -r "$apk_path"

    if [ $? -eq 0 ]; then
        log "安装成功！启动应用: adb shell am start -n com.codex.android/.ui.CodexActivity"
    fi
}

# ---- 变更日志 ----
generate_changelog() {
    local prev_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    local current_version=$(read_current_version | cut -d'|' -f2)

    log "生成变更日志 (v$current_version)"

    {
        echo "## v$current_version ($(date +%Y-%m-%d))"
        echo ""
        if [ -n "$prev_tag" ]; then
            git log "$prev_tag..HEAD" --pretty=format:"- %s (%an)" 2>/dev/null || echo "- 初始版本"
        else
            git log --pretty=format:"- %s (%an)" 2>/dev/null || echo "- 手动构建"
        fi
        echo ""
        echo "---"
        echo ""
    } > "$CHANGELOG_FILE.tmp"

    if [ -f "$CHANGELOG_FILE" ]; then
        cat "$CHANGELOG_FILE" >> "$CHANGELOG_FILE.tmp"
    fi
    mv "$CHANGELOG_FILE.tmp" "$CHANGELOG_FILE"
    log "变更日志已更新: $CHANGELOG_FILE"
}

# ---- 主流程 ----
main() {
    local action="${1:-release}"

    case "$action" in
        bump)
            bump_version
            ;;
        debug|nightly|release)
            check_signing || true
            build_apk "$action"
            verify_apk
            ;;
        install)
            build_apk "debug"
            verify_apk
            install_to_device
            ;;
        verify)
            verify_apk
            ;;
        changelog)
            generate_changelog
            ;;
        all)
            bump_version
            check_signing || true
            build_apk "release"
            verify_apk
            generate_changelog
            ;;
        *)
            echo "用法: $0 {debug|nightly|release|install|bump|verify|changelog|all}"
            echo ""
            echo "  debug      - 构建 Debug APK"
            echo "  nightly    - 构建 Nightly APK"
            echo "  release    - 构建 Release APK"
            echo "  install    - 构建 Debug APK 并安装到设备"
            echo "  bump       - 递增版本号"
            echo "  verify     - 验证 APK 签名"
            echo "  changelog  - 生成变更日志"
            echo "  all        - 执行完整发布流程 (bump + build + verify + changelog)"
            exit 1
            ;;
    esac

    log "完成!"
}

main "$@"