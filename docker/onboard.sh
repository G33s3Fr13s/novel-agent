#!/bin/bash
# Novel Agent - Setup Wizard (runs inside container)
CONFIG_FILE="/data/config.env"

echo ""
echo "=========================================="
echo "  Novel Agent - Setup Wizard"
echo "=========================================="
echo ""

# ─── LLM Provider ──────────────────────────────────────
echo "--- LLM (Large Language Model) ---"
echo "  Used for: chat, novel continuation, plot analysis"
echo ""
echo "  [1] DeepSeek          (cheapest, good at Chinese)"
echo "  [2] Alibaba Cloud      (Qwen series)"
echo "  [3] SiliconFlow        (multiple open-source models)"
echo "  [4] OpenAI             (GPT series)"
echo "  [5] Zhipu GLM          (GLM series)"
echo "  [6] Custom             (any OpenAI-compatible API)"
echo ""

while true; do
    read -p "Select [1-6]: " LLM_CHOICE
    case "$LLM_CHOICE" in
        1) LLM_BASE_URL="https://api.deepseek.com"
           LLM_MODEL="deepseek-chat"
           LLM_NAME="DeepSeek"; break ;;
        2) LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
           LLM_MODEL="qwen-plus"
           LLM_NAME="Alibaba Cloud"; break ;;
        3) LLM_BASE_URL="https://api.siliconflow.cn/v1"
           LLM_MODEL="deepseek-ai/DeepSeek-V3"
           LLM_NAME="SiliconFlow"; break ;;
        4) LLM_BASE_URL="https://api.openai.com/v1"
           LLM_MODEL="gpt-4o-mini"
           LLM_NAME="OpenAI"; break ;;
        5) LLM_BASE_URL="https://open.bigmodel.cn/api/paas/v4"
           LLM_MODEL="glm-4-flash"
           LLM_NAME="Zhipu GLM"; break ;;
        6) read -p "  Base URL: " LLM_BASE_URL
           read -p "  Model name: " LLM_MODEL
           LLM_NAME="Custom"; break ;;
        *) echo "  Please enter 1-6" ;;
    esac
done

read -p "  $LLM_NAME API Key: " LLM_API_KEY

# ─── Embedding Provider ─────────────────────────────────
echo ""
echo "--- Embedding Provider ---"
echo "  Used for: text vectorization, semantic search"
echo ""
echo "  [1] Same as LLM        (share API key)"
echo "  [2] SiliconFlow BGE-M3  (free quota for new users)"
echo "  [3] Alibaba Cloud        (text-embedding-v4)"
echo "  [4] OpenAI               (text-embedding-3-small)"
echo "  [5] Custom"
echo ""

while true; do
    read -p "Select [1-5]: " EMBED_CHOICE
    case "$EMBED_CHOICE" in
        1) EMBED_BASE_URL="$LLM_BASE_URL"
           EMBED_API_KEY="$LLM_API_KEY"
           EMBED_MODEL="$LLM_MODEL"
           EMBED_NAME="$LLM_NAME (shared)"; break ;;
        2) EMBED_BASE_URL="https://api.siliconflow.cn/v1"
           EMBED_MODEL="BAAI/bge-m3"
           EMBED_NAME="SiliconFlow BGE-M3"
           read -p "  SiliconFlow API Key: " EMBED_API_KEY; break ;;
        3) EMBED_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
           EMBED_MODEL="text-embedding-v4"
           EMBED_NAME="Alibaba Cloud"
           read -p "  Alibaba Cloud API Key: " EMBED_API_KEY; break ;;
        4) EMBED_BASE_URL="https://api.openai.com/v1"
           EMBED_MODEL="text-embedding-3-small"
           EMBED_NAME="OpenAI"
           read -p "  OpenAI API Key: " EMBED_API_KEY; break ;;
        5) read -p "  Base URL: " EMBED_BASE_URL
           read -p "  Model name: " EMBED_MODEL
           read -p "  API Key: " EMBED_API_KEY
           EMBED_NAME="Custom"; break ;;
        *) echo "  Please enter 1-5" ;;
    esac
done

# ─── Write config ───────────────────────────────────────
cat > "$CONFIG_FILE" << ENVEOF
LLM_API_KEY=$LLM_API_KEY
LLM_BASE_URL=$LLM_BASE_URL
LLM_MODEL=$LLM_MODEL
LLM_EMBED_API_KEY=$EMBED_API_KEY
LLM_EMBED_BASE_URL=$EMBED_BASE_URL
LLM_EMBED_MODEL=$EMBED_MODEL
ENVEOF

echo ""
echo "=========================================="
echo "  Setup complete!"
echo ""
echo "  LLM:       $LLM_NAME / $LLM_MODEL"
echo "  Embedding: $EMBED_NAME / $EMBED_MODEL"
echo ""
echo "  Configuration saved to /data/config.env"
echo ""
echo "  To apply: exit and restart the container"
echo "    exit"
echo "    docker restart novel-agent"
echo ""
echo "  Then open: http://localhost:8080"
echo "=========================================="
echo ""
