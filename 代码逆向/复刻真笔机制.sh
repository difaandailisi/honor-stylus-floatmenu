#!/system/bin/sh
# =============================================================================
#  复刻真笔机制     v1.0.0
# -----------------------------------------------------------------------------
#  目的：让任意可用的荣耀笔（如 CD54 / CD54S）表现得像 Magic-Pencil 3
#        —— 连接后：取下自动出悬浮球 / 磁吸自动隐藏 / 断开无球
#
#  原理：真笔的整套表现，本质是【蓝牙包写出的一组 Settings.Global 键值序列】。
#        本脚本用只读改 Settings 的方式，复刻出完全相同的键值变化，
#        从而让 floatmenu 的判定走到与真笔一样的分支。
#
#  ★ 信号源的选择（关键，避免自触发震荡）：
#       监听 honor_bt_pencil_charging_state  （系统写，不参与悬浮球判定）
#       写入 key_pencil_attach_state/_type   （参与判定）
#    这是「监听 A 写 B」的单向结构。绝不可「监听 A 写 A」，否则会无限震荡。
#
#  ★ 必须循环的原因：悬浮球有 45 秒空闲超时（floatmenu 内部定时器），
#    会自己把球收掉。所以轮询间隔必须 < 30 秒（默认 20）。
#
#  ★ 能力边界（重要）：
#    本脚本只能复刻【悬浮球的 UI 行为】。
#    120Hz 由蓝牙包 → AGPService stylus 场景驱动，Settings 完全够不着。
#    · 若笔已被接管（conn=2）→ 写字时系统会自动给 120Hz，不用管
#    · 若笔未被接管        → 请用：设置 → 显示和亮度 → 屏幕刷新率
#                                  → 高刷新率管理 → 加「笔记」
#
#  用法：sh 复刻真笔机制.sh <命令>
# =============================================================================
VERSION='1.0.0'

K_CONN='honor_bt_pencil_connection_state'
K_PSTATE='key_bt_pencil_state'
K_ATTACH='key_pencil_attach_state'
K_ATYPE='key_pencil_attach_type'
K_CHARGE='honor_bt_pencil_charging_state'
K_BATT='honor_bt_pencil_battery_level'
K_MODEL='honor_bt_pencil_model_number'

# 轮询间隔。球有 45 秒空闲超时（floatmenu 内部定时器，改不了），
# 超时后本脚本会在下一轮把它拉回来。间隔越小，"球消失"的窗口越短。
# 权衡：太小会频繁调 dumpsys 增加开销；10 秒时球每次最多消失约 10 秒。
IV=${IV:-10}
INVERT=${INVERT:-0} # 1 = 反相：磁吸显示 / 取下隐藏

SU=''
if [ "$(id -u 2>/dev/null)" != '0' ] && command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    SU='su'
fi
_s()   { if [ -n "$SU" ]; then su -c "settings $*"; else settings $*; fi; }
gget() { _s get global "$1" 2>/dev/null | tr -d '\r'; }
gput() { _s put global "$1" "$2" >/dev/null 2>&1; }
now()  { date '+%H:%M:%S'; }
win()  { dumpsys window windows 2>/dev/null | grep -c 'FloatBallWindowView'; }

# -------------------------------------------------------------------- 诊断
diag() {
    echo '===== 笔是否被蓝牙包接管 ====='
    conn=$(gget "$K_CONN"); batt=$(gget "$K_BATT"); model=$(gget "$K_MODEL")
    printf '  %-30s = %s\n' 'connection_state' "$conn"
    printf '  %-30s = %s\n' 'battery_level'    "$batt"
    printf '  %-30s = %s\n' 'model_number'     "$model"
    printf '  %-30s = %s\n' 'charging_state'   "$(gget "$K_CHARGE")"
    printf '  %-30s = %s\n' 'attach_state'     "$(gget "$K_ATTACH")"
    echo '  ----------------------------------------'
    if [ "$conn" = '2' ] && [ "$batt" != '-1' ] && [ "$batt" != 'null' ] && [ -n "$batt" ]; then
        echo '  ✅ 已被接管：写字时系统会自动给 120Hz'
        echo '     本脚本只需负责把悬浮球维护出来。'
    elif [ "$conn" = '0' ]; then
        echo '  ⚪ 笔未连接（断开态）'
    else
        echo '  ⚠️ 未被接管（conn 非 2，或 battery=-1）'
        echo '     这与 CD52 是同一类问题：GATT 握手失败，adb 修不了。'
        echo '     120Hz 请走：设置 → 屏幕刷新率 → 高刷新率管理 → 加「笔记」'
        echo '     本脚本仍能复刻「悬浮球」的显示/隐藏行为。'
    fi
}

