#!/usr/bin/env bash
set -e

# 终端色彩
GREEN="\033[0;32m"
BLUE="\033[0;34m"
YELLOW="\033[1;33m"
CYAN="\033[0;36m"
RED="\033[0;31m"
NC="\033[0m"

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}    🚀 BiliIntlUnrestrict 本地签名安全发版脚本     ${NC}"
echo -e "${CYAN}====================================================${NC}"

# 1. 环境与登录校验
if ! command -v gh &> /dev/null; then
    echo -e "${RED}❌ 未检测到 GitHub CLI (gh)，请先安装: sudo apt install gh${NC}"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo -e "${RED}❌ gh 尚未登录，请先执行 \x27gh auth login\x27 进行授权！${NC}"
    exit 1
fi

# 2. 检查 Git 状态
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo -e "${BLUE}📌 当前 Git 分支: ${GREEN}${CURRENT_BRANCH}${NC}"

if [ -n "$(git status --porcelain)" ]; then
    echo -e "${YELLOW}⚠️ 工作区存在未提交的修改，建议先提交或暂存后再发版！${NC}"
    read -rp "是否仍要继续？(y/N): " FORCE_CONTINUE
    if [[ ! "$FORCE_CONTINUE" =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}已取消发版。${NC}"
        exit 0
    fi
fi

# 3. 提取默认版本号
DEFAULT_VERSION=$(grep -E "\bversionName\s*=\s*\"[^\"]+\"" app/build.gradle.kts | head -n1 | sed -E "s/.*\"([^\"]+)\".*/\1/")
if [ -z "$DEFAULT_VERSION" ]; then
    DEFAULT_VERSION="1.0.0"
fi
DEFAULT_TAG="v${DEFAULT_VERSION}"

read -rp "$(echo -e "🏷️ 请输入发版 Tag 版本号 [默认: ${GREEN}${DEFAULT_TAG}${NC}]: ")" INPUT_TAG
TAG="${INPUT_TAG:-$DEFAULT_TAG}"

read -rp "$(echo -e "📝 请输入 Release 标题 [默认: ${GREEN}BiliIntl Unrestrict ${TAG}${NC}]: ")" INPUT_TITLE
TITLE="${INPUT_TITLE:-BiliIntl Unrestrict ${TAG}}"

# 4. 编译 Release APK
read -rp "$(echo -e "🔨 是否使用本地密钥编译最新 Release APK？(Y/n): ")" BUILD_CHOICE
BUILD_CHOICE="${BUILD_CHOICE:-Y}"

if [[ "$BUILD_CHOICE" =~ ^[Yy]$ ]]; then
    echo -e "${BLUE}⏳ 正在执行 Gradle Release 混淆压缩编译与签名...${NC}"
    ./gradlew assembleRelease
fi

SOURCE_APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$SOURCE_APK" ]; then
    echo -e "${RED}❌ 未找到编译好的 Release APK: ${SOURCE_APK}${NC}"
    exit 1
fi

TARGET_APK_NAME="BiliIntlUnrestrict-${TAG}.apk"
TARGET_APK="app/build/outputs/apk/release/${TARGET_APK_NAME}"
cp "$SOURCE_APK" "$TARGET_APK"

# 计算文件信息
APK_SIZE=$(du -h "$TARGET_APK" | cut -f1)
APK_SHA256=$(sha256sum "$TARGET_APK" | awk "{print \$1}")
echo -e "${GREEN}✅ 安装包就绪: ${TARGET_APK_NAME} (${APK_SIZE})${NC}"

# 5. 组装版本说明 (Release Notes)
NOTES_FILE=$(mktemp)

# 方案 A: 检测是否存在自定义的 RELEASE_NOTES.md
USE_CUSTOM_NOTES=false
if [ -f "RELEASE_NOTES.md" ] && [ -s "RELEASE_NOTES.md" ]; then
    read -rp "$(echo -e "📄 检测到项目根目录下存在 ${GREEN}RELEASE_NOTES.md${NC}，是否直接使用其内容？(Y/n): ")" USE_MD
    USE_MD="${USE_MD:-Y}"
    if [[ "$USE_MD" =~ ^[Yy]$ ]]; then
        cat RELEASE_NOTES.md > "$NOTES_FILE"
        USE_CUSTOM_NOTES=true
    fi
fi

# 方案 B: 若无自定义文件，从 Git 提交历史自动提取生成
if [ "$USE_CUSTOM_NOTES" = false ]; then
    LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
    
    echo "### 🌟 更新日志" >> "$NOTES_FILE"
    echo "" >> "$NOTES_FILE"
    
    if [ -n "$LAST_TAG" ]; then
        echo "> 对比版本：$LAST_TAG ... $TAG" >> "$NOTES_FILE"
        echo "" >> "$NOTES_FILE"
        GIT_RANGE="${LAST_TAG}..HEAD"
    else
        GIT_RANGE="HEAD~10..HEAD"
    fi
    
    FEATS=$(git log $GIT_RANGE --grep="^feat" --pretty=format:"- %s (%h)" 2>/dev/null || true)
    FIXES=$(git log $GIT_RANGE --grep="^fix" --pretty=format:"- %s (%h)" 2>/dev/null || true)
    OTHERS=$(git log $GIT_RANGE --invert-grep --grep="^feat" --grep="^fix" --pretty=format:"- %s (%h)" 2>/dev/null || true)

    if [ -n "$FEATS" ]; then
        echo "#### 🚀 新增与增强" >> "$NOTES_FILE"
        echo "$FEATS" >> "$NOTES_FILE"
        echo "" >> "$NOTES_FILE"
    fi

    if [ -n "$FIXES" ]; then
        echo "#### 🐛 修复与优化" >> "$NOTES_FILE"
        echo "$FIXES" >> "$NOTES_FILE"
        echo "" >> "$NOTES_FILE"
    fi

    if [ -n "$OTHERS" ] && [ -z "$FEATS" ] && [ -z "$FIXES" ]; then
        echo "#### 📝 变动记录" >> "$NOTES_FILE"
        echo "$OTHERS" >> "$NOTES_FILE"
        echo "" >> "$NOTES_FILE"
    fi
fi

# 追加安装包哈希信息
echo "" >> "$NOTES_FILE"
echo "### 📦 安装包校验" >> "$NOTES_FILE"
echo "| 文件名 | 大小 | SHA-256 校验和 |" >> "$NOTES_FILE"
echo "| :--- | :--- | :--- |" >> "$NOTES_FILE"
echo "| \`${TARGET_APK_NAME}\` | ${APK_SIZE} | \`${APK_SHA256}\` |" >> "$NOTES_FILE"

# 6. 预览与编辑
echo -e "\n${CYAN}------------------- [版本说明预览] -------------------${NC}"
cat "$NOTES_FILE"
echo -e "${CYAN}------------------------------------------------------${NC}\n"

while true; do
    read -rp "$(echo -e "👉 选择操作: [${GREEN}Y${NC}] 确认发布 | [${YELLOW}E${NC}] 唤起编辑器微调 | [${RED}Q${NC}] 取消退出: ")" ACTION
    ACTION="${ACTION:-Y}"
    case "$ACTION" in
        [Yy]*)
            break
            ;;
        [Ee]*)
            EDITOR_CMD="${EDITOR:-nano}"
            if command -v code &> /dev/null && [ "$EDITOR_CMD" = "nano" ]; then
                code --wait "$NOTES_FILE"
            else
                $EDITOR_CMD "$NOTES_FILE"
            fi
            echo -e "\n${CYAN}------------------- [修改后预览] -------------------${NC}"
            cat "$NOTES_FILE"
            echo -e "${CYAN}----------------------------------------------------${NC}\n"
            ;;
        [Qq]*)
            echo -e "${YELLOW}已取消发布。${NC}"
            rm -f "$NOTES_FILE"
            exit 0
            ;;
        *)
            echo "无效选项，请重新选择。"
            ;;
    esac
done

# 7. 打 Tag 并推送 GitHub
echo -e "${BLUE}🏷️ 正在创建与推送 Git Tag: ${TAG}...${NC}"
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    git tag -a "$TAG" -m "$TITLE"
fi
git push origin "$TAG"

# 8. 执行 gh release create
echo -e "${BLUE}☁️ 正在上传 APK 并创建 GitHub Release...${NC}"
gh release create "$TAG" "$TARGET_APK" \
    --title "$TITLE" \
    --notes-file "$NOTES_FILE"

rm -f "$NOTES_FILE"
echo -e "${GREEN}🎉 恭喜！版本 ${TAG} 已成功发布到 GitHub Release！${NC}"
echo -e "网页查看: https://github.com/Cornfy/BiliIntlUnrestrict/releases/tag/${TAG}"
