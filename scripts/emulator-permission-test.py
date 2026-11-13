"""Verify first-use microphone consent on an already paired video session.

Start with RECORD_AUDIO revoked and permission flags cleared on the disposable
viewer emulator before pairing. The flag reset below only resets test UI history;
it does not grant the permission or bypass the subsequent system consent dialog.
"""
import json
import runpy
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
voice = runpy.run_path(str(ROOT / "scripts/emulator-voice-test.py"))
owner, viewer, log = voice["owner"], voice["viewer"], voice["log"]


def run():
    if "record-start" in voice["app_logs"](viewer):
        raise RuntimeError("Microphone started before consent")
    viewer.tap("开启语音指导", scroll=True)
    viewer.tap("DON’T ALLOW", timeout=15)
    viewer.find("开启语音指导", scroll=True)
    if ".voice.VoiceService" in viewer.shell("dumpsys", "activity", "services", "dev.screenguideqi"):
        raise RuntimeError("Microphone service started despite denied permission")
    if "record-start" in voice["app_logs"](viewer):
        raise RuntimeError("Microphone started despite denied permission")
    log("PASS: microphone denied, no recording, video continues")
    # Tests may inherit older denial history. Re-show the actual system dialog.
    viewer.shell("pm", "clear-permission-flags", "dev.screenguideqi", "android.permission.RECORD_AUDIO", "user-fixed", "user-set")
    voice["run"]()
    (ROOT / "build/emulator-test/permission-result.json").write_text(json.dumps({
        "noRecordingBeforeConsent": True, "denialDoesNotRecord": True,
        "grantAfterDenialVoiceWorks": True, "permissionGrantedViaSystemDialog": True,
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
