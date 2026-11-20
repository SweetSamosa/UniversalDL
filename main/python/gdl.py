"""gallery-dl bridge. Called from Kotlin through Chaquopy (see GalleryDl.kt).

gallery-dl is bundled at build time, but update() can fetch a newer pure-Python wheel from PyPI
into the app's HOME; _load() prefers that wheel and falls back to the bundled copy if it's broken.
"""
import hashlib
import io
import json
import logging
import os
import re
import sys
import urllib.request

_sink = None
_gd = None  # (config, exception, job) from the gallery_dl currently loaded


def _wheel():
    return os.path.join(os.environ.get("HOME", "."), "gallery_dl_update.whl")


def _purge():
    for name in [m for m in sys.modules if m == "gallery_dl" or m.startswith("gallery_dl.")]:
        del sys.modules[name]


def _import():
    from gallery_dl import config, exception, job
    return config, exception, job


def _load(fresh=False):
    global _gd
    if _gd is not None and not fresh:
        return _gd
    _purge()
    w = _wheel()
    sys.path_importer_cache.pop(w, None)
    if w in sys.path:
        sys.path.remove(w)
    if os.path.exists(w):
        sys.path.insert(0, w)
        try:
            _gd = _import()
            return _gd
        except Exception:
            # A broken update must never take the bundled copy down with it.
            sys.path.remove(w)
            _purge()
            os.remove(w)
    _gd = _import()
    return _gd


def version():
    _load()
    from gallery_dl import version as v
    return v.__version__


def _key(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def update():
    """Fetch the newest gallery-dl wheel from PyPI. Returns a status string."""
    with urllib.request.urlopen("https://pypi.org/pypi/gallery-dl/json", timeout=20) as r:
        meta = json.load(r)
    latest, current = meta["info"]["version"], version()
    if _key(latest) <= _key(current):
        return "gallery-dl %s is up to date" % current
    wheels = [f for f in meta["urls"]
              if f["packagetype"] == "bdist_wheel" and f["filename"].endswith("none-any.whl")]
    if not wheels:
        raise RuntimeError("no wheel published for gallery-dl %s" % latest)
    with urllib.request.urlopen(wheels[0]["url"], timeout=60) as r:
        data = r.read()
    if hashlib.sha256(data).hexdigest() != wheels[0]["digests"]["sha256"]:
        raise RuntimeError("gallery-dl download failed its checksum")
    tmp = _wheel() + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, _wheel())
    _load(fresh=True)
    return "gallery-dl %s -> %s" % (current, version())


class _Sink(io.TextIOBase):
    """Stands in for stdout while a job runs: gallery-dl prints one saved path per line."""

    def __init__(self, on_line):
        self.on_line = on_line
        self.cancelled = False
        self.buf = ""
        self.count = 0

    def writable(self):
        return True

    def write(self, s):
        if self.cancelled:
            raise KeyboardInterrupt()
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            line = line.strip()
            if line:
                if not line.startswith("#"):  # "# path" = already existed
                    self.count += 1
                self.on_line.accept(line)
        return len(s)

    def flush(self):
        pass


class _LastError(logging.Handler):
    def __init__(self):
        super().__init__(logging.WARNING)
        self.last = ""

    def emit(self, record):
        self.last = record.getMessage()


def cancel():
    if _sink is not None:
        _sink.cancelled = True


def run(url, dest, cookies, on_line):
    """Download everything at `url` flat into `dest`.

    Returns the number of files saved, or -1 if cancelled.
    Raises RuntimeError when nothing was saved and gallery-dl reported a problem.
    """
    global _sink
    config, exception, job = _load()
    config.clear()
    config.set(("extractor",), "base-directory", dest)
    config.set(("extractor",), "directory", [])      # no per-site subfolders
    config.set(("extractor",), "path-restrict", "windows")  # no ':' etc. in file names
    config.set(("output",), "mode", "pipe")           # plain "path per line" output
    config.set(("downloader",), "retries", 3)
    config.set(("extractor",), "user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
    if cookies:
        config.set(("extractor",), "cookies", cookies)

    err = _LastError()
    root = logging.getLogger()
    root.addHandler(err)
    old_stdout, _sink = sys.stdout, _Sink(on_line)
    sys.stdout = _sink
    try:
        status = job.DownloadJob(url).run()
    except KeyboardInterrupt:
        return -1
    except exception.NoExtractorError:
        raise RuntimeError("gallery-dl doesn't support this link")
    finally:
        sys.stdout = old_stdout
        root.removeHandler(err)
    if _sink.count == 0 and status:
        raise RuntimeError(err.last or "gallery-dl failed (status %d)" % status)
    return _sink.count
