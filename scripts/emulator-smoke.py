"""Drive two disposable Android emulators through the real APK UI.

Uses only adb, Python's standard library, and Pillow for optional screenshots.
Never grants MediaProjection through adb/app-ops: the system dialog is tapped.
Room codes remain in memory and are redacted from diagnostic output.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path.home() / ".cache/screenguideqi/toolchains/android-sdk"
ADB = SDK / "platform-tools/adb.exe"
OUT = ROOT / "build/emulator-test"
APP = "dev.screenguideqi"


def redact(value: str) -> str:
    return re.sub(r"(?<!\d)\d{4}\s*\d{4}(?!\d)", "[redacted]", value)


def log(value: str) -> None:
    print(redact(value), flush=True)


class Device:
    def __init__(self, serial: str):
        if not serial.startswith("emulator-"):
            raise ValueError("This test operates only on disposable emulators")
        self.serial = serial

    def adb(self, *args: str, timeout: float = 30, binary: bool = False):
        result = subprocess.run([str(ADB), "-s", self.serial, *args],
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(redact(result.stderr.decode("utf-8", errors="replace")))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def shell(self, *args: str) -> str:
        return self.adb("shell", *args)

    def nodes(self) -> list[dict[str, str]]:
        # The transient XML stays inside the disposable emulator.
        self.shell("uiautomator", "dump", "/sdcard/sgqi-window.xml")
        raw = self.adb("exec-out", "cat", "/sdcard/sgqi-window.xml")
        return [node.attrib for node in ET.fromstring(raw).iter("node")]

    def inspect(self) -> None:
        for node in self.nodes():
            value = node.get("text") or node.get("content-desc")
            if value or "Surface" in node.get("class", ""):
                log(f"{node.get('class')} {node.get('bounds')} {value}")

    @staticmethod
    def bounds(node: dict[str, str]) -> tuple[int, int, int, int]:
        return tuple(map(int, re.findall(r"\d+", node["bounds"])))

    def tap_node(self, node: dict[str, str]) -> None:
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def find(self, text: str, *, contains: bool = False, timeout: float = 20,
             scroll: bool = False) -> dict[str, str]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for node in self.nodes():
                value = node.get("text", "")
                if (text in value if contains else text == value):
                    return node
            if scroll:
                self.shell("input", "swipe", "650", "1050", "650", "450", "250")
            time.sleep(.4)
        raise RuntimeError(f"{self.serial}: UI text not found: {text}")

    def tap(self, text: str, **kwargs) -> None:
        self.tap_node(self.find(text, **kwargs))

    def launch(self) -> None:
        self.shell("cmd", "statusbar", "collapse")
        self.shell("am", "start", "-n", f"{APP}/.MainActivity", "-f", "0x20000000")

    def top(self) -> None:
        self.shell("input", "swipe", "650", "300", "650", "1100", "900")
        self.shell("input", "swipe", "650", "300", "650", "1100", "900")

    def set_signal(self, address: str) -> None:
        if not re.fullmatch(r"wss?://[a-zA-Z0-9.:-]+/signal", address):
            raise ValueError("Use a plain test signal URL without credentials or query parameters")
        self.find("我是共享端 · 创建协助码", timeout=20)
        field = next(n for n in self.nodes() if n.get("class") == "android.widget.EditText")
        self.tap_node(field)
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        self.shell("input", "keyevent", *(["67"] * len(field.get("text", ""))))
        self.shell("input", "text", address)
        self.shell("input", "keyevent", "4")

    def screenshot(self, name: str) -> Path:
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / name
        image = self.adb("exec-out", "screencap", "-p", binary=True)
        # Only call this after pairing has consumed the room code.
        path.write_bytes(image)
        return path

    def diagnostics(self) -> str:
        return next((n["text"] for n in self.nodes() if n.get("text", "").startswith("视频统计：")), "")


def pair(owner: Device, viewer: Device) -> None:
    owner.launch()
    viewer.launch()
    owner.top()
    viewer.top()
    owner.tap("我是共享端 · 创建协助码")
    owner.find("等待对方输入协助码", contains=True, timeout=55)
    code = next((re.sub(r"\s", "", n.get("text", "")) for n in owner.nodes()
                 if re.fullmatch(r"\d{4}\s*\d{4}", n.get("text", ""))), None)
    if not code:
        raise RuntimeError("Room created but no code found")
    fields = [n for n in viewer.nodes() if n.get("class") == "android.widget.EditText"]
    if len(fields) != 2:
        raise RuntimeError("Viewer room input not found")
    viewer.tap_node(fields[-1])
    viewer.shell("input", "keyevent", "KEYCODE_MOVE_END")
    viewer.shell("input", "keyevent", *(["67"] * 8))
    viewer.shell("input", "text", code)
    viewer.shell("input", "keyevent", "4")
    viewer.tap("我是指导端 · 申请加入")
    owner.tap("接受加入", timeout=60)
    owner.find("开始共享屏幕", contains=True)
    log("PASS: APK room creation, join request and visible owner confirmation")


def authorize(owner: Device) -> None:
    start_capture = next((n for n in owner.nodes() if "开始共享屏幕" in n.get("text", "")), None)
    if start_capture:
        owner.tap_node(start_capture)
    # A fresh install requests notifications before the system screen-capture dialog.
    for _ in range(4):
        nodes = owner.nodes()
        permission = next((n for n in nodes if n.get("resource-id", "").endswith("permission_allow_button")), None)
        if permission:
            owner.tap_node(permission)
            continue
        start = next((n for n in nodes if n.get("text", "").casefold() in ("start now", "开始", "立即开始", "start")), None)
        if start:
            owner.tap_node(start)
            break
        time.sleep(.5)
    owner.find("屏幕采集已启动", timeout=20)
    log("PASS: Android notification request and explicit MediaProjection system authorization")


def basic_run(owner: Device, viewer: Device) -> None:
    pair(owner, viewer)
    authorize(owner)
    wait_video(owner, viewer)


def wait_video(owner: Device, viewer: Device) -> None:
    viewer.find("对方屏幕", timeout=45, scroll=True)
    viewer.shell("input", "swipe", "650", "1100", "650", "500", "350")
    # Move the capture to a different real Android activity, avoiding recursive self-preview.
    owner.shell("am", "start", "-a", "android.settings.SETTINGS")
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        status = viewer.diagnostics()
        match = re.search(r"解码 (\d+) 帧", status)
        if match and int(match.group(1)) > 0:
            log("PASS: " + status)
            owner.screenshot("owner-settings.png")
            viewer.screenshot("viewer-settings.png")
            return
        time.sleep(1)
    raise RuntimeError("No decoded video frames observed through the APK UI; " + viewer.diagnostics())


def counters(viewer: Device) -> tuple[int, int]:
    status = viewer.diagnostics()
    match = re.search(r"收到 (\d+) 字节，解码 (\d+) 帧", status)
    if not match:
        raise RuntimeError("Video counters not visible: " + status)
    return int(match.group(1)), int(match.group(2))


def assert_capture_stopped(owner: Device, timeout: float = 15) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        projection = owner.shell("dumpsys", "media_projection")
        services = owner.shell("dumpsys", "activity", "services", APP)
        if APP not in projection and ".capture.ProjectionService" not in services:
            log("PASS: MediaProjection and foreground service completely stopped")
            return
        time.sleep(.5)
    raise RuntimeError("Projection or foreground service still active after stop")


def exercise(owner: Device, viewer: Device, duration: float = 60) -> None:
    start_bytes, start_frames = counters(viewer)
    started = time.monotonic()
    changes = 0
    previous_bytes, previous_frames = start_bytes, start_frames
    stalled = 0
    scene = 0
    while time.monotonic() - started < duration:
        action = "android.settings.DISPLAY_SETTINGS" if scene % 2 == 0 else "android.settings.SETTINGS"
        scene += 1
        owner.shell("am", "start", "-a", action)
        time.sleep(3)
        current_bytes, current_frames = counters(viewer)
        if current_frames > previous_frames and current_bytes > previous_bytes:
            changes += 1
            stalled = 0
        else:
            stalled += 1
            if stalled >= 3:
                raise RuntimeError("Video counters stopped increasing across scene changes")
        previous_bytes, previous_frames = current_bytes, current_frames
    end_bytes, end_frames = counters(viewer)
    if changes < 2 or end_frames <= start_frames or end_bytes <= start_bytes:
        raise RuntimeError("Video did not continue changing during the sustained test")
    log(f"PASS: sustained video for {time.monotonic() - started:.1f}s; bytes {start_bytes}->{end_bytes}; decoded frames {start_frames}->{end_frames}; scene checks={changes}")
    owner.shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(2)
    # Position the entire aspect-fitted preview inside the screenshot.
    viewer.top()
    header = viewer.find("对方屏幕")
    delta = max(0, min(500, viewer.bounds(header)[1] - 120))
    if delta:
        viewer.shell("input", "swipe", "650", "1000", "650", str(1000 - delta), "1000")
    owner.screenshot("owner-settings.png")
    viewer.screenshot("viewer-settings.png")


def suite(owner: Device, viewer: Device) -> None:
    exercise(owner, viewer)
    viewer.tap("断开并结束本次协助", scroll=True)
    assert_capture_stopped(owner)
    log("PASS: viewer disconnect ends owner capture")

    # Same processes / same ViewModels: a new session must ask for capture permission again.
    pair(owner, viewer)
    owner.tap("开始共享屏幕", contains=True)
    owner.tap("CANCEL")
    assert_capture_stopped(owner)
    log("PASS: declining the system capture dialog does not start capture")
    owner.tap("断开并结束本次协助", scroll=True)

    pair(owner, viewer)
    authorize(owner)
    wait_video(owner, viewer)
    owner.launch()
    owner.tap("停止屏幕共享", scroll=True)
    assert_capture_stopped(owner)
    viewer.find("对方已结束会话", timeout=15, scroll=True)
    log("PASS: repeat session requires a new system authorization and owner stop ends both sides")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "suite-result.json").write_text(json.dumps({
        "androidApi": 34, "devices": 2, "videoSeconds": 60,
        "sustainedVideo": True, "viewerDisconnectCleanup": True,
        "declineCapture": True, "repeatSession": True, "ownerStopCleanup": True,
        "realPhoneTest": False, "crossCarrierNatTest": False,
    }, indent=2), encoding="utf-8")


def extra_checks(owner: Device, viewer: Device) -> None:
    from PIL import Image
    before = counters(viewer)
    owner.shell("settings", "put", "system", "accelerometer_rotation", "0")
    try:
        owner.shell("settings", "put", "system", "user_rotation", "1")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if counters(viewer)[1] > before[1]:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Video did not continue after rotating the owner")
        path = owner.screenshot("owner-landscape.png")
        with Image.open(path) as image:
            if image.width <= image.height:
                raise RuntimeError("Owner did not actually rotate to landscape")
        viewer.screenshot("viewer-landscape.png")
        log("PASS: real display rotation to landscape, capture resize and continued decoding")
    finally:
        owner.shell("settings", "put", "system", "user_rotation", "0")
    time.sleep(2)
    notification_stop(owner, viewer)


def notification_stop(owner: Device, viewer: Device) -> None:
    owner.shell("cmd", "statusbar", "expand-notifications")
    nodes = owner.nodes()
    if not any(n.get("text") == "停止共享" for n in nodes):
        notice = next(n for n in nodes if n.get("text") == "正在共享屏幕")
        notice_y = owner.bounds(notice)[1]
        expands = [n for n in nodes if (n.get("text") or n.get("content-desc")) == "Expand" and owner.bounds(n)[1] <= notice_y + 50]
        if expands:
            owner.tap_node(max(expands, key=lambda n: owner.bounds(n)[1]))
    owner.tap("停止共享", timeout=15)
    assert_capture_stopped(owner)
    viewer.find("对方已结束会话", timeout=15, scroll=True)
    log("PASS: notification stop action releases projection and ends the viewer session")
    (OUT / "extra-result.json").write_text(json.dumps({
        "androidApi": 34, "rotation": True, "notificationStop": True,
        "signal": "configured test endpoint; see run command", "mediaNetwork": "virtual Wi-Fi",
        "realPhoneTest": False, "crossCarrierNatTest": False,
    }, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "screen", "run", "video", "suite", "extra"))
    parser.add_argument("--owner", default="emulator-5554")
    parser.add_argument("--viewer", default="emulator-5556")
    parser.add_argument("--signal", help="Override signal URL for this disposable emulator test")
    args = parser.parse_args()
    owner, viewer = Device(args.owner), Device(args.viewer)
    if args.action == "inspect":
        log("OWNER")
        owner.inspect()
        log("VIEWER")
        viewer.inspect()
    elif args.action == "screen":
        log(str(owner.screenshot("owner-current.png")))
        log(str(viewer.screenshot("viewer-current.png")))
    elif args.action == "video":
        authorize(owner)
        wait_video(owner, viewer)
    elif args.action == "suite":
        suite(owner, viewer)
    elif args.action == "extra":
        extra_checks(owner, viewer)
    else:
        for device in (owner, viewer):
            device.shell("am", "force-stop", APP)
            device.launch()
            if args.signal:
                if args.signal.startswith("ws://127.0.0.1:8787/"):
                    device.adb("reverse", "tcp:8787", "tcp:8787")
                device.set_signal(args.signal)
        basic_run(owner, viewer)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
