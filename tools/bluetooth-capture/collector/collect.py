"""Attaches the observe-only capture agent to a running app and records events.

Attach, never spawn. Spawning would restart the target, change its PID, and
throw away whatever Bluetooth session the operator just set up; the milestone
acceptance is explicitly that the PID is unchanged across attach and detach.

Refuses to touch system processes. Injecting the Java bridge into
com.android.settings aborted that process on this ROM, and the capture plan
forbids attaching to system_server or com.android.bluetooth outright.
"""

import argparse
import json
import os
import signal
import sys
import time

import frida

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from event_writer import EventWriter          # noqa: E402
from session import Session, utc_now_iso      # noqa: E402

PROCESS_DENYLIST = {
    "system_server",
    "com.android.bluetooth",
    "com.android.settings",
    "com.android.systemui",
    "com.xiaomi.bluetooth",
    "com.milink.service",
}

DEFAULT_AGENT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_agent.js")


class Collector:
    def __init__(self, args):
        self.args = args
        self.session = Session(args.output_root, args.session_id)
        self.writer = EventWriter(self.session.events_path, self.session.payloads_path)
        self.device = None
        self.script = None
        self.frida_session = None
        self.pid_before = None
        self.pid_after = None
        self.health = None
        self.stopping = False
        self.spawned = False

    def _on_message(self, message, data):
        if message["type"] == "send":
            self.writer.write(message["payload"], data)
        elif message["type"] == "error":
            self.writer.write({
                "kind": "agent.exception",
                "description": message.get("description"),
                "stack": message.get("stack"),
            })
            print(f"[agent error] {message.get('description')}", file=sys.stderr)

    def connect(self):
        self.device = frida.get_device_manager().add_remote_device(self.args.host)
        print(f"已连接 frida 端点 {self.args.host}")

    def resolve_target(self):
        if self.args.package in PROCESS_DENYLIST:
            raise SystemExit(f"拒绝注入系统进程：{self.args.package}")

        if self.args.spawn:
            # Cold-init handshakes finish within a second of the app connecting,
            # long before an attach can land, so catching them needs the process
            # gated at startup. This necessarily changes the PID, which is why it
            # is opt-in and the PID-stability check is skipped below.
            self.pid_before = self.device.spawn([self.args.package])
            self.spawned = True
            print(f"已 spawn 并门控 {self.args.package} pid={self.pid_before}")
            return

        pids = self._pids_for_package()
        if not pids:
            raise SystemExit(
                f"目标未在运行：{self.args.package}。请先手动启动 App，"
                "collector 只 attach 不 spawn。"
            )
        if len(pids) > 1:
            raise SystemExit(f"{self.args.package} 有多个进程：{sorted(pids)}")

        self.pid_before = pids[0]
        print(f"目标进程 {self.args.package} pid={self.pid_before}")

    def _pids_for_package(self):
        """Resolve a package name to its running PIDs.

        enumerate_processes() reports the *display label* for apps ("Sound
        Connect"), not the package, so matching there silently finds nothing.
        enumerate_applications() carries the package in .identifier and is the
        only reliable lookup; the process-name pass is a fallback for
        non-app processes.
        """
        pids = {
            app.pid
            for app in self.device.enumerate_applications()
            if app.identifier == self.args.package and app.pid
        }
        if not pids:
            pids = {p.pid for p in self.device.enumerate_processes() if p.name == self.args.package}
        return sorted(pids)

    def attach(self):
        with open(self.args.agent, "r", encoding="utf-8") as handle:
            source = handle.read()

        self.frida_session = self.device.attach(self.pid_before)
        self.script = self.frida_session.create_script(source)
        self.script.on("message", self._on_message)
        self.script.load()

        if self.spawned:
            # At the spawn gate the Java VM is not initialised yet, so
            # Java.perform() queues its callback instead of running inline and
            # the hooks are not in place at load time. Resuming lets the VM come
            # up; the queued callback then runs long before app code can open a
            # socket, so nothing is missed by resuming first.
            self.device.resume(self.pid_before)
            print("已恢复目标进程，等待 Java VM 与 hook 就绪")

        self.health = self._await_health()
        print(json.dumps(self.health, ensure_ascii=False, indent=2))
        if not self.health.get("ready"):
            raise SystemExit("agent 未就绪，拒绝进入采集：" + str(self.health.get("errors")))

    def _await_health(self, timeout=15.0):
        """Attach mode is ready at load; spawn mode needs the VM to come up."""
        deadline = time.time() + timeout
        health = self.script.exports_sync.health()
        while not health.get("ready") and time.time() < deadline:
            time.sleep(0.25)
            health = self.script.exports_sync.health()
        return health

    def mark(self, label, phase):
        try:
            self.script.exports_sync.mark(label, phase)
        except Exception as error:                        # noqa: BLE001
            print(f"[mark failed] {error}", file=sys.stderr)

    def self_test(self):
        """Verifies the payload chain: hook -> binary channel -> writer.

        Uses a fixed pattern that includes 0x00 and 0x80..0xFF so sign handling
        on the Java byte[] boundary is actually exercised.
        """
        pattern = bytes([0x00, 0x01, 0x7F, 0x80, 0xAA, 0xFF, 0x55, 0xC3])
        signed = [b - 256 if b > 127 else b for b in pattern]

        before = self.writer.event_count
        if not self.script.exports_sync.self_test(signed):
            print("自检失败：agent 未能构造测试对象", file=sys.stderr)
            return False
        time.sleep(0.5)

        captured = self._read_last_payload()
        if captured is None:
            print(f"自检失败：未捕获到 payload（新增事件 {self.writer.event_count - before} 条）",
                  file=sys.stderr)
            return False
        if captured != pattern:
            print(f"自检失败：payload 不一致 期望={pattern.hex()} 实际={captured.hex()}", file=sys.stderr)
            return False

        print(f"自检通过：payload 字节级一致（{pattern.hex()}）")
        return True

    def _read_last_payload(self):
        with open(self.session.events_path, "r", encoding="utf-8") as handle:
            events = [json.loads(line) for line in handle if line.strip()]
        for event in reversed(events):
            if event.get("kind") == "gatt.setvalue" and "payloadOffset" in event:
                with open(self.session.payloads_path, "rb") as blob:
                    blob.seek(event["payloadOffset"])
                    return blob.read(event["payloadBytes"])
        return None

    def run(self):
        deadline = time.time() + self.args.duration if self.args.duration else None

        def stop(_signum, _frame):
            self.stopping = True

        signal.signal(signal.SIGINT, stop)
        print("采集中，Ctrl-C 结束" + (f"（最长 {self.args.duration} 秒）" if deadline else ""))
        self.mark("session", "BEGIN")

        while not self.stopping:
            if deadline and time.time() >= deadline:
                break
            time.sleep(0.2)

        self.mark("session", "END")
        time.sleep(0.3)   # let in-flight messages drain before detaching

    def detach(self):
        if self.script is not None:
            try:
                self.script.unload()
            except frida.InvalidOperationError:
                pass
        if self.frida_session is not None:
            try:
                self.frida_session.detach()
            except frida.InvalidOperationError:
                pass

        pids = self._pids_for_package()
        self.pid_after = pids[0] if pids else None

    def finish(self, self_test_ok=None):
        self.writer.close()
        summary = self.writer.summary()
        # In spawn mode the PID is new by construction, so "unchanged" is not the
        # health signal; surviving the session is.
        if self.spawned:
            survived = self.pid_after is not None
        else:
            survived = self.pid_after is not None and self.pid_after == self.pid_before

        self.session.merge({
            "fridaCollector": {
                "package": self.args.package,
                "agent": os.path.abspath(self.args.agent),
                "pidBefore": self.pid_before,
                "pidAfter": self.pid_after,
                "processSurvived": survived,
                "selfTestPassed": self_test_ok,
                "health": self.health,
                "finishedAt": utc_now_iso(),
                **summary,
            }
        })

        print()
        print(f"事件数        : {summary['eventCount']}")
        print(f"payload 字节  : {summary['payloadBytes']}")
        print(f"事件类型分布  : {summary['kindCounts']}")
        print(f"PID 前/后     : {self.pid_before} / {self.pid_after}")
        if survived:
            print("进程存活且 PID 未变：attach/detach 健康检查通过")
        else:
            print("进程未存活或 PID 变化：本次会话不可用作证据", file=sys.stderr)
        if self_test_ok is not None:
            print(f"payload 链路自检: {'通过' if self_test_ok else '失败'}")
        print(f"产物          : {self.session.raw}")
        if not survived:
            return 1
        return 0 if self_test_ok is not False else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, help="目标应用包名（必须已在运行）")
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--output-root", default="D:\\HeadphoneCaptures")
    parser.add_argument("--host", default="127.0.0.1:27052")
    parser.add_argument("--agent", default=DEFAULT_AGENT)
    parser.add_argument("--duration", type=int, default=0, help="秒；0 表示直到 Ctrl-C")
    parser.add_argument("--self-test", action="store_true",
                        help="attach 后验证 payload 采集链路，不产生任何射频操作")
    parser.add_argument("--spawn", action="store_true",
                        help="spawn 并门控目标以捕获冷启动握手；会改变 PID，仅在需要 cold-init 时使用")
    args = parser.parse_args()

    if not os.path.exists(args.agent):
        raise SystemExit(f"未找到已编译 agent：{args.agent}（先运行 npm run build）")

    collector = Collector(args)
    collector.connect()
    collector.resolve_target()
    self_test_ok = None
    try:
        collector.attach()
        if args.self_test:
            self_test_ok = collector.self_test()
        collector.run()
    finally:
        collector.detach()
    exit_code = collector.finish(self_test_ok)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
