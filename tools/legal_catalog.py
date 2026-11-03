#!/usr/bin/env python3
"""Generate or verify notices against the resolved runtime. Never assumes an unknown license."""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    check = parser.parse_args().check
    coordinates = sorted(set((ROOT / "app/build/runtime-dependencies.txt").read_text().splitlines()))
    if check:
        entries = json.loads((ASSETS / "legal/libraries.json").read_text())
        assert {e["id"] + ":" + e["version"] for e in entries if ":" in e["id"]} == set(coordinates)
        for e in entries:
            assert (ASSETS / e["asset"]).is_file()
        print(f"Legal catalog verified: {len(coordinates)} runtime components")
        return
    entries = [dict(id="project", title="Galaxy Charge Monitor", version="0.1.0", license="MIT", asset="legal/PROJECT-MIT.txt")]
    notices = []
    seen = set()

    def extract(archive, coordinate):
        for name in archive.namelist():
            if name.startswith("META-INF/") and any(x in name.rsplit("/", 1)[-1].lower() for x in ("license", "notice", "copyright")):
                try:
                    text = archive.read(name).decode().strip()
                except UnicodeError:
                    continue
                if text and text not in seen:
                    seen.add(text)
                    notices.append(coordinate + " / " + name + "\n\n" + text)
    def licenses(pom, depth=0):
        assert depth < 5, "License inheritance too deep"
        doc = ET.parse(pom)
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        found = [(e.findtext("m:name", "", ns) + " " + e.findtext("m:url", "", ns)).lower()
                 for e in doc.findall("./m:licenses/m:license", ns)]
        if found:
            return found
        parent = doc.find("./m:parent", ns)
        if parent is not None:
            parts = [parent.findtext("m:" + key, "", ns) for key in ["groupId", "artifactId", "version"]]
            assert all(parts) and not any("$" in part for part in parts)
            parents = list((CACHE / parts[0] / parts[1] / parts[2]).glob("*/*.pom"))
            assert parents, "Missing parent POM: " + ":".join(parts)
            return licenses(parents[0], depth + 1)
        return []

    for coordinate in coordinates:
        group, module, version = coordinate.split(":")
        folder = CACHE / group / module / version
        poms = list(folder.glob("*/*.pom"))
        assert poms, "Missing dependency license metadata: " + coordinate
        declared = []
        for pom in poms:
            declared.extend(licenses(pom))
        assert declared and all("apache" in name for name in declared), "Review license: " + coordinate
        for file in folder.glob("*/*"):
            if file.suffix in (".jar", ".aar") and not file.name.endswith("-sources.jar"):
                with ZipFile(file) as archive:
                    extract(archive, coordinate)
                    if "classes.jar" in archive.namelist():
                        with ZipFile(io.BytesIO(archive.read("classes.jar"))) as nested:
                            extract(nested, coordinate)
        entries.append(dict(id=group + ":" + module, title=module, version=version,
                            license="Apache License 2.0", asset="legal/APACHE-2.0.txt",
                            notice=coordinate + "\nCopyright belongs to the respective authors. See the bundled third-party notices."))
    entries.append(dict(id="notices", title="Third-party notices", version="", license="Bundled notices", asset="legal/THIRD-PARTY-NOTICES.txt"))
    (ASSETS / "legal/libraries.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n")
    (ASSETS / "legal/THIRD-PARTY-NOTICES.txt").write_text("\n\n".join(notices) + "\n")
    print(f"Generated {len(coordinates)} library entries and {len(seen)} embedded notices")


if __name__ == "__main__":
    main()
