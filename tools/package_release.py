#!/usr/bin/env python3
"""Build and verify a signed release APK without exposing private signing material."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "build-tools/37.0.0"


def run(*args):
    return subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def validate(apk):
    badging = run(TOOLS / "aapt2", "dump", "badging", apk)
    assert "name='dev.ryuya.galaxycharge'" in badging and "targetSdkVersion:'37'" in badging
    assert "application-debuggable" not in badging and "android.permission.INTERNET" not in badging
    manifest = run(TOOLS / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    assert not any(s in manifest.lower() for s in ("testactivity", "probeactivity", "fixtureactivity", "instrumentation", "xposed"))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source-ref", default="HEAD")
    p.add_argument("--check-release-apk", type=Path)
    args = p.parse_args()
    if args.check_release_apk:
        validate(args.check_release_apk.resolve())
        print("Release APK structure verified")
        return
    run("git", "diff", "--exit-code", args.source_ref, "--", ".")
    assert not run("git", "ls-files", "--others", "--exclude-standard").strip(), "Commit sources first"
    print("Building release, tests, lint and dependency catalog", flush=True)
    run(ROOT / "gradlew", ":app:assembleRelease", ":app:testDebugUnitTest", ":app:lintRelease", ":app:writeRuntimeDependencyList")
    run("python3", "tools/legal_catalog.py", "--check")
    output = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((output / "output-metadata.json").read_text())
    assert metadata["variantName"] == "release" and len(metadata["elements"]) == 1
    item = metadata["elements"][0]
    version = item["versionName"]
    unsigned = output / item["outputFile"]
    validate(unsigned)
    signing = ROOT / ".signing"
    assert (signing / "release.keystore").is_file() and (signing / "password").is_file()
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    apk = dist / f"GalaxyChargeMonitor-{version}-release.apk"
    with tempfile.TemporaryDirectory() as temp:
        aligned = Path(temp) / "aligned.apk"
        run(TOOLS / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
        run(TOOLS / "apksigner", "sign", "--ks", signing / "release.keystore", "--ks-key-alias", "galaxycharge-release", "--ks-pass", "file:" + str(signing / "password"), "--debuggable-apk-permitted", "false", "--out", apk, aligned)
    signature = run(TOOLS / "apksigner", "verify", "--verbose", "--print-certs", apk)
    certificate = (ROOT / "release-certificate.sha256").read_text().strip()
    assert certificate in signature
    run(TOOLS / "zipalign", "-c", "-P", "16", "4", apk)
    validate(apk)
    proof = dist / f"GalaxyChargeMonitor-{version}-signature.txt"
    proof.write_text(signature)
    source = dist / f"GalaxyChargeMonitor-{version}-source.zip"
    run("git", "archive", "--format=zip", "--prefix=GalaxyChargeMonitor/", "--output=" + str(source), args.source_ref)
    sums = dist / f"SHA256SUMS-{version}.txt"
    sums.write_text("".join(hashlib.sha256(f.read_bytes()).hexdigest() + "  " + f.name + "\n" for f in [apk, source, proof]))
    print("Signed release verified: " + apk.name)


if __name__ == "__main__":
    main()
