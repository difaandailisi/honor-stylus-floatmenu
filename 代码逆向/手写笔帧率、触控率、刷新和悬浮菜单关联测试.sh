#!/system/bin/sh
# =============================================================================
#  手写笔帧率、触控率、刷新和悬浮菜单关联测试     v1.0.0
# -----------------------------------------------------------------------------
#  目的：用真机数据判定这四者的关系
#        「悬浮菜单」 「手写笔状态」 「屏幕刷新率」 「触控/笔采样率」
#  做法：A/B 两步只改 Settings 的悬浮球判定（绝不碰蓝牙）
#        C 步单独做一次真实蓝牙重连 —— 把“球”和“笔状态”两个变量彻底分开
#  环境：adb shell / Shizuku(uid 2000) 即可，root 非必需
#  用法：sh "手写笔帧率、触控率、刷新和悬浮菜单关联测试.sh" help
#  产出：/sdcard/Download/stylus_fps_test_<时间戳>.txt（可直接发回分析）
# =============================================================================
VERSION='1.0.0'
OUTDIR=${OUTDIR:-/sdcard/Download}
( mkdir -p "$OUTDIR" 2>/dev/null ) || OUTDIR=/sdcard
REPORT="$OUTDIR/stylus_fps_test_$(date +%Y%m%d_%H%M%S).txt"

K_SWITCH='stylus_support_floating_capsule'
K_CONN='honor_bt_pencil_connection_state'
K_PSTATE='key_bt_pencil_state'
K_ATTACH='key_pencil_attach_state'
K_ATYPE='key_pencil_attach_type'
K_CHARGE='honor_bt_pencil_charging_state'
K_WAKE='honor_stylus_wake_state'
WIN='FloatBallWindowView'

SU=''
if [ "$(id -u 2>/dev/null)" != '0' ] && command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    SU='su'
fi
_s()   { if [ -n "$SU" ]; then su -c "settings $*"; else settings $*; fi; }
gget() { _s get global "$1" 2>/dev/null | tr -d '\r'; }
gput() { _s put global "$1" "$2" >/dev/null 2>&1; }

sec()  { printf '\n===== %s =====\n' "$*" | tee -a "$REPORT"; }
line() { printf '%s\n' "$*" | tee -a "$REPORT"; }
sub()  { sed 's/^/    /' | tee -a "$REPORT"; }
now()  { date '+%F %T'; }

win_count() { dumpsys window windows 2>/dev/null | grep -c "$WIN"; }

fps_now() {
    line "  [刷新率快照 @ $(now)]"
    line '  -- dumpsys display'
    dumpsys display 2>/dev/null | grep -iE 'mActiveMode|activeModeId|refreshRate' | head -12 | sub
    line '  -- dumpsys SurfaceFlinger'
    dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'activeMode|DisplayMode|refreshRate' | head -12 | sub
    line '  -- dumpsys window displays'
    dumpsys window displays 2>/dev/null | grep -iE 'refresh|mode' | head -8 | sub
    line '  [提示] 请同时打开 开发者选项 -> 显示刷新率，肉眼核对右上角数字'
}

modes() {
    line '  [支持的显示模式 / 刷新率（看有没有 120Hz 模式）]'
    dumpsys display 2>/dev/null | grep -iE 'mSupportedModes|SupportedModes|refreshRate' | head -30 | sub
}

keys() {
    for tbl in system global secure; do
        line "  -- settings list $tbl  (grep refresh|fps|frame|rate)"
        _s list "$tbl" 2>/dev/null | grep -iE 'refresh|fps|frame|rate' | sub
    done
    line '  [用途] 若出现 high_refresh_app_list / refresh_rate_app_list 之类，把笔记应用写进去即可稳定 120'
}

