#!/bin/sh
# 确定性 codex 桩（SDLC 验收用）。用法：
#   TIDINGS_CODEX_CLI=design/runtime-evidence/tools/stub-codex.sh <宿主可执行文件> --background
#
# 为什么需要它：本机没有任何可用的真 agent CLI，且四类原因不同（2026-11-02 实测）：
#   codex 0.145.0 —— 未登录（`codex login status` → Not logged in），且
#                    `api.openai.com` 的 TLS 握手被切断（关沙箱、清代理直连仍 000），
#                    exec 会打出会话头 + 5 次 `tls handshake eof` 重试，永远拿不到回复；
#   claude 2.1.212 —— 仅未登录（`oauthAccount=None`，无凭据文件），但 api.anthropic.com
#                    是通的（401），登进去就能跑；
#   agy 1.4.12 —— 上游 502（daily-cloudcode-pa.googleapis.com loadCodeAssist: Bad Gateway）；
#   gemini —— 解释器缺失，exec 直接 ENOENT；
#   opencode 2.2.5 —— 走 gpt-5.4，同一张网，同样卡在 OpenAI 侧。
#
# 要让「派单 → 沙箱 → 产物 → 卡片 → 合流」这条**编排链路**在真机上被验证，
# 需要一个行为确定的执行体。被验的是链路与交互，不是模型能力 ——
# 这一条在证据里写清楚，不拿它冒充「真实 agent 干成了活」。
#
# 分支（按 prompt 里的关键词匹配，顺序有意义）：
#   interactive single-file prototype → 写 prototype.html（阶段 A 原型）
#   Refine the prototype              → 整份重写 prototype.html（阶段 A 微调）
#   Prototype confirmed!              → 写 src/utils/uvIndex.js + 测试（立项转开发）
#   AQI / aqi / 空气质量               → 写 src/aqi.js + 测试（阶段 B）
#   BEIJING / normalizeQuery / city    → 写 cityQuery 回归测试 + 修 normalizeQuery（阶段 C）
#   slow drill                        → sleep 120（§6.1 硬打断，要留打断窗口）
#   failing drill                     → 永不产出补丁（§6.2 三轮自愈交回）
#   quick drill                       → 几秒内产出（§6.4 双工单并发）
#
# 协议：与真 codex `exec --json` 同形（thread.started / item.completed / turn.completed）。
set -u

PROMPT=""
for a in "$@"; do PROMPT="$a"; done

printf '{"type":"thread.started","thread_id":"stub-%s"}\n' "$$"
say() { printf '{"type":"item.completed","item":{"type":"text","message":"%s"}}\n' "$1"; }

case "$PROMPT" in
  *"interactive single-file prototype"*)
    say "stub: drafting a single-file prototype"
    sleep 4
    cat > prototype.html <<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Weather — prototype</title>
