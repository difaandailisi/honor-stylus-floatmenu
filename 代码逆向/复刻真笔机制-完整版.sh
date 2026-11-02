#!/system/bin/sh
# =============================================================================
#  复刻真笔机制 —— 完整版（激进）   v1.0.0
# -----------------------------------------------------------------------------
#  目标：笔连接后，「磁吸=悬浮菜单关闭 / 取下=悬浮菜单打开」，效果上完全模仿真笔。
#
#  与稳定版的区别：
#    稳定版只做「球显示/隐藏 + 保活」（已验证有效）。
#    完整版在稳定版基础上，额外把【真笔在线时系统会触发的一切】都请求一遍：
#      · 补齐真笔的 Settings 键值指纹（让系统"看起来"像真笔在线）
#      · 多路径请求 120Hz（AOSP 键 / DisplayManager / 荣耀私有键）
#      · 尝试 AGPService stylus 场景（真笔拿 120Hz 的真正通道，需 root）
#      · 尝试拉起蓝牙笔管理服务（促使蓝牙包重走一遍策略）
#
#  ⚠ 声明：下面标注【未验证】的项，成功率未知，可能完全无效。
#    它们都只是写 Settings / 调用系统命令，不会改动任何 APK，
#    可随时用 reset 命令撤销。若你不想要这些尝试，请用稳定版。
#
#  用法：sh 复刻真笔机制-完整版.sh <命令>
# =============================================================================
VERSION='1.0.0'

K_CONN='honor_bt_pencil_connection_state'
K_PSTATE='key_bt_pencil_state'
K_ATTACH='key_pencil_attach_state'
K_ATYPE='key_pencil_attach_type'
K_CHARGE='honor_bt_pencil_charging_state'
K_BATT='honor_bt_pencil_battery_level'
K_MODEL='honor_bt_pencil_model_number'
K_WAKE='honor_stylus_wake_state'
K_SWITCH='stylus_support_floating_capsule'

IV=${IV:-10}
INVERT=${INVERT:-0}
DO_FP=${DO_FP:-1}    # 真笔键值指纹补全
DO_FPS=${DO_FPS:-1}  # 多路径请求 120Hz
DO_AGP=${DO_AGP:-1}  # AGPService stylus 场景（需 root）
DO_BT=${DO_BT:-1}    # 拉起蓝牙笔管理服务

SU=''
if [ "$(id -u 2>/dev/null)" != '0' ] && command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    SU='su'
fi
_s()   { if [ -n "$SU" ]; then su -c "settings $*"; else settings $*; fi; }
gget() { _s get global "$1" 2>/dev/null | tr -d '\r'; }
gput() { _s put global "$1" "$2" >/dev/null 2>&1; }
now()  { date '+%H:%M:%S'; }
win()  { dumpsys window windows 2>/dev/null | grep -c 'FloatBallWindowView'; }
log()  { printf '  [%s] %s\n' "$(now)" "$*"; }

# ------------------------------------------------------------ 显示 / 隐藏
# ContentObserver 只在值变化时回调；球被 45 秒超时收掉后，写同一个值不会有任何反应，
# 必须做 1->0 跳变才能把它拉回来。球已在时则保持，避免每轮闪烁。
do_show() {
    if [ "$(gget "$K_ATTACH")" != '0' ] || [ "$(win)" = '0' ]; then
        gput "$K_ATTACH" 1; sleep 0.2; gput "$K_ATTACH" 0
    else
        gput "$K_ATTACH" 0
    fi
}
do_hide() {
    if [ "$(gget "$K_ATTACH")" != '1' ]; then
        gput "$K_ATTACH" 0; sleep 0.2; gput "$K_ATTACH" 1
    else
        gput "$K_ATTACH" 1
    fi
}

# ------------------------------------------- ① 核心：复刻真笔的球状态机（已验证）
sync_ball() {
    conn=$(gget "$K_CONN"); chg=$(gget "$K_CHARGE")

    if [ "$conn" = '0' ]; then
        st='断开(无球)'; gput "$K_PSTATE" 0
    elif [ "$chg" = '1' ]; then
        if [ "$INVERT" = '1' ]; then
            st='磁吸[反相->显示]'; gput "$K_PSTATE" 2; gput "$K_ATYPE" 1; do_show
        else
            st='磁吸(关闭)';      gput "$K_PSTATE" 4; gput "$K_ATYPE" 1; do_hide
        fi
    else
        if [ "$INVERT" = '1' ]; then
            st='取下[反相->隐藏]'; gput "$K_PSTATE" 4; gput "$K_ATYPE" 1; do_hide
        else
            st='取下(打开)';      gput "$K_PSTATE" 2; gput "$K_ATYPE" 1; do_show
        fi
    fi
    printf '  [%s] %-16s pstate=%s attach=%s 球=%s\n' \
        "$(now)" "$st" "$(gget "$K_PSTATE")" "$(gget "$K_ATTACH")" "$(win)"
}

