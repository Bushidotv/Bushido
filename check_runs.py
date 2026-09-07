import urllib.request, json

url = "https://api.github.com/repos/nftdisk-cmyk/TestPlugins/actions/runs?per_page=5"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
try:
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.loads(r.read().decode("utf-8"))
        for run in data.get("workflow_runs", []):
            print(f"ID: {run['id']}, Conclusion: {run.get('conclusion')}, Status: {run.get('status')}")
            print(f"  Commit: {run.get('head_commit', {}).get('message', '')[:60]}")
            print(f"  HTML: {run.get('html_url')}")
            # If failed, get jobs
            if run.get('conclusion') == 'failure':
                jobs_url = run.get('jobs_url')
                req_jobs = urllib.request.Request(jobs_url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req_jobs, timeout=10) as jr:
                    jdata = json.loads(jr.read().decode("utf-8"))
                    for job in jdata.get("jobs", []):
                        print(f"  Job: {job['name']}, Conclusion: {job.get('conclusion')}")
                        for step in job.get("steps", []):
                            if step.get("conclusion") == "failure":
                                print(f"    Failed step: {step['name']}")
except Exception as e:
    print("Error:", e)
