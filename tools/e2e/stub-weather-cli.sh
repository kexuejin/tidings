#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# E2E 天气场景的**受控 CLI 替身**（不是产品代码，只被
# `agent_dispatch_e2e.py --scenario weather --worker stub` 用）。
#
# 与通用替身 stub_cli.sh 的差别：通用替身只改一行 README 证明「有 diff 可割」，
# 这个替身写的是**真实的天气代码**——一个实现 WeatherProvider.fetchWeather 契约、
# 调 Open-Meteo 免 key 接口的 provider，加一套注入 fetch 的确定性测试，并把 app.js
# 的注入从 LocalFixtureWeatherProvider 换成新 provider。这样「接入真实天气 API」
# 这条链有真实代码产物 + 测试绿可断言，而不是嘴上说完成。
#
# 被验的仍是宿主编排链（身份 → 执行档 → 授权 → 项目目录 → 工单 → 卡片 → 产物收割）；
# 外部 CLI 只是链尾一格。替身把这一格钉成确定行为：产物必然非空、测试必然可跑。
# 唯一联网点是**显式的 live 探针**（--offline 可关），它单独留证，不混进编排链判据。
#
# 按 codex 的位置参数形态被调用（codex exec --json --sandbox <mode> <prompt>），
# 只认最后一个参数。命中 "Open-Meteo" 关键词才写天气代码；"probe" 只回声。
set -u

PROMPT=""
for a in "$@"; do PROMPT="$a"; done

printf '{"type":"agent_message","text":"weather stub: %s"}\n' "$(printf '%s' "$PROMPT" | tr '\n\r\t' '   ' | cut -c1-72 | sed 's/\\/\\\\/g; s/"/\\"/g')"

case "$PROMPT" in
  *probe*)
    exit 0
    ;;
  *Open-Meteo*|*open-meteo*|*OPEN-METEO*)
    ;;
  *)
    printf '{"type":"agent_message","text":"weather stub: nothing to do (no Open-Meteo keyword)"}\n'
    sleep 2
    exit 0
    ;;
esac

mkdir -p src/providers test

# 基座文件自包含：真机派单时 CLI 的 cwd 是空沙箱快照目录（产物 harvest 后不保留项目树），
# 不像本机验证那样在 weather-relay 完整副本里跑。provider 与测试都 import ./weatherProvider.js，
# 所以先从源工程把基座拷进来；拷不到就当场钉失败，绝不用空文件冒充。
# 源工程位置优先取 env（脚本侧会注入），回落到本机固定路径。
WEATHER_SOURCE="${TENON_WEATHER_SOURCE:-$HOME/weather-relay}"
if [ ! -f src/providers/weatherProvider.js ]; then
  if [ -d "$WEATHER_SOURCE/src" ]; then
    # 整棵 src 拷进来（provider 会 import fixtures/weatherData.js 等同伴文件，逐个追依赖必漏）。
    cp -R "$WEATHER_SOURCE/src/." src/
  else
    printf 'E2E_EVIDENCE base_missing=1\n'
    printf '{"type":"agent_message","text":"weather stub: base src/ not found at %s"}\n' "$WEATHER_SOURCE"
    exit 0
  fi
fi

cat > src/providers/openMeteoProvider.js <<'JS'
/**
 * Open-Meteo 实时 provider：实现 WeatherProvider.fetchWeather(cityQuery) 契约。
 *
 * 免 key、纯 HTTP GET（非商业 1 万次/天）。城市名先用内置四城坐标表解析，
 * 命中不了再走 Open-Meteo geocoding；拿到的 current/daily 映射成
 * buildWeatherResult 认识的形状，所以渲染与业务层一行都不用改。
 *
 * fetch 可注入 —— 测试用假响应，无需联网；live 探针才走真实 fetch。
 */
import {
  WeatherProvider,
  buildWeatherResult,
  normalizeCityQuery,
  PROVIDER_ERROR_MESSAGE,
} from './weatherProvider.js';

const FORECAST_URL = 'https://api.open-meteo.com/v1/forecast';
const GEOCODE_URL = 'https://geocoding-api.open-meteo.com/v1/search';

