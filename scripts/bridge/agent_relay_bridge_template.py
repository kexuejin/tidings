#!/usr/bin/env python3
"""
Agent-Tidings Bridge Adapter Reference Template (v1.0)
===================================================
A lightweight, stateless bridge template demonstrating how any external AI agent
(e.g., Gemini CLI, Claude Code, custom MCP agent) connects to the Tidings host
control plane via local loopback HTTP (127.0.0.1:8788).

Security Principles ("三不做"):
1. No auth decisions: Tidings host control plane enforces roles (owner/admin/collab/guest) via allows().
2. No persistent private ledger: Device keys & grants are persisted centrally in agent-grants.json / SQLite.
3. No direct external networking: Loopback only; Tidings handles encrypted LAN P2P (agn1/ams1).
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error
from typing import Any, Dict, List, Optional

DEFAULT_RELAY_ENDPOINT = "http://127.0.0.1:8788"


class AgentRelayBridge:
    def __init__(self, endpoint: str = DEFAULT_RELAY_ENDPOINT, agent_kind: str = "Gemini"):
        self.endpoint = endpoint.rstrip("/")
        self.agent_kind = agent_kind

    def _post_json(self, path: str, data: Dict[str, Any]) -> Dict[str, Any]:
        url = f"{self.endpoint}{path}"
        body = json.dumps(data).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                resp_text = resp.read().decode("utf-8")
                return json.loads(resp_text) if resp_text else {"ok": True}
        except urllib.error.URLError as e:
            raise ConnectionError(f"Failed to communicate with Tidings host at {url}: {e}")

    def _get_json(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = f"{self.endpoint}{path}"
        if params:
            query = urllib.parse.urlencode(params)
            url = f"{url}?{query}"
        req = urllib.request.Request(
            url,
            headers={"Accept": "application/json"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                resp_text = resp.read().decode("utf-8")
                return json.loads(resp_text) if resp_text else {}
        except urllib.error.URLError as e:
            raise ConnectionError(f"Failed to communicate with Tidings host at {url}: {e}")

    def get_peers(self) -> List[Dict[str, Any]]:
        """Query currently discovered / connected peers."""
        resp = self._get_json("/v1/peers")
        return resp.get("peers", [])

    def resolve_to(self, to: Optional[str] = None) -> str:
        """Resolve target peer nodeId/IP. If not provided, fallback to granted or first online peer."""
        if to:
            return to
        try:
            grants_resp = self._get_json("/v1/agent/grants")
            grants = grants_resp.get("grants", [])
            if grants:
                # prefer an actively granted nodeId
                return grants[0]["nodeId"]
        except Exception:
            pass
        peers = self.get_peers()
        online_peers = [p for p in peers if p.get("online")]
        if online_peers:
            return online_peers[0].get("nodeId") or online_peers[0].get("ip")
        if peers:
            return peers[0].get("nodeId") or peers[0].get("ip")
        raise ConnectionError("No peer available in /v1/peers to target.")

    # 1. Announce Workspace
    def announce_workspace(
        self,
        ws_id: str,
        ws_title: str,
        ws_path: str,
        online: bool = True,
        to: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Announce that this agent kind is resident and active on the given workspace."""
        target = self.resolve_to(to)
        payload = {
            "to": target,
            "wsId": ws_id,
            "wsTitle": ws_title,
            "wsPath": ws_path,
            "agentKind": self.agent_kind,
            "online": online,
            "at": int(time.time() * 1000),
        }
        return self._post_json("/v1/agent/workspace", payload)

    # 2. Announce Task Session / Thread
    def announce_session(
        self,
        session_id: str,
        ws_id: str,
        ws_title: str,
        ws_path: str,
        alias: str,
        title: str,
        state: str = "busy",
        to: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Announce a task thread / session lifecycle under the workspace."""
        target = self.resolve_to(to)
        payload = {
            "to": target,
            "sessionId": session_id,
            "agentKind": self.agent_kind,
            "state": state,  # "busy", "idle", "paused"
            "alias": alias,
            "title": title,
            "wsId": ws_id,
            "wsTitle": ws_title,
            "wsPath": ws_path,
            "startedAt": int(time.time() * 1000),
        }
        return self._post_json("/v1/agent/session", payload)

    # 3. Request High-Risk Execution Approval
    def request_approval(
        self,
        approval_id: str,
        session_id: str,
        tool: str,
        summary: str,
        timeout_seconds: int = 120,
        to: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Request blocking user approval from mobile / collaborative peer."""
        target = self.resolve_to(to)
        payload = {
            "to": target,
            "approvalId": approval_id,
            "sessionId": session_id,
            "tool": tool,
            "summary": summary,
            "expiresAt": int((time.time() + timeout_seconds) * 1000),
        }
        return self._post_json("/v1/agent/approval", payload)

    # 4. Pull Inbound Messages & Approvals
    def pull_inbox(self, since_ms: int = 0, limit: int = 50) -> List[Dict[str, Any]]:
        """Pull incoming commands or approval results from remote peers."""
        import urllib.parse
        resp = self._get_json("/v1/inbox", {"since": since_ms, "limit": limit})
        return resp.get("items", [])

    # 5. Send Proactive Message to Peer or Channel
    def send_message(self, to_peer: str, text: str) -> Dict[str, Any]:
        """Send chat update or completion notification to remote peer."""
        return self._post_json("/v1/send", {"to": to_peer, "text": text})


def run_demo(bridge: AgentRelayBridge, ws_id: str, ws_path: str, ws_title: str, to: Optional[str] = None):
    print(f"[{bridge.agent_kind}] Announcing resident status on workspace '{ws_title}' ({ws_path})...")
    try:
        res1 = bridge.announce_workspace(ws_id, ws_title, ws_path, online=True, to=to)
        print(f"[{bridge.agent_kind}] Workspace announcement sent: {res1}")

        sess_id = f"sess-{bridge.agent_kind.lower()}-{int(time.time())}"
        print(f"[{bridge.agent_kind}] Creating task thread '{sess_id}'...")
        res2 = bridge.announce_session(
            session_id=sess_id,
            ws_id=ws_id,
            ws_title=ws_title,
            ws_path=ws_path,
            alias="#协同优化测试",
            title=f"{bridge.agent_kind} 协同分析任务",
            state="busy",
            to=to,
        )
        print(f"[{bridge.agent_kind}] Session '{sess_id}' announced: {res2}")
    except ConnectionError as e:
        print(f"[Warning] Tidings host not reachable ({e}). Running in offline demo mode.")


def main():
    parser = argparse.ArgumentParser(description="Agent-Tidings Bridge Adapter Reference Template")
    parser.add_argument("--endpoint", default=DEFAULT_RELAY_ENDPOINT, help="Tidings Desktop loopback URL")
    parser.add_argument("--agent-kind", default="Gemini", help="Agent identifier (e.g., Gemini, DSH, Claude)")
    parser.add_argument("--ws-id", default="ws-teahouse-mobile", help="Workspace identifier")
    parser.add_argument("--ws-title", default="teahouse-mobile", help="Workspace display title")
    parser.add_argument("--ws-path", default="/workspace/src/teahouse-mobile", help="Workspace directory")
    parser.add_argument("--to", default=None, help="Target peer nodeId or IP")
    parser.add_argument("--demo", action="store_true", help="Run self-test demo sequence")

    args = parser.parse_args()
    bridge = AgentRelayBridge(endpoint=args.endpoint, agent_kind=args.agent_kind)

    if args.demo:
        run_demo(bridge, args.ws_id, args.ws_path, args.ws_title, to=args.to)
    else:
        print(f"Agent-Tidings Bridge initialized for {args.agent_kind} at {args.endpoint}.")
        print("Use --demo to test announcement sequence, or import AgentRelayBridge into your agent runner.")


if __name__ == "__main__":
    main()