pen() {
    line "  [笔状态 @ $(now)]"
    for k in $K_CONN $K_PSTATE $K_ATTACH $K_ATYPE $K_CHARGE $K_WAKE $K_SWITCH; do
        printf '    %-34s= %s\n' "$k" "$(gget "$k")" | tee -a "$REPORT"
    done
    line '  [悬浮球窗口]'
    line "    $WIN 窗口数 = $(win_count)"
    dumpsys window windows 2>/dev/null | grep -A14 "$WIN" | grep -iE 'mViewVisibility|mObscured' | sub
    line '  [系统是否认笔]'
    dumpsys input 2>/dev/null | grep -i 'ExternalStylusPresence' | sub
    line '  [HID 设备类 / VID / PID]'
    dumpsys input 2>/dev/null | grep -iA6 -E 'pencil|stylus' | grep -iE 'Classes|Identifier|UniqueId|Pencil|Stylus' | head -12 | sub
    line '  [蓝牙链路]'
    dumpsys bluetooth_manager 2>/dev/null | grep -iE 'bonded|bond_state|pencil' | head -10 | sub
}

# ---------------------------------------------------------- 只动设置，不碰蓝牙
ball_show() {
    line '  [伪造“悬浮球显示”] 仅写 Settings.Global，不碰蓝牙'
    gput "$K_PSTATE" 2
    gput "$K_ATYPE" 1;  sleep 0.3
    gput "$K_ATTACH" 1; sleep 0.4
    gput "$K_ATYPE" 0;  sleep 0.3
    gput "$K_ATTACH" 0; sleep 0.8
    line "  $WIN 窗口数 = $(win_count)"
}

ball_hide() {
    line '  [伪造“悬浮球隐藏”] 仅写 Settings.Global，不碰蓝牙'
    gput "$K_ATYPE" 1;  sleep 0.3
    gput "$K_ATTACH" 1; sleep 0.8
    line "  $WIN 窗口数 = $(win_count)"
}

devices() {
    line '  [输入设备清单（用来找笔/触控的 /dev/input/eventX）]'
    getevent -pl 2>/dev/null | grep -iE 'add device|name:' | head -60 | sub
}

# ------------------------------------------------------------------ 采样率实测
# 原理：触摸/笔的上报是“等间隔”事件流，测连续事件的时间间隔即可反推采样率
# 采样核心：可被 sample 直接调用，也可被 guide 放到后台跑
sample_core() {
    n=$1; dev=$2
    timeout "$n" getevent -lt "$dev" 2>/dev/null | awk '
        /ABS_MT_POSITION|ABS_X|ABS_Y/ {
            t=""
            for (i=1; i<=3; i++) { if ($i ~ /\]$/) { t=$i; gsub(/[\[\]]/,"",t); t=t+0; break } }
            if (t=="") next
            total++
            if (t==prev) next
            if (prev>0) { d=t-prev; c++; s+=d; if (min==0||d<min) min=d; if (d>max) max=d }
            prev=t
        }
        END {
            if (c>0) {
                printf "    有效上报 = %d 次   (原始 axis 行 = %d -> 每次上报约 %d 个轴)\n", c+1, total, int(total/(c+1))
                printf "    采样率   = %.1f Hz  (平均间隔 %.4f s, 最小 %.4f, 最大 %.4f)\n", c/s, s/c, min, max
            } else {
                print "    未捕获到事件。确认设备号；笔休眠/磁吸时 ts_pen 不上报，可先执行 ball-show 再测。"
            }
        }'
}

sample() {
    n=${1:-8}; dev=$2
    if [ -z "$dev" ]; then
        line '  用法: sample <秒数> /dev/input/eventX'
        line '  候选设备（name 含 pen / stylus / touch / digitizer）：'
        getevent -pl 2>/dev/null | grep -iE 'name:.*(pen|stylus|touch|digitizer)' | sub
        line '  完整清单请执行: sh "<脚本>" devices'
        return 1
    fi
    line "  [采样 $n 秒 @ $dev]  测试期间请用笔在屏幕上连续画线！"
    line '  [注意] 一次上报含多个 axis 且共用同一时间戳，已按时间戳去重。'
    sample_core "$n" "$dev" | tee -a "$REPORT"
}

# ------------------------------------------------- 刷新率后台监视（guide 专用）
# 注意：用“有限次数 + 自然结束”，不要 kill。实测在 adb shell 里 kill 后台子 shell
# 会把整个会话一起杀掉（表现为只输出 Terminated），导致采集结果丢失。
fpsmon_start() {
    FPSLOG="$TMPD/fps_$1.log"
    : > "$FPSLOG"
    n=${2:-10}
    ( i=0
      while [ $i -lt "$n" ]; do
          dumpsys display 2>/dev/null | grep -oE 'renderFrameRate [0-9.]+' | head -1 | awk '{print $2}' >> "$FPSLOG"
          i=$((i + 1))
          sleep 0.4
      done ) &
    FPSMON_PID=$!
}

