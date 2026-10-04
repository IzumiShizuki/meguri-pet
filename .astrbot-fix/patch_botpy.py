from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"{label} block mismatch")
    return text.replace(old, new, 1)


ROBOT = Path('/usr/local/lib/python3.12/site-packages/botpy/robot.py')
HTTP = Path('/usr/local/lib/python3.12/site-packages/botpy/http.py')
GATEWAY = Path('/usr/local/lib/python3.12/site-packages/botpy/gateway.py')
LLM_METADATA = Path('/AstrBot/astrbot/core/utils/llm_metadata.py')
HAPI_MAIN = Path('/AstrBot/data/plugins/astrbot_plugin_hapi_connector/main.py')
BILI_LISTENER = Path('/AstrBot/data/plugins/astrbot_plugin_bilibili/listener.py')
WEIXIN_OC_CLIENT = Path('/AstrBot/astrbot/core/platform/sources/weixin_oc/weixin_oc_client.py')

robot_text = ROBOT.read_text(encoding='utf-8')
if 'import requests' not in robot_text:
    robot_text = robot_text.replace(
        "import time\n\nimport aiohttp\n",
        "import time\nimport os\n\nimport aiohttp\nimport requests\n",
        1,
    )
marker_start = "    async def update_access_token(self):\n"
marker_end = '        if "access_token" not in data or "expires_in" not in data:\n'
if 'requests.post(' not in robot_text or 'for _ in range(3):' in robot_text or 'http.client' not in robot_text:
    prefix, sep, rest = robot_text.partition(marker_start)
    if not sep:
        raise SystemExit('robot.py start marker missing')
    _, sep2, suffix = rest.partition(marker_end)
    if not sep2:
        raise SystemExit('robot.py end marker missing')
    new_func = """    async def update_access_token(self):
        data = None

        def _fetch():
            # 2026-09 fix: 直连获取 access_token.
            # 实测容器内 requests(即使 proxies=None)仍会走 clash-core 代理,
            # 而代理到腾讯线路故障 -> SSL EOF. 改用 http.client 直连:
            # http.client 不读取任何代理环境变量, 无法走代理; DNS 走 /etc/hosts 固定 IP.
            import http.client
            import ssl
            import json as _json
            ctx = ssl.create_default_context()
            conn = http.client.HTTPSConnection(
                \"bots.qq.com\", 443, timeout=30, context=ctx
            )
            body = _json.dumps({
                \"appId\": self.app_id,
                \"clientSecret\": self.secret,
            })
            conn.request(
                \"POST\",
                \"/app/getAppAccessToken\",
                body=body,
                headers={
                    \"Content-Type\": \"application/json\",
                    \"Accept\": \"application/json\",
                },
            )
            resp = conn.getresponse()
            raw = resp.read()
            conn.close()
            if resp.status != 200:
                raise RuntimeError(
                    f\"[botpy] access_token http status={resp.status}: {raw[:200]!r}\"
                )
            return _json.loads(raw)

        last_error = None
        for attempt in range(6):
            try:
                data = await asyncio.to_thread(_fetch)
                break
            except Exception as e:
                last_error = e
                _log.warning(\"[botpy] access_token fetch error (%s): %s\", type(e).__name__, e)
            await asyncio.sleep(min(2 ** attempt, 30))
        if data is None and last_error is not None:
            raise last_error
"""
    robot_text = prefix + new_func + marker_end + suffix
ROBOT.write_text(robot_text, encoding='utf-8')

http_text = HTTP.read_text(encoding='utf-8')
if 'trust_env=False' not in http_text:
    # 2026-09 fix: botpy REST 全部为腾讯域名, 一律直连(不走 clash-core 代理, 其到腾讯线路故障).
    new = """            self._session = aiohttp.ClientSession(
                connector=TCPConnector(limit=500, ssl=SSLContext(), force_close=True),
                trust_env=False,
            )
"""
    old_true = """            self._session = aiohttp.ClientSession(
                connector=TCPConnector(limit=500, ssl=SSLContext(), force_close=True),
                trust_env=True,
            )
"""
    old_fresh = """            self._session = aiohttp.ClientSession(
                connector=TCPConnector(limit=500, ssl=SSLContext(), force_close=True),
            )
"""
    if old_true in http_text:
        http_text = http_text.replace(old_true, new, 1)
    else:
        http_text = replace_once(http_text, old_fresh, new, 'http.py')
HTTP.write_text(http_text, encoding='utf-8')

gateway_text = GATEWAY.read_text(encoding='utf-8')
if 'import os' not in gateway_text:
    gateway_text = gateway_text.replace('import asyncio\n', 'import asyncio\nimport os\n', 1)
