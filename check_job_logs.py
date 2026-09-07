import urllib.request, json

# Get job ID
run_url = "https://api.github.com/repos/nftdisk-cmyk/TestPlugins/actions/runs/34095668925/jobs"
req = urllib.request.Request(run_url, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=10) as r:
    data = json.loads(r.read().decode("utf-8"))
    job = data["jobs"][0]
    job_id = job["id"]
    print("Job ID:", job_id)

# Try fetching job log
log_url = f"https://api.github.com/repos/nftdisk-cmyk/TestPlugins/actions/jobs/{job_id}/logs"
req_log = urllib.request.Request(log_url, headers={"User-Agent": "Mozilla/5.0"})
try:
    with urllib.request.urlopen(req_log, timeout=10) as lr:
        log_text = lr.read().decode("utf-8", errors="ignore")
        print("Log length:", len(log_text))
        for line in log_text.splitlines()[-50:]:
            print(line)
except Exception as e:
    print("Error fetching log:", e)
