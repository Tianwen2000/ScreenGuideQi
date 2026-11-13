"""Verify voice with a debug-only synthetic capture probe; no host mic/audio files.
Prepare opt-in flags before starting the screen session. Release APK excludes the probe.
"""
import argparse
import json
import re
import runpy
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/emulator-test"
sys.stdout.reconfigure(encoding="utf-8")
m = runpy.run_path(str(ROOT / "scripts/emulator-smoke.py"))
Device, log = m["Device"], m["log"]
owner, viewer = Device("emulator-5554"), Device("emulator-5556")


def app_logs(device):
    pid = device.shell("pidof", "dev.screenguideqi").strip()
    return device.adb("logcat", "-d", "--pid=" + pid, "-s", "SGQI.AudioProbe:D")


def prepare():
    for device, flag in ((owner, "audio-test-playback"), (viewer, "audio-test-tone")):
        device.shell("run-as", "dev.screenguideqi", "mkdir", "-p", "files")
        device.shell("run-as", "dev.screenguideqi", "touch", "files/" + flag)
    log("Prepared opt-in debug-only synthetic tone and playback counter probes")


def enable():
    viewer.tap("开启语音指导", scroll=True)
    for _ in range(5):
        nodes = viewer.nodes()
        allow = next((n for n in nodes if n.get("resource-id", "").endswith(("permission_allow_foreground_only_button", "permission_allow_button"))), None)
        if allow:
            viewer.tap_node(allow)
        elif any("关闭麦克风" == n.get("text") for n in nodes):
            return
        else:
            time.sleep(.3)
    viewer.find("关闭麦克风", scroll=True)


def run():
    if "record-start" in app_logs(viewer) and "record-stop" not in app_logs(viewer):
        raise RuntimeError("Microphone was already recording before the explicit voice action")
    for device in (owner, viewer):
        device.adb("logcat", "-c")
    enable()
    log("PASS: explicit microphone permission and visible foreground service")
    deadline = time.monotonic() + 20
    best_rms = 0.; best_tone = 0.
    while time.monotonic() < deadline:
        values = re.findall(r"playback rms=([0-9.]+) tone440=([0-9.]+)", app_logs(owner))
        for rms, tone in values:
            if float(rms) > 100 and float(tone) > .45:
                best_rms, best_tone = float(rms), float(tone)
                break
        if best_tone:
            break
        time.sleep(1)
    if not best_tone:
        raise RuntimeError("Expected 440 Hz tone was not found at shared Android speaker output: " + app_logs(owner)[-1000:])
    if "record-start" not in app_logs(viewer):
        raise RuntimeError("Android AudioRecord did not start")
    log(f"PASS: synthetic capture -> Opus/WebRTC -> decoded speaker output; rms={best_rms}, tone440={best_tone}")
    viewer.tap("关闭麦克风", scroll=True)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        services = viewer.shell("dumpsys", "activity", "services", "dev.screenguideqi")
        if ".voice.VoiceService" not in services and "record-stop" in app_logs(viewer):
            break
        time.sleep(.5)
    else:
        raise RuntimeError("AudioRecord or microphone service did not stop")
    log("PASS: disabling voice stops Android AudioRecord and removes its foreground service")
    enable()
    time.sleep(2)
    viewer.shell("cmd", "statusbar", "expand-notifications")
    # Android initially collapses silent foreground notifications and hides actions.
    nodes = viewer.nodes()
    notice = next(n for n in nodes if n.get("text") == "正在发送语音指导")
    notice_y = viewer.bounds(notice)[1]
    expands = [n for n in nodes if (n.get("text") or n.get("content-desc")) == "Expand" and viewer.bounds(n)[1] <= notice_y + 50]
    if expands:
        viewer.tap_node(max(expands, key=lambda n: viewer.bounds(n)[1]))
    viewer.tap("关闭麦克风")
    time.sleep(1)
    if ".voice.VoiceService" in viewer.shell("dumpsys", "activity", "services", "dev.screenguideqi"):
        raise RuntimeError("Notification stop did not end microphone service")
    viewer.shell("cmd", "statusbar", "collapse")
    log("PASS: repeat voice start and notification microphone stop")
    (OUT / "voice-result.json").write_text(json.dumps({"androidApi":34,"syntheticCapture":True,
        "hostMicrophoneUsed":False,"audioSaved":False,"speakerRms":best_rms,"tone440":best_tone,
        "recordStart":True,"recordStop":True,"notificationMicStop":True,"repeatVoice":True,
        "physicalMicrophoneTested":False},indent=2),encoding="utf-8")


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=("prepare","run"))
    args=parser.parse_args()
    try:
        prepare() if args.action == "prepare" else run()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