fpsmon_stop() {
    FPSMON_PID=''
}

fps_sum() {
    awk 'NF>0 && $1+0>0 { n++; s+=$1; if (mx==""||$1+0>mx) mx=$1+0; if (mn==""||$1+0<mn) mn=$1+0; c[$1]+=1 }
         END { if (n>0) { best=0; mode=""; hi=0
                for (k in c) { if (c[k]>best) { best=c[k]; mode=k } if (k+0>hi) hi=k+0 }
                printf "    刷新率  样本 %d 个 | 分布:", n
                for (k in c) printf "  %sHz×%d", k, c[k]
                printf "   | 最高 %.0f | 最常见 %s Hz\n", hi, mode }
              else print "    刷新率  未采到样本" }' "$1"
}

# ------------------------------------------------- 倒计时（前台占用，提示用户）
# 变量名必须加前缀：sh 没有 local，若这里用 i 会覆盖调用方的循环计数器
countdown() {
    _cd_dur=$1; _cd_msg=$2
    _cd_i=$_cd_dur
    while [ $_cd_i -gt 0 ]; do
        printf '\r     %s   >>> %2d 秒 <<<   ' "$_cd_msg" "$_cd_i"
        sleep 1
        _cd_i=$((_cd_i - 1))
    done
    printf '\r     %s   >>> 时间到，停手！        \n' "$_cd_msg"
}

# ------------------------------------------------------------------ 关联实验
exp() {
    sec "关联实验开始 @ $(now)"
    line '  设计：A/B 只写 Settings（不碰蓝牙），用来判断“悬浮球本身”是否影响刷新率；'
    line '        C 步单独做一次真实蓝牙重连，用来判断“笔状态”是否才是真正原因。'
    line '  请全程打开 开发者选项 -> 显示刷新率，肉眼核对右上角数字。'

    sec '基线：什么都不做（笔保持已连接，手不要碰屏幕）'
    fps_now
    pen

    sec '测试 A：只用 Settings 伪造出悬浮球（完全不动蓝牙）'
    ball_show
    sleep 2
    fps_now

    sec '测试 B：再把悬浮球隐藏（仍然不动蓝牙）'
    ball_hide
    sleep 2
    fps_now

    sec '测试 C：只做一次真实蓝牙重连（手动操作）'
    line '  请操作：设置 -> 蓝牙 -> 手写笔 -> “断开连接”，等 3 秒后点“连接”'
    line '  完成后回到本终端按回车继续...'
    read -r REPLY
    sleep 2
    fps_now
    pen

    sec '测试 D：触控 / 笔采样率实测'
    devices
    line "  请挑一个 event 设备号执行： sh \"$0\" sample 8 /dev/input/eventX"

    sec '结论判读方法'
    line '  · A 时刷新率不变、B 时也不变  -> 悬浮球与帧率【无因果】（只是伴生）'
    line '  · C 时刷新率变化             -> 刷新率由【笔连接/唤醒事件】驱动（AGPService stylus 场景）'
    line '  · A 时刷新率就变了           -> 那才说明球相关（与静态分析矛盾，请把报告发回）'
    line "  报告文件: $REPORT"
}

