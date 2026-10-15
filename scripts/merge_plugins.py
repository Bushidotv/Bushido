import os
import sys
import json
import zipfile
import hashlib

def get_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def merge_plugins(builds_dir):
    plugins_json_path = os.path.join(builds_dir, "plugins.json")
    plugins = []
    if os.path.exists(plugins_json_path):
        try:
            with open(plugins_json_path, "r", encoding="utf-8") as f:
                content = json.load(f)
                if isinstance(content, list):
                    plugins = content
                elif isinstance(content, dict) and "value" in content:
                    plugins = content["value"]
        except Exception as e:
            print(f"Error reading plugins.json: {e}")

    # Index existing by internalName or name
    existing_map = {}
    for p in plugins:
        key = p.get("internalName") or p.get("name")
        if key:
            existing_map[key] = p

    cs3_files = [f for f in os.listdir(builds_dir) if f.endswith(".cs3")]
    print(f"Found .cs3 files in {builds_dir}: {cs3_files}")

    for cs3_name in cs3_files:
        base_name = os.path.splitext(cs3_name)[0]
        cs3_path = os.path.join(builds_dir, cs3_name)
        file_size = os.path.getsize(cs3_path)
        file_hash = f"sha256-{get_sha256(cs3_path)}"

        manifest = {}
        try:
            with zipfile.ZipFile(cs3_path, "r") as z:
                if "manifest.json" in z.namelist():
                    manifest = json.loads(z.read("manifest.json").decode("utf-8"))
        except Exception as e:
            print(f"Error reading manifest from {cs3_name}: {e}")

        if base_name in existing_map:
            # Update hash and size
            entry = existing_map[base_name]
            entry["fileSize"] = file_size
            entry["fileHash"] = file_hash
            if "version" in manifest and manifest["version"]:
                entry["version"] = manifest["version"]
            print(f"Updated existing entry: {base_name}")
        else:
            # Create new entry
            name = manifest.get("name") or base_name
            entry = {
                "name": name,
                "internalName": base_name,
                "version": manifest.get("version", 1),
                "url": f"https://raw.githubusercontent.com/Bushidotv/Bushido/builds/{cs3_name}",
                "fileSize": file_size,
                "fileHash": file_hash,
                "authors": ["BushidoTV"],
                "repositoryUrl": "https://github.com/Bushidotv/Bushido",
                "iconUrl": "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png",
                "description": manifest.get("description", f"{name} Eklentisi"),
                "language": manifest.get("language", "tr"),
                "tvTypes": manifest.get("tvTypes", ["Documentary", "Movie", "TvSeries"]),
                "status": 1,
                "apiVersion": 1
            }
            plugins.append(entry)
            existing_map[base_name] = entry
            print(f"Added new plugin entry: {base_name}")

    with open(plugins_json_path, "w", encoding="utf-8") as f:
        json.dump(plugins, f, indent=4, ensure_ascii=False)
    print(f"Successfully saved {len(plugins)} plugins to {plugins_json_path}")

    # Ensure repo.json exists and is valid
    repo_json_path = os.path.join(builds_dir, "repo.json")
    repo_data = {
        "name": "Bushido",
        "description": "Bushido CloudStream Eklenti Havuzu",
        "manifestVersion": 1,
        "pluginLists": [
            "https://raw.githubusercontent.com/Bushidotv/Bushido/builds/plugins.json"
        ]
    }
    with open(repo_json_path, "w", encoding="utf-8") as f:
        json.dump(repo_data, f, indent=4, ensure_ascii=False)
    print(f"Successfully saved repo.json to {repo_json_path}")

if __name__ == "__main__":
    target_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    merge_plugins(target_dir)
