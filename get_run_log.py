import urllib.request, ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = "https://api.github.com/repos/nftdisk-cmyk/TestPlugins/actions/jobs/101658664813/logs"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
try:
    with urllib.request.urlopen(req, context=ctx, timeout=15) as r:
        lines = r.read().decode("utf-8", errors="ignore").splitlines()
        print("Total log lines:", len(lines))
        # Print lines around error
        for i, l in enumerate(lines):
            if any(k in l.lower() for k in ["error:", "e: ", "failed", "exception", "build failed"]):
                for sub in lines[max(0, i-5):min(len(lines), i+15)]:
                    print(sub)
                print("="*40)
                break
except urllib.error.HTTPError as e:
    print("HTTPError:", e.code, e.reason)
    print("Headers:", e.headers)
except Exception as e:
    print("Error:", e)