old_gateway_variants = [
    """        async with ClientSession(connector=TCPConnector(limit=10, ssl=SSLContext())) as session:
            async with session.ws_connect(self._session[\"url\"]) as ws_conn:
""",
    """        async with ClientSession(connector=TCPConnector(limit=10, ssl=SSLContext()), trust_env=True) as session:
            async with session.ws_connect(self._session[\"url\"]) as ws_conn:
""",
    """        proxy = os.getenv(\"HTTPS_PROXY\") or os.getenv(\"HTTP_PROXY\")
        ws_connect_kwargs = {\"proxy\": proxy} if proxy else {}
        async with ClientSession(connector=TCPConnector(limit=10, ssl=SSLContext()), trust_env=True) as session:
            async with session.ws_connect(self._session[\"url\"], **ws_connect_kwargs) as ws_conn:
""",
    # 旧版 patch(显式走代理)生成的代码, 用于升级已打过旧补丁的容器
    """        proxy = os.getenv(\"HTTPS_PROXY\") or os.getenv(\"HTTP_PROXY\")
        ws_connect_kwargs = {\"heartbeat\": 30, \"receive_timeout\": 90}
        if proxy:
            ws_connect_kwargs[\"proxy\"] = proxy
        timeout = __import__(\"aiohttp\").ClientTimeout(
            total=None,
            connect=20,
            sock_connect=20,
            sock_read=None,
        )
        async with ClientSession(
            connector=TCPConnector(limit=10, ssl=SSLContext()),
            trust_env=True,
            timeout=timeout,
        ) as session:
            async with session.ws_connect(self._session[\"url\"], **ws_connect_kwargs) as ws_conn:
""",
    # 上一版 patch(去代理但 trust_env=True)生成的代码, 用于升级当前运行中的容器
    """        ws_connect_kwargs = {\"heartbeat\": 30, \"receive_timeout\": 90}
        # 2026-09 fix: 不显式指定 proxy, 交由 trust_env + NO_PROXY 环境变量决定.
        # api.sgroup.qq.com 在 NO_PROXY 中, 将直连(不走 clash-core 代理).
        timeout = __import__(\"aiohttp\").ClientTimeout(
            total=None,
            connect=20,
            sock_connect=20,
            sock_read=None,
        )
        async with ClientSession(
            connector=TCPConnector(limit=10, ssl=SSLContext()),
            trust_env=True,
            timeout=timeout,
        ) as session:
            async with session.ws_connect(self._session[\"url\"], **ws_connect_kwargs) as ws_conn:
""",
]
new_gateway = """        ws_connect_kwargs = {\"heartbeat\": 30, \"receive_timeout\": 90}
        # 2026-09 fix: 不显式指定 proxy 且 trust_env=False, 强制直连
        # (api.sgroup.qq.com 直连可用, clash-core 到腾讯线路故障).
        timeout = __import__(\"aiohttp\").ClientTimeout(
            total=None,
            connect=20,
            sock_connect=20,
            sock_read=None,
        )
        async with ClientSession(
            connector=TCPConnector(limit=10, ssl=SSLContext()),
            trust_env=False,
            timeout=timeout,
        ) as session:
            async with session.ws_connect(self._session[\"url\"], **ws_connect_kwargs) as ws_conn:
"""
if new_gateway not in gateway_text and 'QQ direct gateway patch' not in gateway_text:
    for old_gateway in old_gateway_variants:
        if old_gateway in gateway_text:
            gateway_text = gateway_text.replace(old_gateway, new_gateway, 1)
            break
    else:
        raise SystemExit('gateway.py block mismatch')
GATEWAY.write_text(gateway_text, encoding='utf-8')

weixin_text = WEIXIN_OC_CLIENT.read_text(encoding='utf-8')
old_weixin = """            self._http_session = aiohttp.ClientSession(timeout=timeout)\n"""
new_weixin = """            self._http_session = aiohttp.ClientSession(timeout=timeout, trust_env=True)\n"""
if 'ClientSession(timeout=timeout, trust_env=True)' not in weixin_text:
    weixin_text = replace_once(weixin_text, old_weixin, new_weixin, 'weixin_oc_client.py')
WEIXIN_OC_CLIENT.write_text(weixin_text, encoding='utf-8')

llm_text = LLM_METADATA.read_text(encoding='utf-8')
# 兼容 v4.26(12空格) 与 v4.28+(16空格) 结构: 按匹配到的缩进插入 gzip headers 行
if 'Accept-Encoding' not in llm_text:
    llm_m = re.search(r'^([ \t]+)async with session\.get\(url\) as response:\n', llm_text, re.M)
    if not llm_m:
        raise SystemExit('llm_metadata.py block mismatch')
    llm_indent = llm_m.group(1)
    llm_text = (
        llm_text[:llm_m.start()]
        + llm_indent + 'headers = {"Accept-Encoding": "gzip, deflate"}\n'
        + llm_indent + 'async with session.get(url, headers=headers) as response:\n'
        + llm_text[llm_m.end():]
    )
LLM_METADATA.write_text(llm_text, encoding='utf-8')

hapi_text = HAPI_MAIN.read_text(encoding='utf-8')
hapi_guard = """        endpoint = (self.config.get(\"hapi_endpoint\", \"\") or \"\").strip()
        if not endpoint:
            logger.info(\"HAPI endpoint missing, skipping initialization.\")
            return

"""
if 'HAPI endpoint missing, skipping initialization.' not in hapi_text:
    target = "        await self.client.init()\n"
    hapi_text = replace_once(
        hapi_text,
        target,
        hapi_guard + target,
        'hapi main initialize guard',
    )