# --------------------------------- ② 真笔键值指纹：让 Settings 看起来与真笔在线一致
apply_fingerprint() {
    [ "$DO_FP" = '1' ] || return 0
    gput "$K_CONN" 2                 # 已连接（真笔在线值）
    gput "$K_WAKE" 2                 # 已唤醒（真笔在线值）
    gput "$K_SWITCH" 1               # 悬浮菜单总开关
    _s put system stylus_enable 1 >/dev/null 2>&1
    b=$(gget "$K_BATT")
    # 未被接管时电量是 -1，补一个合理值让相关 UI 看起来正常
    if [ "$b" = '-1' ] || [ -z "$b" ] || [ "$b" = 'null' ]; then
        gput "$K_BATT" 80
    fi
    m=$(gget "$K_MODEL")
    if [ -z "$m" ] || [ "$m" = 'null' ]; then
        gput "$K_MODEL" 'HONOR Magic-Pencil 3'
    fi
}

# --------------------------------------------- ③ 多路径请求 120Hz【部分未验证】
request_fps() {
    [ "$DO_FPS" = '1' ] || return 0
    # AOSP 键：荣耀实测不读，但写了无害，万一某个版本认呢
    _s put system min_refresh_rate 120  >/dev/null 2>&1
    _s put system peak_refresh_rate 120 >/dev/null 2>&1
    # DisplayManager：内容帧率匹配偏好【未验证】
    cmd display set-match-content-frame-rate-pref 1 >/dev/null 2>&1
    # 用户首选显示模式：多半因权限失败被忽略【未验证】
    cmd display set-user-preferred-display-mode 2136 3200 120 >/dev/null 2>&1
    # 荣耀可能存在的私有键，名字是猜的；不存在则无副作用【未验证】
    _s put system screen_refresh_rate 120 >/dev/null 2>&1
    _s put system refresh_rate_mode 2     >/dev/null 2>&1
}

# ------------------------------ ④ AGPService stylus 场景：真笔拿 120Hz 的真正通道
# 【未验证】service call 不会自动写 interface token，且需要 root/system，成功率低。
request_stylus_scene() {
    [ "$DO_AGP" = '1' ] || return 0
    if [ -n "$SU" ]; then
        su -c 'service call AGPService 0x7a i32 60' >/dev/null 2>&1
        return $?
    fi
    if [ "$(id -u 2>/dev/null)" = '0' ]; then
        service call AGPService 0x7a i32 60 >/dev/null 2>&1
        return $?
    fi
    return 1
}

# ---------------------------------- ⑤ 尝试拉起蓝牙笔管理服务，促使其重走策略【未验证】
poke_bt() {
    [ "$DO_BT" = '1' ] || return 0
    am start-service -n com.hihonor.hnbluetoothpencilmanager/.MainService >/dev/null 2>&1
}

# -------------------------------------------------------------------- 一次性全量
sync_all() {
    sync_ball
    apply_fingerprint
    request_fps
}

run() {
    echo "复刻真笔机制 完整版 v$VERSION   间隔=${IV}s   反相=${INVERT}"
    echo "  指纹=${DO_FP}  120Hz请求=${DO_FPS}  AGPService=${DO_AGP}  蓝牙拉起=${DO_BT}"
    diag
    echo ''
    echo '  启动：先做一次全量请求，之后循环维护。Ctrl+C 停止。'
    echo ''
    trap 'echo ""; echo "已停止。"; exit 0' INT TERM

    # 首次：把一次性的激进尝试全做一遍
    sync_ball
    apply_fingerprint
    request_fps
    if request_stylus_scene; then log 'AGPService stylus 场景：已下发（root）'
    else log 'AGPService stylus 场景：跳过（无 root，本项注定失败）'; fi
    poke_bt && log '蓝牙笔管理服务：已尝试拉起'

    n=0
    while :; do
        n=$((n + 1))
        sync_ball
        # 指纹与帧率请求不必每轮做，每 6 轮（约 1 分钟）来一次即可
        if [ $((n % 6)) = 0 ]; then
            apply_fingerprint
            request_fps
        fi
        if [ $((n % 30)) = 0 ]; then
            poke_bt
        fi
        sleep "$IV"
    done
}

