#!/bin/sh
# 零 token 配置自检：只发 GET <base>/v1/models（纯鉴权+清单，不可能产生生成/embedding 费用），
# 用来在花钱之前确认"两条腿的端点、key、模型名"三者对得上。
#
# 读的是 application.yml 里那组变量名，所以脚本通过 == 应用启动通过（同一份配置事实）：
#   AI_CHAT_BASE_URL / AI_CHAT_API_KEY  → 生成腿（百炼 Token Plan 个人版：sk-sp- key）
#   AI_EMBED_BASE_URL / AI_EMBED_API_KEY → 向量腿（按量 dashscope：普通 sk- key）
#   AI_CHAT_MODEL / AI_EMBED_MODEL / EVAL_JUDGE_* → 模型名
# 任一级缺省就按 application.yml 的回落链走，行为与 Spring 一致。
#
# 用法：sh rag-kb/scripts/provider-selfcheck.sh
#       （用交互 shell 起，才能拿到 ~/.zshrc 里的 key：zsh -il rag-kb/scripts/provider-selfcheck.sh）
# 退出码：0 全部通过；1 有腿认证失败；2 有腿端点 OK 但模型名不在清单里（最容易踩：
#         包月端点没有 qwen-plus，也没有任何 embedding 模型）。
# 本脚本不打印任何 key 的内容，只打印长度与前缀类别。

CHAT_BASE="${AI_CHAT_BASE_URL:-${AI_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode}}"
EMBED_BASE="${AI_EMBED_BASE_URL:-${AI_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode}}"
CHAT_KEY="${AI_CHAT_API_KEY:-$AI_API_KEY}"
EMBED_KEY="${AI_EMBED_API_KEY:-$AI_API_KEY}"
CHAT_MODEL="${AI_CHAT_MODEL:-qwen-plus}"
EMBED_MODEL="${AI_EMBED_MODEL:-text-embedding-v3}"
JUDGE_BASE="${EVAL_JUDGE_BASE_URL:-$CHAT_BASE}"
JUDGE_KEY="${EVAL_JUDGE_API_KEY:-$CHAT_KEY}"
JUDGE_MODEL="${EVAL_JUDGE_MODEL:-}"

rc=0

kind_of() {
  case "$1" in
    sk-sp-*) echo "Token Plan 个人版(sk-sp-)" ;;
    sk-*) echo "普通百炼/按量(sk-)" ;;
    "") echo "空" ;;
    *) echo "非 sk- 前缀" ;;
  esac
}

# Spring AI 的 base-url 是"前缀"，它自己会补 v1/chat/completions；
# 官网文档给 OpenAI SDK 的 base_url 带 /v1 —— 直接抄进 AI_*_BASE_URL 就变成 //v1/v1/... → 400。
# 这里做一次归一，并把踩到的人告警出来。
normalize() {
  base="$1"
  case "$base" in
    */v1) echo "${base%/v1}"; echo "  ⚠️ $2 以 /v1 结尾，已按 Spring AI 约定去掉（正确值：${base%/v1}）" >&2 ;;
    *) echo "$base" ;;
  esac
}

# 一次 GET /models：状态码 + 期望模型是否在清单里
check() {
  label="$1"; base="$2"; key="$3"; model="$4"; mode="${5:-strict}"
  base=$(normalize "$base" "$label")
  if [ -z "$key" ]; then
    printf '%-22s SKIP 未配置 key（%s）\n' "$label" "$base"; return 0
  fi
  body=$(mktemp)
  code=$(curl -s --max-time 15 -o "$body" -w '%{http_code}' "$base/v1/models" \
    -H "Authorization: Bearer $key")
  if [ "$code" != "200" ]; then
    printf '%-22s HTTP %s  端点=%s  key: len=%s %s\n' \
      "$label" "${code:-超时}" "$base" "${#key}" "$(kind_of "$key")"
    rm -f "$body"; rc=1; return 0
  fi
  hit=$(python3 -c "
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    ids = [m.get('id') for m in d.get('data', [])]
except Exception:
    print('unparseable'); raise SystemExit
want = sys.argv[2]
if not want:
    print('模型数=%d' % len(ids)); raise SystemExit
if want in ids:
    print('IN')
else:
    print('MISSING（共 %d 个，例: %s）' % (len(ids), ', '.join([str(i) for i in ids[:8]])))
" "$body" "$model" 2>/dev/null)
  printf '%-22s HTTP 200  模型 %-24s %s\n' "$label" "$model" "${hit:-解析失败}"
  case "$hit" in
    MISSING*|unparseable)
      # advisory 腿不计失败：dashscope 的 /v1/models 不列 embedding 模型
      # （实测 text-embedding-v3 能用却不在清单里），所以那条腿只能证明"鉴权通了"
      if [ "$mode" = strict ] && [ -n "$model" ]; then rc=2; fi
      [ "$mode" = advisory ] && echo "     ↳ 该腿只验证鉴权：/models 清单不含这类模型，命中与否不作判据"
      ;;
  esac
  rm -f "$body"
}

echo "两条腿配置（key 只显示长度与前缀，不显示内容）"
echo "  生成腿: $CHAT_BASE"
echo "  向量腿: $EMBED_BASE"
echo "  裁判腿: $JUDGE_BASE"
echo
check "生成 chat"    "$CHAT_BASE"  "$CHAT_KEY"  "$CHAT_MODEL" strict
check "向量 embedding" "$EMBED_BASE" "$EMBED_KEY" "$EMBED_MODEL" advisory
[ -n "$JUDGE_MODEL" ] && check "评测 judge" "$JUDGE_BASE" "$JUDGE_KEY" "$JUDGE_MODEL" strict
echo
echo "提示：百炼 Token Plan 个人版端点没有 qwen-plus、也没有任何 embedding 模型；"
echo "      若上面出现 MISSING，改 AI_CHAT_MODEL / AI_EMBED_MODEL 或把该腿指回 dashscope。"
exit $rc