# --------------------------------------------- 显示 / 隐藏（含跳变重触发逻辑）
# ★ 关键：ContentObserver 只在「值发生变化」时回调。若球已被 45 秒超时收掉，
#   再写同一个 attach=0 不会触发任何回调，球永远拉不回来。
#   所以只有「球当前不在、但应该显示」时才做 1->0 跳变强制重触发；
#   球已在时直接保持，避免每轮都闪烁。
do_show() {
    if [ "$(gget "$K_ATTACH")" != '0' ] || [ "$(win)" = '0' ]; then
        gput "$K_ATTACH" 1; sleep 0.2
        gput "$K_ATTACH" 0
    else
        gput "$K_ATTACH" 0
    fi
}

do_hide() {
    if [ "$(gget "$K_ATTACH")" != '1' ]; then
        gput "$K_ATTACH" 0; sleep 0.2
        gput "$K_ATTACH" 1
    else
        gput "$K_ATTACH" 1
    fi
}

# ------------------------------------------------- 按真笔状态机同步一次键值
sync_once() {
    conn=$(gget "$K_CONN")
    chg=$(gget "$K_CHARGE")

    if [ "$conn" = '0' ]; then
        # 断开：真笔会写 pstate=0 -> 不进显示分支
        st='断开(无球)'
        gput "$K_PSTATE" 0

    elif [ "$chg" = '1' ]; then
        # 磁吸：真笔写 pstate=4 / attach=1 -> 球强制隐藏（设计如此）
        if [ "$INVERT" = '1' ]; then
            st='磁吸[反相->显示]'
            gput "$K_PSTATE" 2; gput "$K_ATYPE" 1; do_show
        else
            st='磁吸(隐藏)'
            gput "$K_PSTATE" 4; gput "$K_ATYPE" 1; do_hide
        fi

    else
        # 取下：真笔写 pstate=2 / attach=0 -> 球显示
        if [ "$INVERT" = '1' ]; then
            st='取下[反相->隐藏]'
            gput "$K_PSTATE" 4; gput "$K_ATYPE" 1; do_hide
        else
            st='取下(显示)'
            gput "$K_PSTATE" 2; gput "$K_ATYPE" 1; do_show
        fi
    fi

    printf '  [%s] %-18s pstate=%s attach=%s 球窗口=%s\n' \
        "$(now)" "$st" "$(gget "$K_PSTATE")" "$(gget "$K_ATTACH")" "$(win)"
}

# -------------------------------------------------------------------- 常驻
run() {
    echo "复刻真笔机制 v$VERSION   间隔=${IV}s   反相=${INVERT}"
    diag
    echo ''
    echo '  开始复刻。Ctrl+C 停止。'
    echo '  （每轮都会重写一次目标值，用来对抗悬浮球 45 秒空闲超时）'
    echo ''
    trap 'echo ""; echo "已停止。"; exit 0' INT TERM
    while :; do
        sync_once
        sleep "$IV"
    done
}

help() {
    cat <<'EOF'
复刻真笔机制 —— 让任意可用的荣耀笔表现得像 Magic-Pencil 3

用法: sh 复刻真笔机制.sh <命令>

  run   [间隔秒]    ★常驻复刻（默认 20 秒一轮，必须 <30），Ctrl+C 停止
  once              只同步一次键值（不循环）
  check             诊断：笔有没有被蓝牙包接管
  invert [间隔秒]   反相模式：磁吸显示 / 取下隐藏（与真笔逻辑相反）
  help              本帮助

复刻出来的行为（与真笔一致）：
    笔断开   -> 无悬浮球
    连接·取下 -> 悬浮球显示（并保持，对抗 45 秒超时）
    连接·磁吸 -> 悬浮球隐藏（真笔的设计：充电时不需要球）

能力边界（务必看清）：
  ✅ 能复刻：悬浮球的显示 / 隐藏 / 常显
  ❌ 不能复刻：120Hz。它由蓝牙包 -> AGPService 驱动，Settings 够不着。
     若笔已被接管，写字时系统自动给 120Hz；
     若未被接管，请用：设置 -> 屏幕刷新率 -> 高刷新率管理 -> 加「笔记」

可调环境变量：
  IV=20      轮询间隔（秒），必须 < 30
  INVERT=0   设为 1 开启反相

为什么间隔必须 <30 秒：
  悬浮球有 45 秒空闲超时，是 floatmenu 内部的定时器，与 Settings 无关。
  只能靠周期性重写来持续触发它的 ContentObserver，把球拉回来。
EOF
}

case "$1" in
    run|daemon|start) shift; [ -n "$1" ] && IV="$1"; run ;;
    once)             sync_once ;;
    check|diag|status) diag ;;
    invert)           shift; INVERT=1; [ -n "$1" ] && IV="$1"; run ;;
    help|-h|--help|'') help ;;
    *) echo "未知命令: $1"; echo ''; help; exit 1 ;;
esac
