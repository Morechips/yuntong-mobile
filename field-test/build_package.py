"""Package a clean Android revision and its verified side-by-side debug APK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
BUNDLE_FILES = (
    "开始测试.cmd", "field-test/Start.ps1", "field-test/Core.ps1",
    "field-test/Relay.cs", "field-test/README.md",
)


def capture(*args: str | Path) -> str:
    return subprocess.check_output(args, cwd=ROOT).decode("utf-8", errors="replace")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if capture("git", "status", "--porcelain").strip():
        parser.error("Commit the exact sources and test instructions before packaging.")

    metadata = json.loads((HERE / "round.json").read_text(encoding="utf-8"))
    apk = args.apk.resolve(strict=True)
    aapt = args.build_tools.resolve(strict=True) / ("aapt.exe" if os.name == "nt" else "aapt")
    badging = capture(aapt, "dump", "badging", apk)
    expected = (
        f"package: name='{metadata['packageName']}' versionCode='{metadata['versionCode']}' "
        f"versionName='{metadata['versionName']}'"
    )
    if not badging.startswith(expected):
        parser.error("APK application ID or version differs from round.json.")
    if re.search(r"uses-permission.*android\.permission\.(?:CALL_PHONE|SEND_SMS)", badging):
        parser.error("The field APK must not request direct call or SMS permissions.")
    if "application-label:'云瞳 · 联调测试'" not in badging:
        parser.error("The field APK title must identify the side-by-side test app.")

    signer = aapt.parent / "lib" / "apksigner.jar"
    java_name = "java.exe" if os.name == "nt" else "java"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / java_name if "JAVA_HOME" in os.environ else java_name
    signature = capture(java, "-jar", signer, "verify", "--print-certs", apk)
    digest = re.search(r"certificate SHA-256 digest: ([a-f0-9]{64})", signature)
    if not digest:
        parser.error("Unable to read the signing certificate fingerprint.")
    with apk.open("rb") as stream:
        metadata["apkSha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    metadata["signerSha256"] = digest.group(1)
    metadata["sourceCommit"] = capture("git", "rev-parse", "HEAD").strip()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.output, "x", compression=ZIP_DEFLATED) as bundle:
        for file in BUNDLE_FILES:
            source = (ROOT / file).read_bytes()
            if file.endswith(".cmd"):
                source = source.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
            bundle.writestr(file, source)
        bundle.write(apk, "field-test/yuntong-field-test.apk")
        bundle.writestr("field-test/round.json", json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(args.output.resolve())


if __name__ == "__main__":
    main()