diag() {
    echo '===== 诊断 ====='
    conn=$(gget "$K_CONN"); batt=$(gget "$K_BATT"); model=$(gget "$K_MODEL")
    printf '  %-28s = %s\n' 'connection_state' "$conn"
    printf '  %-28s = %s\n' 'battery_level'    "$batt"
    printf '  %-28s = %s\n' 'model_number'     "$model"
    printf '  %-28s = %s\n' 'charging_state'   "$(gget "$K_CHARGE")"
    if [ "$conn" = '2' ] && [ "$batt" != '-1' ] && [ -n "$batt" ] && [ "$batt" != 'null' ]; then
        echo '  ✅ 已被接管：120Hz 由系统自动给，脚本只需维护悬浮球'
    elif [ "$conn" = '0' ]; then
        echo '  ⚪ 笔未连接'
    else
        echo '  ⚠️ 未被接管（GATT 握手失败，同 CD52）'
        echo '     120Hz 本脚本基本拿不到，请走：设置 → 屏幕刷新率 → 高刷新率管理 → 加「笔记」'
    fi
}

# --------------------------------------------------------- 撤销本脚本写过的键
reset() {
    echo '撤销本脚本写入的所有 Settings 键...'
    for k in $K_CONN $K_PSTATE $K_ATTACH $K_ATYPE $K_BATT $K_WAKE $K_SWITCH $K_MODEL; do
        _s delete global "$k" >/dev/null 2>&1 && echo "  已删除 global $k"
    done
    for k in min_refresh_rate peak_refresh_rate screen_refresh_rate refresh_rate_mode; do
        _s delete system "$k" >/dev/null 2>&1 && echo "  已删除 system $k"
    done
    echo '完成。（stylus_enable 保留为 1，它本来就是开着的）'
}

help() {
    cat <<'EOF'
复刻真笔机制 —— 完整版（激进）

用法: sh 复刻真笔机制-完整版.sh <命令>

  run    [间隔秒]  ★常驻：磁吸关闭 / 取下打开，并周期性请求全部策略
  once             只同步一次（适合交给 Tasker / Shizuku 定时触发）
  check            诊断笔有没有被蓝牙包接管
  invert [间隔秒]  反相：磁吸显示 / 取下隐藏
  reset            撤销本脚本写过的所有 Settings 键，恢复原状
  help             本帮助

请求了哪些东西（★=未验证，成功率未知）：
  ① 悬浮球状态机          已验证：磁吸 pstate=4+attach=1 关闭；取下 pstate=2+attach=0 打开
  ② 真笔键值指纹          补齐 conn/wake/开关/电量/型号，让系统看起来像真笔在线
  ③ 120Hz 多路径请求      min/peak_refresh_rate ★、set-match-content-frame-rate-pref ★
                          set-user-preferred-display-mode ★、荣耀私有键名（猜的）★
  ④ AGPService stylus     ★ 真笔拿 120Hz 的真正通道，需 root；service call 缺 interface token
  ⑤ 拉起蓝牙笔管理服务    ★ 促使 hnbluetoothpencilmanager 重走一遍策略

开关（设为 0 可关闭对应尝试）：
  DO_FP=1   DO_FPS=1   DO_AGP=1   DO_BT=1     IV=10   INVERT=0

已知拿不到的：
  120Hz。它由【蓝牙包 → AGPService】驱动，Settings 这一层完全够不着。
  若笔未被接管，唯一可靠途径是：设置 → 屏幕刷新率 → 高刷新率管理 → 加「笔记」。
EOF
}

case "$1" in
    run|daemon|start) shift; [ -n "$1" ] && IV="$1"; run ;;
    once)             sync_ball; apply_fingerprint; request_fps ;;
    check|diag|status) diag ;;
    invert)           shift; INVERT=1; [ -n "$1" ] && IV="$1"; run ;;
    reset|clean)      reset ;;
    help|-h|--help|'') help ;;
    *) echo "未知命令: $1"; echo ''; help; exit 1 ;;
esac