<style>
  :root { --bg:#0f1220; --fg:#eef1ff; --muted:#98a0c0; --accent:#7c9cff; }
  * { box-sizing: border-box; }
  body { margin:0; font-family:-apple-system,system-ui,sans-serif; background:var(--bg); color:var(--fg); padding:24px; }
  .card { background:#171b2e; border-radius:16px; padding:20px; margin-bottom:16px; }
  .temp { font-size:64px; font-weight:700; line-height:1; }
  .row { display:flex; gap:12px; align-items:center; }
  .muted { color:var(--muted); font-size:14px; }
  button { background:var(--accent); color:#0b1020; border:0; border-radius:999px; padding:8px 16px; font-weight:600; }
  .days { display:grid; grid-template-columns:repeat(7,1fr); gap:8px; text-align:center; }
  .day { background:#1e2439; border-radius:12px; padding:10px 4px; font-size:12px; }
  .aqi { display:flex; gap:8px; }
  .pill { border-radius:999px; padding:4px 10px; font-size:12px; font-weight:600; }
</style>
</head>
<body>
  <div class="card">
    <div class="row" style="justify-content:space-between">
      <div>
        <div class="muted">北京 · 现在</div>
        <div class="temp" id="temp">18°</div>
      </div>
      <button id="unit">°C / °F</button>
    </div>
  </div>
  <div class="card">
    <div class="muted" style="margin-bottom:10px">未来 7 天</div>
    <div class="days" id="days"></div>
  </div>
  <div class="card">
    <div class="muted" style="margin-bottom:10px">空气质量</div>
    <div class="aqi">
      <span class="pill" style="background:#2ecc71;color:#06210f">PM2.5 32 优</span>
      <span class="pill" style="background:#f1c40f;color:#2b2200">PM10 78 良</span>
    </div>
  </div>
<script>
  const C = [18,19,21,20,17,16,18];
  const ICON = ['晴','多云','阴','小雨','晴','多云','晴'];
  let fahrenheit = false;
  const render = () => {
    const t = fahrenheit ? Math.round(C[0] * 9 / 5 + 32) : C[0];
    document.getElementById('temp').textContent = t + (fahrenheit ? '°F' : '°C');
    document.getElementById('days').innerHTML = C.map((v, i) => {
      const d = fahrenheit ? Math.round(v * 9 / 5 + 32) + '°' : v + '°';
      return `<div class="day"><div>周${'一二三四五六日'[i]}</div><div style="font-size:16px;margin:4px 0">${d}</div><div class="muted">${ICON[i]}</div></div>`;
    }).join('');
  };
  document.getElementById('unit').addEventListener('click', () => { fahrenheit = !fahrenheit; render(); });
  render();
</script>
</body>
</html>
HTML
    say "stub: prototype.html written"
    ;;
  *"Refine the prototype"*)
    say "stub: applying refinement to prototype.html"
    sleep 4
    # 微调：毛玻璃动态渐变背景 + 华氏开关移到右上角。
    # **整份重写**而不是 sed：仓库里的 prototype.html 是上一轮的旧版，sed 的锚点对不上就
    # 什么都不改 ⇒ 空补丁 ⇒ 卡上「已微调」但没有任何产物可看。整份重写让补丁必然非空。
    cat > prototype.html <<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Weather — prototype (glass)</title>
<style>
  :root { --fg:#f4f6ff; --muted:#b6bfe0; --accent:#8fb0ff; }
  * { box-sizing: border-box; }
  body {
    margin:0; padding:24px; min-height:100vh; color:var(--fg);
    font-family:-apple-system,system-ui,sans-serif;
    background:linear-gradient(135deg,#101733 0%,#2a1a4d 45%,#0d2b3f 100%);
    background-size:200% 200%;
    animation:drift 18s ease-in-out infinite;
  }
  @keyframes drift { 0%{background-position:0% 50%} 50%{background-position:100% 50%} 100%{background-position:0% 50%} }
  .card {
    background:rgba(255,255,255,0.08); backdrop-filter:blur(18px) saturate(140%);
    -webkit-backdrop-filter:blur(18px) saturate(140%);
    border:1px solid rgba(255,255,255,0.16); border-radius:18px; padding:20px; margin-bottom:16px;
    position:relative;
  }
  .temp { font-size:64px; font-weight:700; line-height:1; }
  .row { display:flex; gap:12px; align-items:center; }
  .muted { color:var(--muted); font-size:14px; }
  #unit { position:absolute; top:16px; right:16px; background:rgba(255,255,255,0.14); color:var(--fg); border:1px solid rgba(255,255,255,0.22); border-radius:999px; padding:8px 16px; font-weight:600; }
  .days { display:grid; grid-template-columns:repeat(7,1fr); gap:8px; text-align:center; }
  .day { background:rgba(255,255,255,0.07); border-radius:12px; padding:10px 4px; font-size:12px; }
  .aqi { display:flex; gap:8px; }
  .pill { border-radius:999px; padding:4px 10px; font-size:12px; font-weight:600; }
</style>
</head>
<body>
  <div class="card">
    <button id="unit">°F</button>
    <div class="muted">北京 · 现在</div>
    <div class="temp" id="temp">18°C</div>
  </div>
  <div class="card">
    <div class="muted" style="margin-bottom:10px">未来 7 天</div>
    <div class="days" id="days"></div>
  </div>
  <div class="card">
    <div class="muted" style="margin-bottom:10px">空气质量</div>
    <div class="aqi">
      <span class="pill" style="background:#2ecc71;color:#06210f">PM2.5 32 优</span>
      <span class="pill" style="background:#f1c40f;color:#2b2200">PM10 78 良</span>
    </div>
  </div>
<script>
  const C = [18,19,21,20,17,16,18];
  const ICON = ['晴','多云','阴','小雨','晴','多云','晴'];
  let fahrenheit = false;
  const render = () => {
    const t = fahrenheit ? Math.round(C[0] * 9 / 5 + 32) : C[0];
    document.getElementById('temp').textContent = t + (fahrenheit ? '°F' : '°C');
    document.getElementById('unit').textContent = fahrenheit ? '°C' : '°F';
    document.getElementById('days').innerHTML = C.map((v, i) => {
      const d = fahrenheit ? Math.round(v * 9 / 5 + 32) + '°' : v + '°';
      return `<div class="day"><div>周${'一二三四五六日'[i]}</div><div style="font-size:16px;margin:4px 0">${d}</div><div class="muted">${ICON[i]}</div></div>`;
    }).join('');
  };
  document.getElementById('unit').addEventListener('click', () => { fahrenheit = !fahrenheit; render(); });
  render();
</script>
</body>
</html>
HTML
    say "stub: refinement applied (glass gradient + top-right unit toggle)"
    ;;
  # 阶段 A 的「立项转开发」：宿主把 devPrompt 传成
  # `Prototype confirmed! Implement the full features and test suite for: <原话>`。
  # 这里写的是**与阶段 B 的 AQI 不同的**模块 —— 若两阶段都写 src/aqi.js，
  # 第二阶段 `git add` 时发现文件已存在且内容一致 ⇒ 空补丁 ⇒ 卡片「已就绪」但无 diff。
  *"Prototype confirmed!"*)
    say "stub: implementing the approved prototype as real modules plus tests"
    sleep 3
    mkdir -p src/utils test
    cat > src/utils/uvIndex.js <<'JS'
/**
 * 紫外线指数（UV Index）的纯逻辑：分级、配色、防护建议。
 *
 * 与 `src/utils/temperature.js` 同风格 —— 只做映射，不碰 DOM、不读时间，
 * 便于单测；调用方负责把指数喂进来。
 */

export const UV_LEVELS = Object.freeze({
  LOW: 'LOW',
  MODERATE: 'MODERATE',
  HIGH: 'HIGH',
  VERY_HIGH: 'VERY_HIGH',
});

/** WHO 四级分界：0-2 低、3-5 中、6-7 高、8+ 很高。 */
export function levelOf(index) {
  const value = Number(index);
  if (!Number.isFinite(value) || value <= 2) return UV_LEVELS.LOW;
  if (value <= 5) return UV_LEVELS.MODERATE;
  if (value <= 7) return UV_LEVELS.HIGH;
  return UV_LEVELS.VERY_HIGH;
}

/** 等级 → 颜色（与 AQI 面板同一套语义色阶：绿 / 黄 / 橙 / 红）。 */
export function colorOf(index) {
  switch (levelOf(index)) {
    case UV_LEVELS.LOW:
      return '#2ecc71';
    case UV_LEVELS.MODERATE:
      return '#f1c40f';
    case UV_LEVELS.HIGH:
      return '#e67e22';
    default:
      return '#e74c3c';
  }
}

export function labelOf(index) {
  switch (levelOf(index)) {
    case UV_LEVELS.LOW:
      return '低';
    case UV_LEVELS.MODERATE:
      return '中';
    case UV_LEVELS.HIGH:
      return '高';
    default:
      return '很高';
  }
}

/** 一句话防护建议：卡片上只放这一行，不放整段科普。 */
export function adviceOf(index) {
  switch (levelOf(index)) {
    case UV_LEVELS.LOW:
      return '无需特别防护';
    case UV_LEVELS.MODERATE:
      return '建议涂抹防晒';
    case UV_LEVELS.HIGH:
      return '防晒 + 遮阳帽';
    default:
      return '避免正午外出';
  }
}

/** 面板模型：指数 + 等级 + 颜色 + 建议，一次给全。 */
export function uvPanel(index) {
  return {
    value: Number(index) || 0,
    level: levelOf(index),
    color: colorOf(index),
    label: labelOf(index),
    advice: adviceOf(index),
  };
}
JS
    cat > test/uvIndex.test.js <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';

import { UV_LEVELS, adviceOf, colorOf, labelOf, levelOf, uvPanel } from '../src/utils/uvIndex.js';

test('0-2 低、3-5 中、6-7 高、8+ 很高', () => {
  assert.equal(levelOf(0), UV_LEVELS.LOW);
  assert.equal(levelOf(2), UV_LEVELS.LOW);
  assert.equal(levelOf(3), UV_LEVELS.MODERATE);
  assert.equal(levelOf(5), UV_LEVELS.MODERATE);
  assert.equal(levelOf(6), UV_LEVELS.HIGH);
  assert.equal(levelOf(7), UV_LEVELS.HIGH);
  assert.equal(levelOf(8), UV_LEVELS.VERY_HIGH);
  assert.equal(levelOf(11), UV_LEVELS.VERY_HIGH);
});

test('非法输入按「低」处理，不抛错', () => {
  assert.equal(levelOf('abc'), UV_LEVELS.LOW);
  assert.equal(levelOf(-1), UV_LEVELS.LOW);
  assert.equal(levelOf(undefined), UV_LEVELS.LOW);
});

test('四级各有自己的颜色、标签与建议', () => {
  assert.equal(colorOf(1), '#2ecc71');
  assert.equal(colorOf(4), '#f1c40f');
  assert.equal(colorOf(7), '#e67e22');
  assert.equal(colorOf(10), '#e74c3c');
  assert.equal(labelOf(1), '低');
  assert.equal(labelOf(10), '很高');
  assert.equal(adviceOf(1), '无需特别防护');
  assert.equal(adviceOf(10), '避免正午外出');
});

test('面板一次给全指数、等级、颜色与建议', () => {
  const panel = uvPanel(9);
  assert.equal(panel.value, 9);
  assert.equal(panel.level, UV_LEVELS.VERY_HIGH);
  assert.equal(panel.color, '#e74c3c');
  assert.equal(panel.label, '很高');
  assert.equal(panel.advice, '避免正午外出');
});
JS
    say "stub: wrote src/utils/uvIndex.js and test/uvIndex.test.js"
    sleep 1
    if node --test >/tmp/sdlc-full-dev.log 2>&1; then
      say "stub: full suite green ($(grep -E '^# (pass|fail)' /tmp/sdlc-full-dev.log | tr '\n' ' '))"
    else
      say "stub: full suite FAILED"
      exit 1
    fi
    ;;
  *AQI*|*aqi*|*空气质量*)
    say "stub: adding AQI panel module and its tests"
    sleep 3
    mkdir -p src test
    cat > src/aqi.js <<'JS'
/**
 * 空气质量指数（AQI）面板的纯逻辑：分级、配色、两条污染物读数。
 *
 * 只做「给定浓度 → 等级 / 颜色 / 文案」的映射，不碰 DOM，便于单测。
 */

export const AQI_LEVELS = Object.freeze({
  EXCELLENT: 'EXCELLENT',
  GOOD: 'GOOD',
  ALERT: 'ALERT',
});

/** 三级分界：0-50 优、51-100 良、101+ 预警（需求原文）。 */
export function levelOf(index) {
  const value = Number(index);
  if (!Number.isFinite(value) || value < 0) return AQI_LEVELS.EXCELLENT;
  if (value <= 50) return AQI_LEVELS.EXCELLENT;
  if (value <= 100) return AQI_LEVELS.GOOD;
  return AQI_LEVELS.ALERT;
}

/** 等级 → 颜色（与设计稿同一套语义色：绿 / 黄 / 红）。 */
export function colorOf(index) {
  const level = levelOf(index);
  if (level === AQI_LEVELS.EXCELLENT) return '#2ecc71';
  if (level === AQI_LEVELS.GOOD) return '#f1c40f';
  return '#e74c3c';
}

export function labelOf(index) {
  const level = levelOf(index);
  if (level === AQI_LEVELS.EXCELLENT) return '优';
  if (level === AQI_LEVELS.GOOD) return '良';
  return '预警';
}

/** 面板模型：两条污染物 + 取二者较大者作为整体等级。 */
export function aqiPanel({ pm25 = 0, pm10 = 0 } = {}) {
  const overall = Math.max(Number(pm25) || 0, Number(pm10) || 0);
  return {
    level: levelOf(overall),
    color: colorOf(overall),
    label: labelOf(overall),
    rows: [
      { key: 'pm25', name: 'PM2.5', value: Number(pm25) || 0, label: labelOf(pm25), color: colorOf(pm25) },
      { key: 'pm10', name: 'PM10', value: Number(pm10) || 0, label: labelOf(pm10), color: colorOf(pm10) },
    ],
  };
}
JS
    cat > test/aqi.test.js <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';

import { AQI_LEVELS, aqiPanel, colorOf, labelOf, levelOf } from '../src/aqi.js';

test('0-50 是优，51-100 是良，101+ 是预警', () => {
  assert.equal(levelOf(0), AQI_LEVELS.EXCELLENT);
  assert.equal(levelOf(50), AQI_LEVELS.EXCELLENT);
  assert.equal(levelOf(51), AQI_LEVELS.GOOD);
  assert.equal(levelOf(100), AQI_LEVELS.GOOD);
  assert.equal(levelOf(101), AQI_LEVELS.ALERT);
  assert.equal(levelOf(500), AQI_LEVELS.ALERT);
});

test('非法输入按优处理，不抛错', () => {
  assert.equal(levelOf('abc'), AQI_LEVELS.EXCELLENT);
  assert.equal(levelOf(-3), AQI_LEVELS.EXCELLENT);
  assert.equal(levelOf(undefined), AQI_LEVELS.EXCELLENT);
});

test('三级各有自己的颜色与中文标签', () => {
  assert.equal(colorOf(20), '#2ecc71');
  assert.equal(colorOf(80), '#f1c40f');
  assert.equal(colorOf(150), '#e74c3c');
  assert.equal(labelOf(20), '优');
  assert.equal(labelOf(80), '良');
  assert.equal(labelOf(150), '预警');
});

test('面板按两条污染物里较差的一条定整体等级', () => {
  const panel = aqiPanel({ pm25: 32, pm10: 78 });
  assert.equal(panel.level, AQI_LEVELS.GOOD);
  assert.equal(panel.rows.length, 2);
  assert.deepEqual(panel.rows.map((r) => r.name), ['PM2.5', 'PM10']);
  assert.equal(panel.rows[0].label, '优');
  assert.equal(panel.rows[1].label, '良');
});

test('缺字段按 0 处理，面板仍可用', () => {
  const panel = aqiPanel();
  assert.equal(panel.level, AQI_LEVELS.EXCELLENT);
  assert.equal(panel.rows[0].value, 0);
});
JS
    say "stub: wrote src/aqi.js and test/aqi.test.js"
    sleep 2
    if node --test test/aqi.test.js >/tmp/sdlc-aqi-test.log 2>&1; then
      say "stub: new AQI tests pass"
    else
      say "stub: new AQI tests FAILED"
    fi
    ;;
  *BEIJING*|*Beijing*|*beijing*|*normalizeQuery*|*uppercase*|*Uppercase*|*city*|*City*)
    say "stub: reproducing the uppercase city lookup bug"
    sleep 3
    node -e "import('./src/services/weatherService.js').then(async (m) => {
      const svc = new m.WeatherService({ provider: { fetchWeather: async (q) => (q === 'beijing' ? { city: '北京' } : null) } });
      const r = await svc.query('BEIJING');
      console.log('BEIJING ->', r.status);
    })" >/tmp/sdlc-repro.log 2>&1 || true
    say "stub: added regression test for uppercase / padded city query"
    cat > test/cityQuery.test.js <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';