# ---------------------------------------------- 交互式引导（自动倒计时，明确指令）
# 目的：消除“不知道什么时候该动”的误差。脚本主动倒计时，用户跟着做即可。
guide() {
    dev=${1:-/dev/input/event7}
    dur=${2:-8}
    cool=${3:-12}
    TMPD=${TMPD:-/data/local/tmp}

    sec "交互式引导测试 @ $(now)"
    line "  设备: $dev      每阶段: ${dur} 秒      冷却: ${cool} 秒"
    line ''
    line '  ★ 规则：'
    line '    1) 只有屏幕出现「>>> 进行中」时才做动作；'
    line '    2) 出现「时间到，停手」立刻停止，不要多画一笔；'
    line '    3) 「静止」阶段请真的什么都别碰 —— 这是基线的关键。'
    line ''
    line '  五阶段（2x2 对照 + 复测）：'
    line '    阶段1  球隐藏 + 静止      <- 基线'
    line '    阶段2  球隐藏 + 画线'
    line '    阶段3  球显示 + 静止'
    line '    阶段4  球显示 + 画线'
    line '    阶段5  球隐藏 + 静止      <- 复测（★判别关键）'
    line ''
    line '  每阶段前都有冷却期，用来消除“上阶段刚画过线、帧率还没回落”的残留效应。'
    line ''
    line '  判读：'
    line '    · 阶段1 vs 阶段3  -> 球是否抬高静止帧率'
    line '    · 阶段3 vs 阶段5  -> 撤掉球后能否回落（回落=球真有用；不回落=只是残留）'
    line ''
    line '  准备好了按回车开始（然后盯住屏幕上的倒计时）...'
    read -r _

    i=0
    for st in 'hide:still' 'hide:draw' 'show:still' 'show:draw' 'hide:still'; do
        i=$((i + 1))
        ball=${st%%:*}; act=${st##*:}

        if [ "$ball" = 'show' ]; then
            ball_show >/dev/null 2>&1; ballname='显示'
        else
            ball_hide >/dev/null 2>&1; ballname='隐藏'
        fi

        if [ "$act" = 'draw' ]; then
            actname='用笔连续画线'; prep='拿起笔，把手放好'
        else
            actname='完全静止(别碰笔和屏幕)'; prep='放下笔，手离开屏幕'
        fi

        line ''
        sec "阶段 $i / 5  —— 球【$ballname】 + 【$actname】"
        line "  当前悬浮球窗口数 = $(win_count)   （$ballname 应为 $([ "$ball" = show ] && echo 非0 || echo 0)）"
        line "  冷却 ${cool} 秒：让刷新率回落到稳定值，期间别碰屏幕"
        countdown "$cool" '冷却中'
        line "  准备：$prep"
        countdown 3 '准备'
        line "  >>> 开始：$actname <<<"

        fpsmon_start "$i" "$((dur + 4))"
        if [ "$act" = 'draw' ]; then
            sample_core "$dur" "$dev" > "$TMPD/sample_$i.txt" 2>/dev/null &
        fi
        countdown "$dur" '进行中'
        fpsmon_stop

        line '  ------ 本阶段结果 ------'
        fps_sum "$TMPD/fps_$i.log" | tee -a "$REPORT"
        if [ "$act" = 'draw' ]; then
            sleep 1
            sub < "$TMPD/sample_$i.txt" 2>/dev/null
            cat "$TMPD/sample_$i.txt" 2>/dev/null >> "$REPORT"
        fi
        sleep 1
    done

    sec '四阶段汇总'
    line '  把下面四组数字横向对比：'
    for j in 1 2 3 4 5; do
        case $j in
            1) tag='阶段1  球隐藏 + 静止' ;;
            2) tag='阶段2  球隐藏 + 画线' ;;
            3) tag='阶段3  球显示 + 静止' ;;
            4) tag='阶段4  球显示 + 画线' ;;
            5) tag='阶段5  球隐藏 + 静止(复测)' ;;
        esac
        line "  $tag"
        fps_sum "$TMPD/fps_$j.log" 2>/dev/null | tee -a "$REPORT"
        if [ -f "$TMPD/sample_$j.txt" ]; then
            sub < "$TMPD/sample_$j.txt" 2>/dev/null
        fi
    done
    line ''
    line '  ★ 判别：主判据是【阶段3 vs 阶段5】（两者都处于“已画过线”的激活态，'
    line '    唯一差别就是球在不在，是唯一干净的对照）。'
    line '    · 3=120, 5=120  -> 球对静止帧率【无影响】（实测结果）'
    line '    · 3=120, 5=60   -> 球确实把静止帧率抬到 120，且可逆'
    line '    · 3=60,  5=60   -> 球无影响，且帧率未被激活'
    line '  ★ 不要把阶段1 和阶段3 直接对比：阶段1 是“从未画过线”的待机态，'
    line '    阶段3 是“刚画过线”的激活态，两者差的不止是球（激活历史不同），属于混淆变量。'
    line '  ★ 阶段1 的 60Hz 只是待机档；只要一动笔就会上 120Hz，这才是书写时的真实帧率。'
    line '  ★ 采样率对比看 2 vs 4；若两者差距大但“最大间隔”也差很多，说明是你画线节奏不同，非球影响。'
    line "  报告文件: $REPORT"
}