const CITY_COORDS = Object.freeze({
  beijing: { lat: 39.9042, lon: 116.4074, name: '北京', id: 'beijing' },
  shanghai: { lat: 31.2304, lon: 121.4737, name: '上海', id: 'shanghai' },
  guangzhou: { lat: 23.1291, lon: 113.2644, name: '广州', id: 'guangzhou' },
  shenzhen: { lat: 22.5431, lon: 114.0579, name: '深圳', id: 'shenzhen' },
  // normalizeCityQuery 保留中文（只去空白/NFKC/小写），所以中文名也要有键。
  北京: { lat: 39.9042, lon: 116.4074, name: '北京', id: 'beijing' },
  上海: { lat: 31.2304, lon: 121.4737, name: '上海', id: 'shanghai' },
  广州: { lat: 23.1291, lon: 113.2644, name: '广州', id: 'guangzhou' },
  深圳: { lat: 22.5431, lon: 114.0579, name: '深圳', id: 'shenzhen' },
});

const WMO = Object.freeze({
  0: ['晴', '☀'], 1: ['晴间多云', '🌤'], 2: ['多云', '⛅'], 3: ['阴', '☁'],
  45: ['雾', '🌫'], 48: ['雾凇', '🌫'], 51: ['毛毛雨', '🌦'], 53: ['毛毛雨', '🌦'],
  55: ['毛毛雨', '🌦'], 61: ['小雨', '🌧'], 63: ['中雨', '🌧'], 65: ['大雨', '🌧'],
  71: ['小雪', '🌨'], 73: ['中雪', '🌨'], 75: ['大雪', '🌨'], 80: ['阵雨', '🌦'],
  81: ['阵雨', '🌦'], 82: ['强阵雨', '⛈'], 95: ['雷阵雨', '⛈'], 96: ['雷暴', '⛈'], 99: ['雷暴', '⛈'],
});

export function wmoToCondition(code) {
  const hit = WMO[Number(code)];
  return hit ? { condition: hit[0], icon: hit[1] } : { condition: '未知', icon: '🌡' };
}

export function windDirectionLabel(deg) {
  const d = Number(deg);
  if (!Number.isFinite(d)) return '';
  const names = ['北', '东北', '东', '东南', '南', '西南', '西', '西北'];
  return names[Math.round((((d % 360) + 360) % 360) / 45) % 8];
}