import { WeatherService, WEATHER_STATUS } from '../src/services/weatherService.js';

const provider = {
  fetchWeather: async (q) => (q === 'beijing' ? { city: '北京', temp: 18 } : null),
};

test('全大写拼音能查到（大小写归一化）', async () => {
  const svc = new WeatherService({ provider });
  const res = await svc.query('BEIJING');
  assert.equal(res.status, WEATHER_STATUS.SUCCESS, 'BEIJING 应命中 beijing，实际 ' + res.status);
});

test('前后连续空格与全角空格都不影响命中', async () => {
  const svc = new WeatherService({ provider });
  assert.equal((await svc.query('  beijing  ')).status, WEATHER_STATUS.SUCCESS);
  assert.equal((await svc.query('\u3000Beijing\u3000')).status, WEATHER_STATUS.SUCCESS);
});

test('大小写不同的同一城市共用缓存键', async () => {
  const svc = new WeatherService({ provider });
  await svc.query('BEIJING');
  const again = await svc.query('beijing');
  assert.equal(again.fromCache, true, '归一化后应命中同一缓存键');
});
JS
    # 修复实现：trim + NFKC + 小写
    node -e "
const fs = require('fs');
const p = 'src/services/weatherService.js';
let s = fs.readFileSync(p, 'utf8');
const before = \"function normalizeQuery(cityQuery) {\n  return typeof cityQuery === 'string' ? cityQuery.trim() : '';\n}\";
const after = \"function normalizeQuery(cityQuery) {\n  // 归一化：NFKC（全角空格/全角字母）→ trim → 小写。缺了这三步，'BEIJING' 与\n  // '　beijing　' 都会落到 NOT_FOUND —— 线上报障就是这个。\n  if (typeof cityQuery !== 'string') return '';\n  return cityQuery.normalize('NFKC').trim().toLowerCase();\n}\";
if (!s.includes(before)) { console.error('anchor not found'); process.exit(1); }
fs.writeFileSync(p, s.replace(before, after));
" || say "stub: patch anchor not found"
    say "stub: fixed normalizeQuery (NFKC + trim + lowercase)"
    sleep 2
    if node --test >/tmp/sdlc-full-test.log 2>&1; then
      say "stub: full suite green"
    else
      say "stub: full suite FAILED"
    fi
    ;;
  # §6.1 硬打断演练：跑得足够久，给「停」留出打断窗口。
  *"slow drill"*)
    say "stub: slow drill started (sleeping 120s before writing)"
    sleep 120
    echo "slow" > slow-drill.txt
    say "stub: slow drill finished"
    ;;
  # §6.2 三轮自愈交回演练：永远不产出补丁，让自愈循环跑满 3 轮后退单。
  *"failing drill"*)
    say "stub: failing drill — this round intentionally produces no patch"
    sleep 2
    exit 1
    ;;
  # §6.4 双工单并发演练：几秒内产出，用来和 slow drill 同时存活。
  *"quick drill"*)
    say "stub: quick drill — writing a small note module"
    sleep 2
    mkdir -p src/utils test
    cat > src/utils/note.js <<'JS'
/** 并发演练用的小模块：把一句话整理成一行备注。 */
export function oneLine(text) {
  return String(text ?? '').replace(/\s+/g, ' ').trim();
}
JS
    cat > test/note.test.js <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';

import { oneLine } from '../src/utils/note.js';

test('折叠连续空白为单个空格', () => {
  assert.equal(oneLine('  a \n\t b  '), 'a b');
});

test('非字符串按空串处理', () => {
  assert.equal(oneLine(null), '');
  assert.equal(oneLine(undefined), '');
});
JS
    if node --test >/tmp/sdlc-quick.log 2>&1; then
      say "stub: quick drill done, suite green"
    else
      say "stub: quick drill FAILED"
      exit 1
    fi
    ;;
  *)
    say "stub: nothing to do for this prompt"
    sleep 2
    ;;
esac

printf '{"type":"turn.completed"}\n'