# -------------------------------------------- 悬浮球空闲超时（多久自己消失）
# 现象：球出现后若长时间不用笔，会自行消失。这里把它量化。
idle() {
    max=${1:-420}
    step=${2:-15}
    TMPD=${TMPD:-/data/local/tmp}
    sec "悬浮球空闲超时测试 @ $(now)"
    line "  最长监视 ${max} 秒，每 ${step} 秒查一次。"
    line '  ★ 接下来【什么都别碰】，不要碰笔、碰屏幕，坐着等即可。'
    ball_show >/dev/null 2>&1
    w=$(win_count)
    line "  t=0s  悬浮球窗口数=$w"
    if [ "$w" = 0 ]; then
        line '  球没显示出来，先确认 key_bt_pencil_state 是否为 2/4。'
        return 1
    fi
    t=0
    while [ $t -lt "$max" ]; do
        sleep "$step"
        t=$((t + step))
        w=$(win_count)
        line "  t=${t}s  窗口数=$w"
        if [ "$w" = 0 ]; then
            line "  >>> 球在约 ${t} 秒（约 $((t / 60)) 分钟）后自行消失"
            line "  >>> 说明：球有空闲超时。若要常显，重新触发的间隔必须小于 ${t} 秒。"
            return 0
        fi
    done
    line "  ${max} 秒内球未消失（超时阈值大于 ${max} 秒）"
}

# ---------------------------------------- 持续监视刷新率（边用边测，最关键）
# 用途：在【真实使用的 App】里边写字边测。静止降频是正常省电行为，
#       只有“正在写”的那段刷新率才有意义。
watch() {
    dur=${1:-20}
    TMPD=${TMPD:-/data/local/tmp}
    sec "刷新率持续监视 ${dur} 秒 @ $(now)"
    line '  ★ 先切到要测的 App（比如荣耀笔记）并拿起笔，准备好再开始。'
    line '    「写字中」一开始就要【连续写满全程】，中间停手会被记成静止。'
    line '    静止掉到 60 是正常省电降频；重点看有没有 90 —— 有 90 就说明被限制。'
    line ''
    line '  准备好了按回车（然后有 3 秒就位时间）...'
    read -r _
    countdown 3 '就位'
    fpsmon_start 'watch' "$((dur + 4))"
    countdown "$dur" '写字中'
    fpsmon_stop
    line '  ------ 结果 ------'
    fps_sum "$TMPD/fps_watch.log" | tee -a "$REPORT"
    line '  判读：'
    line '    · 分布里出现 90Hz  -> 该 App 被限制在触摸通道的 90 档（未进高刷名单且笔未唤醒）'
    line '    · 只有 60/120，且写字那几秒集中在 120 -> 正常'
    line '    · 全程都是 60     -> 大概率没真的在写字，重跑一次'
    line "  报告文件: $REPORT"
}

report() {
    sec "完整报告 @ $(now)"
    line '[环境]'
    for p in ro.product.model ro.build.characteristics ro.build.version.release msc.sys.oemName ro.build.magic_api_level ro.build.magic_api_version; do
        printf '    %-34s= %s\n' "$p" "$(getprop "$p")" | tee -a "$REPORT"
    done
    line '[四个相关应用版本]'
    for pkg in com.hihonor.stylus.floatmenu com.hihonor.hnbluetoothpencilmanager com.hihonor.featurelayer.sharedfeature.stylus com.hihonor.globalwrite; do
        v=$(dumpsys package "$pkg" 2>/dev/null | grep -m1 'versionName')
        printf '    %-46s %s\n' "$pkg" "$v" | tee -a "$REPORT"
    done

    sec '1) 刷新率快照';      fps_now
    sec '2) 支持的显示模式';  modes
    sec '3) 刷新率相关设置键'; keys
    sec '4) 笔 / 悬浮球状态';  pen
    sec '5) 输入设备清单';    devices

    sec '报告完成'
    line "文件: $REPORT"
    line '把这个文件发回即可分析（含刷新率三源快照、笔状态、窗口可见性、输入设备清单）'
}

