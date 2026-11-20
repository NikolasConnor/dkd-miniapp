from pathlib import Path
import re
import subprocess
import tempfile

html = Path("index.html").read_text(encoding="utf-8")
scripts = re.findall(r"<script\b[^>]*>(.*?)</script\s*>", html, flags=re.IGNORECASE | re.DOTALL)
inline = [script for script in scripts if script.strip()]
if not inline:
    raise SystemExit("No inline JavaScript found in index.html")

for number, source in enumerate(inline, start=1):
    with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8") as file:
        file.write(source)
        file.flush()
        result = subprocess.run(["node", "--check", file.name], text=True, capture_output=True)
    if result.returncode:
        print(f"Inline script #{number} has a syntax error:")
        print(result.stderr or result.stdout)
        raise SystemExit(result.returncode)

print(f"JavaScript syntax OK ({len(inline)} inline script block(s)).")