HAPI_MAIN.write_text(hapi_text, encoding='utf-8')

# bilibili 1.5.x 旧结构: listener.py 位于插件根目录, 需要补丁加凭据缺失告警
# bilibili 1.6.6+ 重构: listener 移至 services/listener.py 且原生支持凭据缺失告警, 无需补丁
if BILI_LISTENER.exists():
    bili_text = BILI_LISTENER.read_text(encoding='utf-8')
    if 'warned_missing_credential = False' not in bili_text:
        start_marker = "    async def start(self):\n"
        loop_marker = "            for sub_user, sub_list in all_subs.items():\n"
        start_idx = bili_text.find(start_marker)
        loop_idx = bili_text.find(loop_marker, start_idx)
        if start_idx == -1 or loop_idx == -1:
            print('[patch_botpy] bilibili listener block mismatch, skip', flush=True)
        else:
            suffix = bili_text[loop_idx + len(loop_marker):]
            new_head = """    async def start(self):
        \"\"\"Start background polling loop.\"\"\"
        warned_missing_credential = False
        while True:
            all_subs = self.data_manager.get_all_subscriptions()
            if self.bili_client.credential is None:
                if not all_subs:
                    await asyncio.sleep(max(300, int(60 * self.interval_mins)))
                    continue
                if not warned_missing_credential:
                    logger.warning(
                        "Bilibili credential missing; use /bili_login or set sessdata."
                    )
                    warned_missing_credential = True
                await asyncio.sleep(60 * self.interval_mins)
                continue

            warned_missing_credential = False
"""
            bili_text = bili_text[:start_idx] + new_head + suffix
            BILI_LISTENER.write_text(bili_text, encoding='utf-8')
else:
    print('[patch_botpy] bilibili plugin uses new structure (1.6.6+), patch skipped', flush=True)

MANAGER = Path('/AstrBot/astrbot/core/platform/manager.py')
manager_text = MANAGER.read_text(encoding='utf-8')
if '_schedule_platform_auto_reload' not in manager_text:
    old_task_head = """        try:
            await task
        except asyncio.CancelledError:
"""
    new_task_head = """        try:
            await task
            # 2026-09 fix: 平台正常结束, 重置自动重载计数
            if platform and hasattr(self, "_platform_reload_states"):
                self._platform_reload_states.pop(platform.config[\"id\"], None)
        except asyncio.CancelledError:
"""
    manager_text = replace_once(
        manager_text, old_task_head, new_task_head, 'manager.py task head'
    )
    old_task_tail = """            # 记录错误到平台实例
            if platform:
                platform.record_error(error_msg, tb_str)

    async def reload(self, platform_config: dict) -> None:
"""
    new_task_tail = """            # 记录错误到平台实例
            if platform:
                platform.record_error(error_msg, tb_str)
            # 2026-09 fix: 平台启动失败(如网络瞬时故障)时自动重载, 避免 QQ 等平台一直掉线
            if platform:
                self._schedule_platform_auto_reload(platform)

    def _schedule_platform_auto_reload(self, platform) -> None:
        \"\"\"Schedule reload of a platform that failed to start (transient network resilience).\"\"\"
        if not hasattr(self, \"_platform_reload_states\"):
            self._platform_reload_states = {}
        st = self._platform_reload_states.setdefault(
            platform.config[\"id\"], {\"tries\": 0, \"scheduled\": False, \"last\": 0.0}
        )
        if st[\"scheduled\"]:
            return
        # 距离上次失败超过 30 分钟视为新的一次故障, 重置重试预算
        now = asyncio.get_running_loop().time()
        if now - st.get(\"last\", 0.0) > 1800:
            st[\"tries\"] = 0
        st[\"last\"] = now
        if st[\"tries\"] >= 5:
            logger.error(
                f\"[platform.manager] {platform.config['id']} 自动重载连续失败 5 次, 停止重试, 请手动重载\"
            )
            return
        pc = next(
            (c for c in self.platforms_config if c.get(\"id\") == platform.config[\"id\"]), None
        )
        if pc is None:
            logger.error(f\"[platform.manager] {platform.config['id']} 配置未找到, 跳过自动重载\")
            return
        st[\"tries\"] += 1
        st[\"scheduled\"] = True
        delay = 60 * st[\"tries\"]

        async def _delayed_reload():
            await asyncio.sleep(delay)
            st[\"scheduled\"] = False
            try:
                await self.reload(pc)
            except Exception:
                logger.error(traceback.format_exc())
                st[\"tries\"] = min(st[\"tries\"] + 1, 5)

        asyncio.create_task(_delayed_reload())
        logger.warning(
            f\"[platform.manager] {platform.config['id']} 启动失败, {delay}s 后自动重载 (第 {st['tries']}/5 次)\"
        )

    async def reload(self, platform_config: dict) -> None:
"""
    manager_text = replace_once(
        manager_text, old_task_tail, new_task_tail, 'manager.py task tail'
    )
MANAGER.write_text(manager_text, encoding='utf-8')

print('[patch_botpy] patch applied')