help() {
    cat <<'EOF'
手写笔帧率、触控率、刷新和悬浮菜单关联测试   v1.0.0

用法: sh "手写笔帧率、触控率、刷新和悬浮菜单关联测试.sh" <命令>

  guide [dev][秒][冷却] ★★交互式引导（推荐）：自动倒计时，屏幕提示你何时画线、何时停手
                       五阶段：球隐藏+静止 / 球隐藏+画线 / 球显示+静止 / 球显示+画线
                               / 球隐藏+静止(复测★)
                       默认 dev=/dev/input/event7（honor,ts_pen），秒=8，冷却=12
                       每阶段前有冷却期，消除“刚画完线帧率没回落”的残留效应
  report            ★一键完整报告（推荐先跑，产出可直接发回分析）
  exp               ★关联实验：基线 → A 只伪造球 → B 隐藏球 → C 真实重连 → D 采样率
  fps               刷新率快照（display / SurfaceFlinger / window 三源）
  modes             支持的显示模式（看有没有 120Hz 模式）
  keys              抓 refresh / fps / frame / rate 相关设置键（找 ROM 自己的高刷键）
  pen               笔状态 + 球窗口可见性 + 是否认笔 + HID VID/PID + 蓝牙链路
  watch [秒]           ★持续监视刷新率：切到真实 App（如荣耀笔记）边写字边测
                       默认 20 秒。看“最常见”值：90=被限制，120=正常
  idle [最长秒][步长] 测悬浮球空闲超时：显示球后什么都不碰，看它多久自己消失
                       默认 最长420秒 / 每15秒查一次（用完可 Ctrl+C 中断）
  ball-show         只用 Settings 伪造“悬浮球显示”（不碰蓝牙）
  ball-hide         只用 Settings 伪造“悬浮球隐藏”（不碰蓝牙）
  devices           输入设备清单（找 /dev/input/eventX）
  sample <秒> <dev> 实测触控/笔采样率（getevent 统计事件间隔 → Hz）
  help              本帮助

为什么这么设计：
  · 悬浮球是否显示，只由 Settings.Global 的三个键决定（key_bt_pencil_state、
    key_pencil_attach_state、key_pencil_attach_type），所以可以“只动设置”把球弄出来；
  · 而刷新率由蓝牙包在“笔连接/唤醒”时通过 AGPService 请求 stylus 场景，和球无关；
  · 于是把两件事分开做，就能用数据判定到底是谁在影响帧率。

使用要点：
  · 需要能写 Settings.Global（adb shell / MT 的 Shell(root) / Shizuku 均可）；
  · 【推荐】跑 guide：它会倒计时并明确告诉你何时动笔、何时停手，避免“你动了但我以为没动”的误判；
  · guide / exp 都需要终端能交互（要按回车、要看倒计时），不要在后台静默跑；
  · 【重要】guide 请用电脑 adb 跑。若在手机上的终端 app 里跑，倒计时的每秒刷新本身
    会让屏幕刷新率上升，把“静止基线”污染掉；
  · 静止阶段请真的别碰屏幕 —— 基线一旦被污染，后面全部作废；
  · 报告默认写到 /sdcard/Download/，可用 OUTDIR=路径 覆盖。

PowerShell 提示：整条 shell 参数请用单引号，避免 $( ) 被本地展开。
EOF
}

# ---------------------------------------------------------------------- main
sec "手写笔关联测试 v$VERSION 启动 @ $(now)"
line "报告文件: $REPORT"

case "$1" in
    report|all)  report ;;
    exp)         exp ;;
    fps)         sec '刷新率快照';  fps_now ;;
    modes)       sec '支持的显示模式'; modes ;;
    keys)        sec '刷新率相关设置键'; keys ;;
    pen)         sec '笔 / 悬浮球状态'; pen ;;
    ball-show)   sec '伪造球显示'; ball_show ;;
    ball-hide)   sec '伪造球隐藏'; ball_hide ;;
    devices)     sec '输入设备清单'; devices ;;
    sample)      shift; sample "$@" ;;
    guide)       shift; guide "$@" ;;
    idle)        shift; idle "$@" ;;
    watch)       shift; watch "$@" ;;
    help|-h|--help|'')  help ;;
    *) line "未知命令: $1"; echo ''; help; exit 1 ;;
esac