function toFinite(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

export class OpenMeteoProvider extends WeatherProvider {
  constructor({ fetchImpl = globalThis.fetch, coords = CITY_COORDS } = {}) {
    super();
    this.fetchImpl = fetchImpl;
    this.coords = coords;
  }

  async resolveCity(cityQuery) {
    const key = normalizeCityQuery(cityQuery);
    if (!key) return null;
    if (this.coords[key]) return { ...this.coords[key], query: String(cityQuery).trim() };
    const res = await this.fetchImpl(`${GEOCODE_URL}?name=${encodeURIComponent(key)}&count=1&language=zh&format=json`);
    if (!res.ok) return null;
    const body = await res.json();
    const hit = (body.results || [])[0];
    if (!hit) return null;
    return { lat: hit.latitude, lon: hit.longitude, name: hit.name, id: `geo:${hit.id}`, query: String(cityQuery).trim() };
  }

  async fetchWeather(cityQuery, { now = new Date() } = {}) {
    const city = await this.resolveCity(cityQuery);
    if (!city) return null;
    const url = `${FORECAST_URL}?latitude=${city.lat}&longitude=${city.lon}`
      + '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m'
      + '&daily=weather_code,temperature_2m_max,temperature_2m_min'
      + '&timezone=auto&forecast_days=8';
    let res;
    try {
      res = await this.fetchImpl(url);
    } catch {
      throw new Error(PROVIDER_ERROR_MESSAGE);
    }
    if (!res.ok) throw new Error(PROVIDER_ERROR_MESSAGE);
    const body = await res.json();
    const cur = body.current || {};
    const { condition, icon } = wmoToCondition(cur.weather_code);
    const times = (body.daily || {}).time || [];
    const highs = (body.daily || {}).temperature_2m_max || [];
    const lows = (body.daily || {}).temperature_2m_min || [];
    const codes = (body.daily || {}).weather_code || [];
    const speedMps = toFinite(cur.wind_speed_10m);
    const wind = speedMps === null ? null : {
      speedMps: +(speedMps / 3.6).toFixed(1),
      direction: windDirectionLabel(cur.wind_direction_10m),
      level: speedMps < 1 ? 0 : Math.min(12, Math.floor(Math.pow(speedMps / 0.836, 1.5))),
    };
    return buildWeatherResult({
      id: city.id,
      name: city.name,
      displayName: city.name,
      current: {
        tempC: toFinite(cur.temperature_2m),
        feelsC: toFinite(cur.apparent_temperature),
        humidity: toFinite(cur.relative_humidity_2m),
        condition,
        icon,
        wind,
        observedAt: cur.time || '',
        updateTime: '实时 · Open-Meteo',
      },
      forecast: times.map((date, i) => ({
        date,
        dayOffset: i,
        lowC: toFinite(lows[i]),
        highC: toFinite(highs[i]),
        condition: wmoToCondition(codes[i]).condition,
        icon: wmoToCondition(codes[i]).icon,
      })),
    }, { now, query: city.query });
  }
}
JS

cat > test/openMeteoProvider.test.js <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';

import { OpenMeteoProvider, wmoToCondition, windDirectionLabel } from '../src/providers/openMeteoProvider.js';
import { PROVIDER_ERROR_MESSAGE } from '../src/providers/weatherProvider.js';

const FIXED_NOW = new Date(2026, 0, 1, 12, 0, 0);

const FORECAST_BODY = {
  current: {
    time: '2026-01-01T12:00', temperature_2m: 16.1, apparent_temperature: 15.0,
    relative_humidity_2m: 68, weather_code: 2, wind_speed_10m: 6.3, wind_direction_10m: 192,
  },
  daily: {
    time: ['2026-01-01', '2026-01-02'], weather_code: [2, 61],
    temperature_2m_max: [18, 14], temperature_2m_min: [11, 9],
  },
};

function fakeFetch({ ok = true, body = FORECAST_BODY } = {}) {
  let calls = 0;
  const fn = async () => {
    calls += 1;
    return { ok, json: async () => body };
  };
  fn.calls = () => calls;
  return fn;
}

test('内置城市表解析：北京命中坐标，无需 geocoding', async () => {
  const fetchImpl = fakeFetch();
  const provider = new OpenMeteoProvider({ fetchImpl });
  const result = await provider.fetchWeather('北京', { now: FIXED_NOW });
  assert.equal(fetchImpl.calls(), 1, '只请求 forecast，不请求 geocoding');
  assert.equal(result.name, '北京');
  assert.equal(result.tempC, 16.1);
  assert.equal(result.feelsC, 15);
  assert.equal(result.humidity, 68);
  assert.equal(result.condition, '多云');
});

test('current 与 daily 映射进视图形状', async () => {
  const provider = new OpenMeteoProvider({ fetchImpl: fakeFetch() });
  const result = await provider.fetchWeather('shanghai', { now: FIXED_NOW });
  assert.equal(result.forecast.length, 2);
  assert.equal(result.forecast[0].highC, 18);
  assert.equal(result.forecast[0].lowC, 11);
  assert.equal(result.forecast[0].isToday, true);
  assert.equal(result.forecast[1].condition, '小雨');
  assert.match(result.windText, /m\/s/);
});

test('未知城市走 geocoding，查不到返回 null', async () => {
  const fetchImpl = async (url) => ({
    ok: true,
    json: async () => (url.includes('geocoding-api') ? { results: [] } : FORECAST_BODY),
  });
  const provider = new OpenMeteoProvider({ fetchImpl });
  assert.equal(await provider.fetchWeather('不存在的地方', { now: FIXED_NOW }), null);
});

test('provider 不可用抛统一定制错误', async () => {
  const provider = new OpenMeteoProvider({ fetchImpl: fakeFetch({ ok: false }) });
  await assert.rejects(() => provider.fetchWeather('北京', { now: FIXED_NOW }), { message: PROVIDER_ERROR_MESSAGE });
});

test('WMO 码与风向有确定映射', () => {
  assert.deepEqual(wmoToCondition(0), { condition: '晴', icon: '☀' });
  assert.deepEqual(wmoToCondition(95).condition, '雷阵雨');
  assert.equal(windDirectionLabel(0), '北');
  assert.equal(windDirectionLabel(180), '南');
  assert.equal(windDirectionLabel(NaN), '');
});
JS

node --input-type=module -e "
import fs from 'node:fs';
const p = 'src/app.js';
const impOld = \"import { LocalFixtureWeatherProvider } from './providers/weatherProvider.js';\";
const impNew = \"import { OpenMeteoProvider } from './providers/openMeteoProvider.js';\";
const useOld = 'new LocalFixtureWeatherProvider({ shouldFail: simulateFailure })';
const useNew = 'new OpenMeteoProvider()';
// 真机空沙箱里没有 app.js：用最小骨架创建，注入动作仍然真实发生（不是跳过）。
const skeleton = impOld + '\nconst simulateFailure = false;\nconst provider = ' + useOld + ';\nexport default provider;\n';
let s = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : skeleton;
if (!s.includes(impOld) || !s.includes(useOld)) { console.error('E2E_EVIDENCE app_anchor=MISSING'); process.exit(1); }
s = s.replace(impOld, impNew).replace(useOld, useNew);
fs.writeFileSync(p, s);
console.log('E2E_EVIDENCE app_anchor=OK');
" 2>&1 || true

sleep 3
TEST_LOG=$(node --test test/openMeteoProvider.test.js 2>&1)
printf '%s\n' "$TEST_LOG"
if printf '%s' "$TEST_LOG" | grep -q '^# fail 0'; then
  PASS=$(printf '%s' "$TEST_LOG" | grep '^# pass' | awk '{print $3}')
  printf 'E2E_EVIDENCE provider_written=1 tests_pass=%s\n' "$PASS"
  printf '{"type":"agent_message","text":"wrote src/providers/openMeteoProvider.js + test (%s pass), injected into app.js"}\n' "$PASS"
else
  printf 'E2E_EVIDENCE provider_written=0 tests_pass=0\n'
  printf '{"type":"agent_message","text":"openMeteoProvider tests FAILED"}\n'
  printf 'WEATHER_E2E_EVIDENCE.json written with failure\n' > WEATHER_E2E_EVIDENCE.json
  sleep 2
  exit 0
fi

LIVE_OK=0
LIVE_TEMP=""
LIVE_QUERY=''
if [ "${TENON_E2E_OFFLINE:-0}" != "1" ]; then
  LIVE_QUERY=$(node --input-type=module -e "
import { OpenMeteoProvider } from './src/providers/openMeteoProvider.js';
const p = new OpenMeteoProvider();
const r = await p.fetchWeather('北京');
process.stdout.write(String(r && r.tempC));
" 2>/dev/null) || LIVE_QUERY=''
  if [ -n "$LIVE_QUERY" ]; then
    LIVE_OK=1
    LIVE_TEMP="$LIVE_QUERY"
    printf 'E2E_EVIDENCE live_ok=1 live_temp=%s\n' "$LIVE_TEMP"
    printf '{"type":"agent_message","text":"live Open-Meteo call OK: Beijing now %s C"}\n' "$LIVE_TEMP"
  else
    printf 'E2E_EVIDENCE live_ok=0\n'
    printf '{"type":"agent_message","text":"live Open-Meteo call failed (network gated?); deterministic tests still green"}\n'
  fi
fi

printf '{"provider":"src/providers/openMeteoProvider.js","testsPass":%s,"liveOk":%s,"liveTempC":"%s","offline":"%s"}\n' \
  "${PASS:-0}" "$LIVE_OK" "$LIVE_TEMP" "${TENON_E2E_OFFLINE:-0}" > WEATHER_E2E_EVIDENCE.json

sleep 2
printf '{"type":"turn.completed"}\n'
exit 0
