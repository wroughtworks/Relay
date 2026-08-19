#!/usr/bin/env python3
"""Development helper for the Relay proxy.

Every check in here exists because the corresponding mistake cost real debugging
time. The `doctor` command is the important one: it cross-checks Relay's config
against each backend's Paper config and reports the mismatches that produce
silent, misleading failures --- a forwarding secret that differs by one
character, a PROXY protocol setting that agrees on one side only, a backend left
in online mode.

Stdlib only, no install step. Run `py relay.py` for the command list.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - Python < 3.11
    tomllib = None

PROJECT = Path(__file__).resolve().parent
JAR_GLOB = "proxy-*.jar"
JAR_DIR = PROJECT / "proxy" / "build" / "libs"
PLUGIN_JAR_DIR = PROJECT / "paper-plugin" / "build" / "libs"
PLUGIN_JAR_GLOB = "Relay-*.jar"
CONFIG = PROJECT / "relay.toml"
DEV_CONFIG = PROJECT / "relay-dev.json"
SOURCE_DIRS = [
    PROJECT / "proxy" / "src",
    PROJECT / "proxy" / "build.gradle.kts",
    PROJECT / "build.gradle.kts",
    PROJECT / "settings.gradle.kts",
]

IS_WINDOWS = os.name == "nt"


# --------------------------------------------------------------------------- output


class Style:
    """ANSI codes, disabled when the output is not a terminal."""

    enabled = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None

    @classmethod
    def _wrap(cls, code: str, text: str) -> str:
        return f"\033[{code}m{text}\033[0m" if cls.enabled else text

    @classmethod
    def bold(cls, text: str) -> str:
        return cls._wrap("1", text)

    @classmethod
    def dim(cls, text: str) -> str:
        return cls._wrap("2", text)

    @classmethod
    def green(cls, text: str) -> str:
        return cls._wrap("32", text)

    @classmethod
    def yellow(cls, text: str) -> str:
        return cls._wrap("33", text)

    @classmethod
    def red(cls, text: str) -> str:
        return cls._wrap("31", text)

    @classmethod
    def cyan(cls, text: str) -> str:
        return cls._wrap("36", text)


def heading(text: str) -> None:
    print()
    print(Style.bold(text))
    print(Style.dim("-" * len(text)))


# --------------------------------------------------------------------------- checks

OK, WARN, FAIL, INFO = "ok", "warn", "fail", "info"


@dataclass
class Check:
    """One diagnostic result, with the fix attached rather than left implicit."""

    status: str
    label: str
    detail: str = ""
    fix: str = ""


@dataclass
class Report:
    """
    Collects results and prints each as it arrives.

    Printing immediately rather than at the end keeps checks under the heading
    they belong to, and means a slow check (a port probe against a host that is
    down) shows progress instead of a pause.
    """

    checks: list[Check] = field(default_factory=list)

    MARKS = {
        OK: lambda: Style.green("  OK  "),
        WARN: lambda: Style.yellow(" WARN "),
        FAIL: lambda: Style.red(" FAIL "),
        INFO: lambda: Style.cyan("  ..  "),
    }

    def add(self, status: str, label: str, detail: str = "", fix: str = "") -> None:
        check = Check(status, label, detail, fix)
        self.checks.append(check)
        print(f"[{self.MARKS[status]()}] {label}")
        if detail:
            print(f"          {Style.dim(detail)}")
        for line in fix.splitlines():
            print(f"          {Style.yellow('fix:')} {line}")

    def ok(self, label: str, detail: str = "") -> None:
        self.add(OK, label, detail)

    def warn(self, label: str, detail: str = "", fix: str = "") -> None:
        self.add(WARN, label, detail, fix)

    def fail(self, label: str, detail: str = "", fix: str = "") -> None:
        self.add(FAIL, label, detail, fix)

    def info(self, label: str, detail: str = "") -> None:
        self.add(INFO, label, detail)

    def render(self) -> int:
        failures = sum(1 for c in self.checks if c.status == FAIL)
        warnings = sum(1 for c in self.checks if c.status == WARN)
        print()
        if failures:
            print(Style.red(f"{failures} problem(s) that will stop players connecting."))
        elif warnings:
            print(Style.yellow(f"{warnings} warning(s), nothing fatal."))
        else:
            print(Style.green("Everything checks out."))
        return 1 if failures else 0


# --------------------------------------------------------------------------- parsing


def read_text(path: Path) -> str:
    """Reads UTF-8, tolerating the byte order mark Windows editors add."""
    text = path.read_text(encoding="utf-8-sig")
    return text


def load_toml(path: Path) -> dict:
    if tomllib is not None:
        return tomllib.loads(read_text(path))
    return _minimal_toml(read_text(path))


def _minimal_toml(text: str) -> dict:
    """Enough TOML for relay.toml if tomllib is unavailable: scalars and tables."""
    root: dict = {}
    table = root
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            name = line.strip("[]").strip()
            table = root
            for part in name.split("."):
                table = table.setdefault(part, {})
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        table[key.strip()] = _coerce_scalar(value.strip())
    return root


def _coerce_scalar(value: str):
    value = value.split(" #")[0].strip()
    if value.startswith(("'", '"')) and value.endswith(("'", '"')) and len(value) >= 2:
        return value[1:-1]
    if value.startswith("["):
        inner = value.strip("[]").strip()
        if not inner:
            return []
        return [_coerce_scalar(v.strip()) for v in inner.split(",")]
    lowered = value.lower()
    if lowered in ("true", "false"):
        return lowered == "true"
    try:
        return int(value, 0)
    except ValueError:
        return value


def load_simple_yaml(path: Path) -> dict:
    """
    An indent-aware reader for the handful of scalar keys needed from
    paper-global.yml. Deliberately not a YAML implementation: lists and anchors
    are ignored, because nothing this tool looks at uses them.
    """
    root: dict = {}
    stack: list[tuple[int, dict]] = [(-1, root)]
    for raw in read_text(path).splitlines():
        if not raw.strip() or raw.lstrip().startswith("#") or raw.lstrip().startswith("- "):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        while stack[-1][0] >= indent:
            stack.pop()
        parent = stack[-1][1]
        if value == "":
            child: dict = {}
            parent[key] = child
            stack.append((indent, child))
        else:
            parent[key] = _coerce_scalar(value)
    return root


def load_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in read_text(path).splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def dig(data: dict, *path: str):
    for key in path:
        if not isinstance(data, dict) or key not in data:
            return None
        data = data[key]
    return data


def fingerprint(secret: str) -> str:
    """
    The same 8-character hash Relay prints at startup.

    Lets two secrets be compared without either being shown, which matters when
    the whole point is that they look identical but are not.
    """
    return hashlib.sha256(secret.encode("utf-8")).digest()[:4].hex()


# --------------------------------------------------------------------------- processes


@dataclass
class Process:
    pid: int
    command: str
    #: Unix timestamp the process started, or None where it could not be read.
    started: float | None = None


def running_processes() -> list[Process]:
    if IS_WINDOWS:
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                "Select-Object ProcessId,CommandLine,CreationDate | ConvertTo-Json -Compress",
            ],
            capture_output=True, text=True,
        )
        if result.returncode != 0 or not result.stdout.strip():
            return []
        try:
            data = json.loads(result.stdout)
        except json.JSONDecodeError:
            return []
        if isinstance(data, dict):
            data = [data]
        return [
            Process(int(item["ProcessId"]), item.get("CommandLine") or "",
                    _parse_cim_date(item.get("CreationDate")))
            for item in data if item.get("ProcessId")
        ]

    result = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True)
    processes = []
    for line in result.stdout.splitlines()[1:]:
        pid, _, command = line.strip().partition(" ")
        if pid.isdigit():
            processes.append(Process(int(pid), command))
    return processes


def _parse_cim_date(value) -> float | None:
    """
    Turns a WMI CreationDate into a timestamp.

    PowerShell renders it either as a WMI string (``20260818010348.123456+000``) or,
    once it has been through ConvertTo-Json, as ``/Date(1755478000000)/``.
    """
    if not value:
        return None
    text = str(value)
    if text.startswith("/Date("):
        digits = text[6:].split(")")[0].split("+")[0].split("-")[0]
        return int(digits) / 1000 if digits.lstrip("-").isdigit() else None
    try:
        return time.mktime(time.strptime(text[:14], "%Y%m%d%H%M%S"))
    except ValueError:
        return None


# Companions are Relay processes too, and live under the same directory, so a
# path match finds them. They must not be treated as proxies: killing one
# directly skips the goodbye the proxy sends over the control channel, and the
# supervisor would simply start it again a second later.
COMPANION_MARKERS = ("relay-dashboard", "relay-discord")


def relay_processes() -> list[Process]:
    """
    Java processes running the Relay proxy jar.

    Matched narrowly on purpose: the Gradle daemon, the Paper servers and the
    proxy's own companions are all java processes under this directory, and
    stopping any of them here would be a bad surprise.
    """
    found = []
    for process in running_processes():
        command = process.command.lower()
        if "gradle" in command or "server.jar" in command:
            continue
        if any(marker in command for marker in COMPANION_MARKERS):
            continue
        # Matched on the path rather than the jar name: the artifact has been called
        # both relay-*.jar and proxy-*.jar, and a name-only match silently stopped
        # recognising a running proxy when it was renamed.
        if "dev.relay.relaybootstrap" in command:
            found.append(process)
        elif re.search(r"\.jar", command) and ("relay" in command or "proxy" in command):
            found.append(process)
    return found


def kill(pid: int) -> bool:
    if IS_WINDOWS:
        result = subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
        return result.returncode == 0
    try:
        os.kill(pid, 15)
        return True
    except OSError:
        return False


# --------------------------------------------------------------------------- build


def jar_path() -> Path | None:
    if not JAR_DIR.is_dir():
        return None
    jars = sorted(JAR_DIR.glob(JAR_GLOB), key=lambda p: p.stat().st_mtime, reverse=True)
    return jars[0] if jars else None


def plugin_jar_path() -> Path | None:
    if not PLUGIN_JAR_DIR.is_dir():
        return None
    jars = sorted(PLUGIN_JAR_DIR.glob(PLUGIN_JAR_GLOB), key=lambda p: p.stat().st_mtime, reverse=True)
    return jars[0] if jars else None


def newest_source_mtime() -> float:
    newest = 0.0
    for root in SOURCE_DIRS:
        if root.is_file():
            newest = max(newest, root.stat().st_mtime)
        elif root.is_dir():
            for path in root.rglob("*"):
                if path.is_file():
                    newest = max(newest, path.stat().st_mtime)
    return newest


def jar_is_stale() -> bool:
    jar = jar_path()
    return jar is None or jar.stat().st_mtime < newest_source_mtime()


def gradle_command() -> list[str]:
    wrapper = PROJECT / ("gradlew.bat" if IS_WINDOWS else "gradlew")
    if wrapper.exists():
        return [str(wrapper)]
    if shutil.which("gradle"):
        return ["gradle"]
    print(Style.red("Neither the Gradle wrapper nor a gradle command was found."))
    sys.exit(1)


def run_gradle(tasks: list[str]) -> int:
    command = gradle_command() + tasks + ["--console=plain"]
    print(Style.dim("$ " + " ".join(command)))
    return subprocess.run(command, cwd=PROJECT).returncode


# --------------------------------------------------------------------------- minecraft


def write_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(stream) -> int:
    result = shift = 0
    while shift < 35:
        chunk = stream.recv(1)
        if not chunk:
            raise ConnectionError("connection closed while reading a VarInt")
        byte = chunk[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7
    raise ValueError("VarInt too wide")


def status_ping(host: str, port: int, protocol: int = 764, timeout: float = 5.0) -> dict:
    """A real server-list ping, so `ping` proves the proxy answers rather than just listens."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        host_bytes = host.encode("utf-8")
        handshake = (
            b"\x00" + write_varint(protocol)
            + write_varint(len(host_bytes)) + host_bytes
            + struct.pack(">H", port) + write_varint(1)
        )
        sock.sendall(write_varint(len(handshake)) + handshake)
        sock.sendall(write_varint(1) + b"\x00")

        length = read_varint(sock)
        payload = b""
        while len(payload) < length:
            chunk = sock.recv(length - len(payload))
            if not chunk:
                raise ConnectionError("connection closed mid-response")
            payload += chunk

        offset = 0
        packet_id = payload[offset]
        offset += 1
        if packet_id != 0x00:
            raise ValueError(f"expected a status response, got packet 0x{packet_id:02x}")

        json_length = shift = 0
        while True:
            byte = payload[offset]
            offset += 1
            json_length |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
        return json.loads(payload[offset:offset + json_length].decode("utf-8"))


def port_open(host: str, port: int, timeout: float = 1.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


# --------------------------------------------------------------------------- dev config


def load_dev_config() -> dict:
    if DEV_CONFIG.exists():
        return json.loads(read_text(DEV_CONFIG))
    return {}


def save_dev_config(data: dict) -> None:
    DEV_CONFIG.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def backends() -> dict[str, dict]:
    """
    Linked backends, normalised.

    An entry may be a bare path string from an older config, or an object with
    launch settings; both are accepted so linking again is never required.
    """
    result = {}
    for name, value in load_dev_config().get("paper_servers", {}).items():
        result[name] = {"path": value} if isinstance(value, str) else dict(value)
    return result


def backend(name: str) -> dict | None:
    return backends().get(name)


def paper_dirs() -> dict[str, Path]:
    return {name: Path(entry["path"]) for name, entry in backends().items()}


def resolve_names(name: str | None) -> list[str]:
    """Expands 'all' or a missing name to every linked backend."""
    known = list(backends())
    if name in (None, "all"):
        if not known:
            print(Style.red("No backends are linked."))
            print("  py relay.py link lobby <path-to-paper-server>")
        return known
    if name not in known:
        print(Style.red(f"No backend named '{name}'. Linked: {', '.join(known) or 'none'}"))
        return []
    return [name]


# --------------------------------------------------------------------------- backends

RUN_DIR = PROJECT / ".relay-run"


def pid_file(name: str) -> Path:
    return RUN_DIR / f"{name}.pid"


def backend_pid(name: str) -> int | None:
    """
    The running process for this backend, if there is one.

    Located by the marker stamped onto its command line rather than by a pid
    file, because a terminal that opens tabs spawns the real java process as a
    grandchild and the pid recorded at launch belongs to the launcher.
    """
    marker = f"{BACKEND_MARKER}{name}"
    for process in running_processes():
        # Exact match: a backend called "lobby" must not match "lobby2".
        if marker in process.command:
            tail = process.command.split(marker, 1)[1]
            if not tail or tail[0] in " 	\"'":
                return process.pid

    path = pid_file(name)
    if path.exists():
        try:
            pid = int(path.read_text().strip())
        except ValueError:
            pid = None
        if pid is not None:
            for process in running_processes():
                if process.pid == pid:
                    return pid
        # Stale: the server exited without the file being cleaned up.
        path.unlink(missing_ok=True)
    return None


def find_server_jar(directory: Path) -> Path | None:
    for candidate in ("server.jar", "paper.jar"):
        if (directory / candidate).exists():
            return directory / candidate
    jars = sorted(directory.glob("*.jar"))
    for jar in jars:
        if "paper" in jar.name.lower() or "server" in jar.name.lower():
            return jar
    return jars[0] if jars else None


#: How a backend's console is presented.
#:   foreground - takes over this terminal; only one server at a time
#:   tabs       - one Windows Terminal window, a tab per backend; navigate with
#:                Ctrl+Tab, and each tab is a real console that accepts `stop`
#:   console    - a separate window per backend
#:   background - detached, output to a file, nothing to type into
FOREGROUND, TABS, CONSOLE, BACKGROUND = "foreground", "tabs", "console", "background"

#: Stamped onto every backend the script launches, so its process can be found
#: again regardless of how it was started. A window title or a pid file does not
#: survive being launched through a terminal that spawns the real process as a
#: grandchild, but a system property is right there in the command line.
BACKEND_MARKER = "-Drelay.backend="


def start_backend(name: str, entry: dict, debug: bool, mode: str) -> int:
    directory = Path(entry["path"])
    if not directory.is_dir():
        print(Style.red(f"'{name}': {directory} does not exist"))
        return 1

    if backend_pid(name):
        print(Style.yellow(f"'{name}' is already running (PID {backend_pid(name)})"))
        return 0

    jar = Path(entry["jar"]) if entry.get("jar") else find_server_jar(directory)
    if jar is None or not jar.exists():
        print(Style.red(f"'{name}': no server jar found in {directory}"))
        print(Style.dim("  set one with: py relay.py link "
                        f"{name} {directory} --jar <file>"))
        return 1

    # Paper exits immediately with a bare message if the EULA has not been
    # accepted, which reads like a crash if you are not expecting it.
    eula = directory / "eula.txt"
    if eula.exists() and "eula=true" not in read_text(eula).lower():
        print(Style.red(f"'{name}': eula.txt says eula=false; Paper will exit at once"))
        return 1

    java = entry.get("java", "java")
    memory = entry.get("memory", "2G")
    command = [java, f"-Xmx{memory}", f"{BACKEND_MARKER}{name}"]
    if debug:
        config = directory / "log4j2-debug.xml"
        if not config.exists():
            write_paper_debug_config(config)
            print(Style.dim(f"  wrote {config.name}"))
        command.append("-Dlog4j.configurationFile=log4j2-debug.xml")
    command += entry.get("flags", [])
    command += ["-jar", jar.name, "--nogui"]

    RUN_DIR.mkdir(exist_ok=True)
    print(Style.dim(f"$ {' '.join(command)}"))
    print(Style.dim(f"  cwd {directory}"))

    if mode == FOREGROUND:
        # Takes over this terminal, giving the Paper console directly: typing
        # `stop` there shuts the server down properly and saves the world.
        return subprocess.run(command, cwd=directory).returncode

    if mode == TABS:
        if windows_terminal() is None:
            print(Style.yellow("  Windows Terminal not found; opening a separate window instead"))
            return start_backend(name, entry, debug, CONSOLE)
        # One tab in the shared window. wt returns as soon as the tab is created,
        # so the java process is a grandchild and is found by its marker rather
        # than by the pid wt would report.
        subprocess.Popen(open_tab_command(name, directory, command))
        print(Style.green(f"'{name}' opened as a tab"))
        return 0

    if mode == CONSOLE:
        if not IS_WINDOWS:
            terminal = find_terminal_emulator()
            if terminal is None:
                print(Style.yellow("  no terminal emulator found; running in the background instead"))
                return start_backend(name, entry, debug, BACKGROUND)
            process = subprocess.Popen(terminal + command, cwd=directory)
        else:
            # A new console, with no redirection, so the window owns the server's
            # stdio: output is visible live and the window accepts typed commands.
            process = subprocess.Popen(
                command, cwd=directory, creationflags=subprocess.CREATE_NEW_CONSOLE)

        pid_file(name).write_text(str(process.pid))
        print(Style.green(f"'{name}' started in its own console window as PID {process.pid}"))
        print(Style.dim(f"  type 'stop' in that window to shut it down cleanly"))
        print(Style.dim(f"  history is also written to {directory / 'logs' / 'latest.log'}"))
        return 0

    log = RUN_DIR / f"{name}.log"
    handle = log.open("ab")
    process = subprocess.Popen(
        command, cwd=directory, stdout=handle, stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if IS_WINDOWS else 0,
    )
    pid_file(name).write_text(str(process.pid))
    print(Style.green(f"'{name}' started in the background as PID {process.pid}"))
    print(Style.dim(f"  log: {log}"))
    print(Style.dim("  detached, so there is nothing to type into; --console gives a window"))
    return 0


def windows_terminal() -> str | None:
    """Windows Terminal, which is what provides tabs. Ships with Windows 11."""
    if not IS_WINDOWS:
        return None
    found = shutil.which("wt.exe") or shutil.which("wt")
    if found:
        return found
    bundled = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WindowsApps" / "wt.exe"
    return str(bundled) if bundled.exists() else None


#: The shared window's name. `wt --window <name>` opens it if it does not exist
#: and reuses it if it does, so every backend lands in the same window without
#: this script having to track which tab was first.
TAB_WINDOW = "relay"


def open_tab_command(title: str, directory: Path, command: list[str]) -> list[str]:
    """A `wt` invocation placing one server in a tab of the shared window."""
    return [windows_terminal(), "--window", TAB_WINDOW,
            "new-tab", "--title", title, "-d", str(directory)] + command


def find_terminal_emulator() -> list[str] | None:
    """A command that opens `argv` in a new terminal window, on Linux or macOS."""
    for terminal, prefix in (
        ("x-terminal-emulator", ["x-terminal-emulator", "-e"]),
        ("gnome-terminal", ["gnome-terminal", "--"]),
        ("konsole", ["konsole", "-e"]),
        ("xfce4-terminal", ["xfce4-terminal", "-x"]),
        ("xterm", ["xterm", "-e"]),
    ):
        if shutil.which(terminal):
            return prefix
    return None


def rcon_command(host: str, port: int, password: str, command: str, timeout: float = 5.0) -> str:
    """
    Runs one command over RCON, Minecraft's remote console protocol.

    Packets are little-endian: length, request id, type, NUL-terminated body,
    then a second NUL. Type 3 authenticates, type 2 runs a command, and an auth
    response carrying id -1 means the password was rejected.
    """

    def packet(request_id: int, packet_type: int, body: str) -> bytes:
        payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def read_packet(sock) -> tuple[int, str]:
        header = b""
        while len(header) < 4:
            chunk = sock.recv(4 - len(header))
            if not chunk:
                raise ConnectionError("RCON closed while reading a length")
            header += chunk
        length = struct.unpack("<i", header)[0]
        payload = b""
        while len(payload) < length:
            chunk = sock.recv(length - len(payload))
            if not chunk:
                raise ConnectionError("RCON closed mid-packet")
            payload += chunk
        request_id, _ = struct.unpack("<ii", payload[:8])
        return request_id, payload[8:-2].decode("utf-8", errors="replace")

    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(packet(1, 3, password))
        request_id, _ = read_packet(sock)
        if request_id == -1:
            raise PermissionError("RCON password rejected")
        sock.sendall(packet(2, 2, command))
        _, body = read_packet(sock)
        return body


def rcon_settings(entry: dict) -> tuple[int, str] | None:
    """RCON port and password from server.properties, if it is switched on."""
    properties_path = Path(entry["path"]) / "server.properties"
    if not properties_path.exists():
        return None
    properties = load_properties(properties_path)
    if properties.get("enable-rcon", "false").lower() != "true":
        return None
    port = properties.get("rcon.port", "25575")
    password = properties.get("rcon.password", "")
    if not port.isdigit() or not password:
        return None
    return int(port), password


def stop_backend(name: str, force: bool = False, timeout: float = 60.0) -> int:
    """
    Stops a backend, preferring a clean shutdown.

    Windows has no SIGTERM, and `taskkill` without /F only reaches windowed
    programs, so a detached Java process cannot be asked politely to stop. RCON
    is the one channel that actually works remotely, and it is what a real `stop`
    at the console does. Without it the only option is a hard kill, which skips
    Paper's shutdown hook and can lose recently generated chunks -- so that path
    demands --force rather than happening by default.
    """
    pid = backend_pid(name)
    if pid is None:
        print(f"'{name}' is not running (or was not started by this script).")
        return 0

    entry = backend(name) or {}
    rcon = rcon_settings(entry)

    if rcon is not None:
        port, password = rcon
        print(f"Stopping '{name}' (PID {pid}) over RCON...")
        try:
            rcon_command("127.0.0.1", port, password, "stop")
        except Exception as error:  # noqa: BLE001
            print(Style.yellow(f"  RCON failed: {error}"))
        else:
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if backend_pid(name) is None:
                    print(Style.green(f"'{name}' shut down cleanly and saved."))
                    pid_file(name).unlink(missing_ok=True)
                    return 0
                time.sleep(0.5)
            print(Style.yellow(f"  still running after {timeout:.0f}s"))
    else:
        print(Style.yellow(f"'{name}' has no RCON, so there is no clean way to stop it remotely."))

    if not force:
        print()
        print("Refusing to force-kill: that skips Paper's shutdown hook and can lose")
        print("recently generated chunks. Pick one:")
        print(Style.cyan("  1. Type 'stop' in the server's console window"))
        print(Style.dim("     (started with --console or in the foreground, there is one)"))
        print(Style.cyan(f"  2. Enable RCON in {Path(entry.get('path', '?')) / 'server.properties'}:"))
        print(Style.dim("       enable-rcon=true"))
        print(Style.dim("       rcon.port=25575"))
        print(Style.dim("       rcon.password=<something>"))
        print(Style.cyan(f"  3. py relay.py shutdown {name} --force   (accepts the risk)"))
        return 1

    print(Style.yellow("Forcing; the world may not be saved."))
    kill(pid)
    pid_file(name).unlink(missing_ok=True)
    return 0


def write_paper_debug_config(target: Path) -> None:
    """
    Writes a log4j2 config that turns on Paper's packet-level logging.

    Keeps a file appender alongside the console one. A console-only config looks
    fine while you are watching the window, but the output vanishes with the tab
    and cannot be read back afterwards --- which is useless for the very case
    this exists to diagnose.
    """
    target.write_text(
        """<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="[%d{HH:mm:ss} %level]: %msg%n%throwable"/>
    </Console>
    <!-- Kept so the trace survives the window being closed. -->
    <RollingRandomAccessFile name="File" fileName="logs/latest.log"
                             filePattern="logs/%d{yyyy-MM-dd}-%i.log.gz">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg%n%throwable"/>
      <Policies>
        <TimeBasedTriggeringPolicy/>
        <OnStartupTriggeringPolicy/>
      </Policies>
    </RollingRandomAccessFile>
  </Appenders>
  <Loggers>
    <!-- Packet-level tracing, plus the pipeline exceptions Paper otherwise swallows. -->
    <Logger name="net.minecraft.network" level="DEBUG"/>
    <Logger name="io.netty" level="DEBUG"/>
    <Root level="INFO">
      <AppenderRef ref="Console"/>
      <AppenderRef ref="File"/>
    </Root>
  </Loggers>
</Configuration>
""",
        encoding="utf-8",
    )


def backend_port(entry: dict) -> int | None:
    properties = Path(entry["path"]) / "server.properties"
    if not properties.exists():
        return None
    value = load_properties(properties).get("server-port")
    return int(value) if value and value.isdigit() else None


def wait_for_port(host: str, port: int, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if port_open(host, port, timeout=1.0):
            return True
        time.sleep(1.0)
    return False


# --------------------------------------------------------------------------- commands


def cmd_doctor(args) -> int:
    """Cross-check Relay's config against each backend's Paper config."""
    report = Report()

    heading("Relay")

    if not CONFIG.exists():
        report.fail("relay.toml is missing", f"looked in {CONFIG}",
                    "Run `py relay.py run` once; Relay writes a starter config on first start.")
        return report.render()

    raw = CONFIG.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        report.warn("relay.toml starts with a byte order mark",
                    "Relay strips it, but other tools may not.",
                    "Re-save as UTF-8 without a BOM.")

    try:
        config = load_toml(CONFIG)
    except Exception as error:  # noqa: BLE001 - surfaced to the user verbatim
        report.fail("relay.toml does not parse", str(error))
        return report.render()

    report.ok("relay.toml parses", str(CONFIG))

    mode = config.get("forwarding-mode", "modern")
    online = config.get("online-mode", True)
    send_proxy = config.get("proxy-protocol-send", False)
    secret = str(config.get("forwarding-secret", "") or "")

    report.info(f"forwarding={mode}  online-mode={online}  proxy-protocol-send={send_proxy}")

    relay_fp = None
    if mode == "modern":
        if not secret:
            report.fail("forwarding-mode is 'modern' but no forwarding-secret is set",
                        fix="Copy the secret from your backend's paper-global.yml.")
        elif secret != secret.strip():
            report.warn("forwarding-secret has surrounding whitespace",
                        "Relay trims it; the backend will not.",
                        "Remove the stray space or newline.")
        else:
            relay_fp = fingerprint(secret)
            report.ok("forwarding-secret loaded", f"fingerprint {relay_fp}")

    servers = config.get("servers", {}) or {}
    if not servers:
        report.fail("no backends defined under [servers]")
    for name, address in servers.items():
        host, _, port = str(address).rpartition(":")
        host = host.strip("[]") or "127.0.0.1"
        if not port.isdigit():
            report.fail(f"backend '{name}' has a malformed address", str(address))
            continue
        if port_open(host, int(port)):
            report.ok(f"backend '{name}' is listening", f"{host}:{port}")
        else:
            report.fail(f"backend '{name}' is not reachable", f"{host}:{port}",
                        "Start it, or correct the address in relay.toml.")

    # ------------------------------------------------------------------ paper
    configured = paper_dirs()
    if not configured:
        heading("Backend cross-checks")
        report.warn("no Paper directories configured, so cross-checks are skipped",
                    "This is where secret and PROXY mismatches are caught.",
                    "py relay.py link lobby <path-to-paper-server>")
    for name, directory in configured.items():
        heading(f"Backend: {name}")
        if not directory.is_dir():
            report.fail(f"'{name}' directory does not exist", str(directory))
            continue

        if rcon_settings(entry_for_doctor := {"path": str(directory)}) is None:
            report.warn(f"'{name}' has RCON disabled",
                        "Without it a background server can only be force-killed.",
                        "enable-rcon=true, rcon.port=25575, rcon.password=<something> "
                        "in server.properties")
        else:
            report.ok(f"'{name}' has RCON enabled", "clean background shutdown available")

        eula = directory / "eula.txt"
        if eula.exists() and "eula=true" not in read_text(eula).lower():
            report.fail(f"'{name}' has not accepted the EULA",
                        "Paper exits immediately, which reads like a crash.",
                        f"Set eula=true in {eula}")

        properties = directory / "server.properties"
        if properties.exists():
            values = load_properties(properties)
            if values.get("online-mode", "true").lower() == "false":
                report.ok(f"'{name}' server.properties: online-mode=false")
            else:
                report.fail(f"'{name}' has online-mode=true",
                            "The proxy has already authenticated the player.",
                            f"Set online-mode=false in {properties}")
        else:
            report.warn(f"'{name}' has no server.properties", str(properties))

        paper_global = directory / "config" / "paper-global.yml"
        if not paper_global.exists():
            report.warn(f"'{name}' has no config/paper-global.yml", str(paper_global))
            continue

        paper = load_simple_yaml(paper_global)
        velocity_enabled = dig(paper, "proxies", "velocity", "enabled")
        velocity_secret = dig(paper, "proxies", "velocity", "secret")
        paper_proxy_protocol = dig(paper, "proxies", "proxy-protocol")

        if mode == "modern":
            if velocity_enabled is True:
                report.ok(f"'{name}' has proxies.velocity.enabled=true")
            else:
                report.fail(f"'{name}' does not have velocity forwarding enabled",
                            f"proxies.velocity.enabled={velocity_enabled}",
                            f"Set proxies.velocity.enabled: true in {paper_global}")

            if velocity_secret and relay_fp:
                backend_fp = fingerprint(str(velocity_secret))
                if backend_fp == relay_fp:
                    report.ok(f"'{name}' forwarding secret matches", f"fingerprint {backend_fp}")
                else:
                    report.fail(
                        f"'{name}' forwarding secret does NOT match",
                        f"relay.toml {relay_fp}  vs  {name} {backend_fp}",
                        "Paper reports this only as 'Unable to verify player details'.\n"
                        f"Copy one value to the other, in relay.toml or {paper_global}",
                    )
            elif not velocity_secret:
                report.fail(f"'{name}' has no proxies.velocity.secret")

        # The mismatch that produces no error on either side: the backend waits
        # for a header that never arrives, so it accepts the connection and then
        # simply never answers.
        if bool(paper_proxy_protocol) != bool(send_proxy):
            report.fail(
                f"'{name}' PROXY protocol disagrees with Relay",
                f"relay.toml proxy-protocol-send={send_proxy}  vs  "
                f"{name} proxies.proxy-protocol={paper_proxy_protocol}",
                "Neither side logs anything when these disagree; the backend just "
                "goes silent.\nSet both to the same value (false is fine unless "
                "something sits in front of Relay).",
            )
        else:
            report.ok(f"'{name}' PROXY protocol agrees", f"both {bool(send_proxy)}")

    heading("Build")
    jar = jar_path()
    if jar is None:
        report.warn("no jar built yet", fix="py relay.py build")
    elif jar_is_stale():
        report.warn("jar is older than the sources", str(jar), "py relay.py build")
    else:
        age = time.strftime("%Y-%m-%d %H:%M", time.localtime(jar.stat().st_mtime))
        report.ok("jar is up to date", f"{jar.name}, built {age}")

    for process in relay_processes():
        report.info(f"Relay is running as PID {process.pid}")

    print()
    return report.render()


def cmd_link(args) -> int:
    """Records a backend's Paper install, so it can be inspected and launched."""
    directory = Path(args.path).expanduser().resolve()
    if not directory.is_dir():
        print(Style.red(f"Not a directory: {directory}"))
        return 1
    if not (directory / "server.properties").exists():
        print(Style.yellow(f"Warning: no server.properties in {directory}"))

    entry: dict = {"path": str(directory)}
    if args.jar:
        entry["jar"] = str((directory / args.jar).resolve())
    if args.memory:
        entry["memory"] = args.memory
    if args.java:
        entry["java"] = args.java

    data = load_dev_config()
    # Preserve settings from a previous link so re-linking a path is not
    # silently destructive.
    existing = data.setdefault("paper_servers", {}).get(args.name)
    if isinstance(existing, dict):
        merged = dict(existing)
        merged.update(entry)
        entry = merged
    data["paper_servers"][args.name] = entry
    save_dev_config(data)

    print(Style.green(f"Linked '{args.name}' to {directory}"))
    jar = Path(entry["jar"]) if entry.get("jar") else find_server_jar(directory)
    print(Style.dim(f"  jar    {jar.name if jar else 'none found'}"))
    print(Style.dim(f"  java   {entry.get('java', 'java')}   memory {entry.get('memory', '2G')}"))
    port = backend_port(entry)
    if port:
        print(Style.dim(f"  port   {port}"))
    print(Style.dim(f"Saved to {DEV_CONFIG}"))
    return 0


def cmd_start(args) -> int:
    names = resolve_names(args.name)
    if not names:
        return 1

    mode = (BACKGROUND if args.background
            else CONSOLE if args.console
            else TABS if args.tabs
            else FOREGROUND)
    if len(names) > 1 and mode == FOREGROUND:
        # This terminal can only host one server, so several have to go somewhere
        # else rather than the command silently starting just the first. Tabs keep
        # them together where that is possible.
        mode = TABS if windows_terminal() else CONSOLE
        print(Style.yellow("Starting several servers, so they go into "
                           + ("tabs of one window." if mode == TABS else "separate windows.")))

    entries = backends()
    code = 0
    for name in names:
        heading(f"Backend: {name}")
        code |= start_backend(name, entries[name], args.debug, mode)
    return code


def cmd_shutdown(args) -> int:
    names = resolve_names(args.name)
    if not names:
        return 1
    code = 0
    for name in names:
        code |= stop_backend(name, force=args.force)
    return code


def proxy_command(quiet: bool) -> list[str] | None:
    """The java invocation for the proxy, or None if there is no jar yet."""
    jar = jar_path()
    if jar is None:
        return None
    command = ["java"]
    if not quiet:
        command.append("-Drelay.log.level=DEBUG")
    return command + ["-jar", str(jar)]


def start_proxy_tab(quiet: bool) -> bool:
    """
    Opens the proxy in a tab of the shared window.

    Its console comes with it, so `stop` can be typed there for a clean shutdown
    -- and this terminal is left free to be the control console instead.
    """
    command = proxy_command(quiet)
    if command is None:
        print(Style.red("No jar found. Run `build` first."))
        return False
    if windows_terminal() is None:
        return False
    subprocess.Popen(open_tab_command("relay-proxy", PROJECT, command))
    print(Style.green("Relay opened as a tab"))
    return True


#: Commands the console handles itself rather than passing to the parser.
SHELL_BUILTINS = {"help", "?", "exit", "quit", "cls", "clear"}


def cmd_shell(args) -> int:
    """
    A control console for the running stack.

    Typed lines are dispatched through the same parser the command line uses, so
    there is exactly one definition of every command and the two cannot drift.
    """
    print()
    print(Style.bold("Relay console") + Style.dim("   'help' for commands, 'exit' to leave"))
    print(Style.dim("Servers keep running when you leave; 'down' stops them."))

    parser = build_parser()
    while True:
        try:
            line = input(Style.cyan("relay> ")).strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not line:
            continue

        word = line.split()[0].lower()
        if word in ("exit", "quit"):
            return 0
        if word in ("cls", "clear"):
            os.system("cls" if IS_WINDOWS else "clear")
            continue
        if word in ("help", "?"):
            print_shell_help()
            continue

        try:
            import shlex
            parsed = parser.parse_args(shlex.split(line))
        except SystemExit:
            # argparse prints its own message and would otherwise end the console.
            continue
        except ValueError as error:
            print(Style.red(f"Could not parse that: {error}"))
            continue

        if not getattr(parsed, "command", None):
            print(Style.yellow("Unknown command. Try 'help'."))
            continue
        # `up` from inside the console would otherwise open a second console.
        parsed.no_shell = True
        try:
            parsed.func(parsed)
        except KeyboardInterrupt:
            print(Style.yellow("  interrupted"))
        except Exception as error:  # noqa: BLE001 - a console should survive anything
            print(Style.red(f"  {type(error).__name__}: {error}"))


def print_shell_help() -> None:
    rows = [
        ("status", "what is running, and which backends are up"),
        ("doctor", "cross-check every config for silent mismatches"),
        ("ping", "prove the proxy answers a server-list ping"),
        ("logs -n 40", "recent proxy log, or 'logs -f' to follow"),
        ("", ""),
        ("start <name>", "start a backend in a tab"),
        ("shutdown <name>", "stop a backend, saving the world"),
        ("run", "restart the proxy in this terminal"),
        ("stop", "stop the proxy"),
        ("down", "stop the proxy and every backend"),
        ("", ""),
        ("build", "rebuild the jar and plugin"),
        ("test", "run the test suite"),
        ("plugin <name>", "install the Paper-side plugin"),
        ("link <name> <path>", "register a backend"),
        ("", ""),
        ("exit", "leave the console; servers keep running"),
    ]
    print()
    for command, description in rows:
        if not command:
            print()
        else:
            print(f"  {Style.cyan(command.ljust(20))} {description}")
    print()
    print(Style.dim("  Any relay.py command works here, with the same flags."))
    print()


def cmd_up(args) -> int:
    """Starts every backend in the background, waits for them, then runs the proxy."""
    entries = backends()
    if not entries:
        print(Style.red("No backends are linked."))
        print("  py relay.py link lobby <path-to-paper-server>")
        return 1

    # Backends go into tabs of a single window so their output can be watched and
    # commands typed into them without covering the screen, leaving this terminal
    # free for the proxy.
    mode = (BACKGROUND if args.background
            else CONSOLE if args.windows
            else TABS if windows_terminal()
            else CONSOLE)
    heading("Starting backends")
    for name, entry in entries.items():
        start_backend(name, entry, args.debug, mode)

    heading("Waiting for backends")
    for name, entry in entries.items():
        port = backend_port(entry)
        if port is None:
            print(Style.yellow(f"{name}: no server-port in server.properties, not waiting"))
            continue
        print(f"{name}: waiting for 127.0.0.1:{port} ...", end="", flush=True)
        if wait_for_port("127.0.0.1", port, args.timeout):
            print(Style.green(" up"))
        else:
            # Not fatal: Relay's fallback list will simply skip it, and the log
            # will say why more usefully than a guess here would.
            print(Style.yellow(f" still down after {args.timeout:.0f}s"))
            print(Style.dim(f"  check {RUN_DIR / (name + '.log')}"))

    heading("Starting Relay")
    if relay_processes():
        print(Style.yellow("Relay is already running; stopping the old instance."))
        cmd_stop(args)
    if args.build or jar_is_stale():
        if run_gradle([":proxy:build"]) != 0:
            return 1

    # The proxy gets a tab like everything else, which leaves this terminal free
    # to be the control console rather than being consumed by the proxy's own.
    if mode == TABS and start_proxy_tab(args.quiet):
        if getattr(args, "no_shell", False):
            return 0
        return cmd_shell(args)

    # No tabs available, so the proxy takes this terminal as before.
    return cmd_run(args)


def cmd_down(args) -> int:
    cmd_stop(args)
    code = 0
    for name in backends():
        code |= stop_backend(name, force=args.force)
    return code


def cmd_plugin(args) -> int:
    """Builds the debug plugin and drops it into each linked server's plugins folder."""
    names = resolve_names(args.name)
    if not names:
        return 1

    if run_gradle([":paper-plugin:build"]) != 0:
        return 1
    jar = plugin_jar_path()
    if jar is None:
        print(Style.red("The plugin jar was not produced."))
        return 1

    entries = backends()
    code = 0
    for name in names:
        # Windows will not let a loaded jar be replaced or deleted, so a running
        # server turns this into a confusing permission error partway through.
        if backend_pid(name):
            print(Style.yellow(f"'{name}' is running; its plugin jar cannot be replaced."))
            print(Style.dim(f"  stop it first:  py relay.py shutdown {name}"))
            code = 1
            continue

        plugins = Path(entries[name]["path"]) / "plugins"
        plugins.mkdir(exist_ok=True)
        # Clear older copies, or the server loads two versions and refuses one.
        # RelayDebug is the name this plugin shipped under before it grew past being
        # a diagnostic; leaving one behind would load the plugin twice.
        for pattern in ("Relay-*.jar", "RelayDebug-*.jar"):
            for stale in plugins.glob(pattern):
                if stale.name == jar.name:
                    continue
                try:
                    stale.unlink()
                    print(Style.dim(f"  removed stale {stale.name}"))
                except OSError as error:
                    print(Style.yellow(f"  could not remove {stale.name}: {error}"))
                    print(Style.dim("  two copies would load the plugin twice; remove it by hand"))
                    code = 1
        shutil.copy2(jar, plugins / jar.name)
        print(Style.green(f"Installed {jar.name} into {plugins}"))

    if code == 0:
        print()
        print("Restart the backend(s) to load it. On join the plugin probes the")
        print("backend API and prints what came back; /relay drives it by hand.")
    return code


def cmd_unlink(args) -> int:
    data = load_dev_config()
    servers = data.get("paper_servers", {})
    if args.name not in servers:
        print(Style.yellow(f"'{args.name}' is not linked."))
        return 0
    del servers[args.name]
    save_dev_config(data)
    print(Style.green(f"Unlinked '{args.name}'"))
    return 0


def companion_processes() -> list[Process]:
    """Companion processes the proxy started, matched by their jar names."""
    return [
        process for process in running_processes()
        if any(marker in process.command.lower() for marker in COMPANION_MARKERS)
    ]


def cmd_stop(args) -> int:
    processes = relay_processes()
    if not processes:
        print("No Relay process is running.")
        return 0
    for process in processes:
        print(f"Stopping PID {process.pid}")
        print(Style.dim(f"  {process.command[:120]}"))
        if not kill(process.pid):
            print(Style.red(f"  could not terminate {process.pid}"))
            return 1

    # Companions are stopped here too. On Windows kill() is taskkill /F, which skips
    # the proxy's shutdown hook entirely -- so the goodbye that would normally tell
    # companions to exit never goes out, and they are left holding their ports. The
    # next dashboard then fails to bind, which looks like a broken dashboard rather
    # than an orphan from the run before.
    time.sleep(0.6)
    orphans = companion_processes()
    for process in orphans:
        print(f"Stopping companion PID {process.pid}")
        kill(process.pid)

    print(Style.green("Stopped."))
    return 0


def cmd_build(args) -> int:
    # A running proxy holds the jar open, which makes `clean` fail on Windows
    # with an error that does not mention the proxy at all.
    if relay_processes():
        print(Style.yellow("Relay is running and holding the jar; stopping it first."))
        cmd_stop(args)

    tasks = ["clean", "build"] if args.clean else ["build"]
    if args.skip_tests:
        tasks += ["-x", "test"]
    code = run_gradle(tasks)
    if code == 0:
        for built in (jar_path(), plugin_jar_path()):
            if built:
                print(Style.green(f"Built {built.name}"))
    return code


def cmd_run(args) -> int:
    if relay_processes():
        print(Style.yellow("Relay is already running; stopping the old instance."))
        cmd_stop(args)

    if args.build or jar_is_stale():
        if jar_is_stale() and not args.build:
            print(Style.yellow("Jar is older than the sources; rebuilding."))
        if run_gradle([":proxy:build"]) != 0:
            return 1

    jar = jar_path()
    if jar is None:
        print(Style.red("No jar found. Run `py relay.py build`."))
        return 1

    command = ["java"]
    if not args.quiet:
        command.append("-Drelay.log.level=DEBUG")
    command += ["-jar", str(jar)]

    print(Style.dim(f"$ {' '.join(command)}"))
    print(Style.dim(f"  cwd {PROJECT}   config {CONFIG}"))
    print(Style.dim("  type 'stop' to shut down cleanly"))
    print()
    # Inherit stdio so the proxy console stays interactive.
    return subprocess.run(command, cwd=PROJECT).returncode


def cmd_status(args) -> int:
    heading("Relay")
    processes = relay_processes()
    if processes:
        for process in processes:
            print(Style.green(f"running, PID {process.pid}"))
    else:
        print("not running")

    jar = jar_path()
    if jar:
        age = time.strftime("%Y-%m-%d %H:%M", time.localtime(jar.stat().st_mtime))
        stale = Style.yellow(" (older than sources)") if jar_is_stale() else ""
        print(f"jar  {jar.name}  built {age}{stale}")

        # A jar rebuilt under a running proxy leaves it unable to load classes it has
        # not touched yet, which surfaces much later as a NoClassDefFoundError deep in
        # Netty and reads like a proxy bug rather than a stale process.
        for process in processes:
            if process.started and process.started < jar.stat().st_mtime:
                print(Style.yellow("     this process started before that jar was built, so it is "
                                   "running stale code"))
                print(Style.dim("     restart it: py relay.py stop, then run/up"))
                break
    else:
        print("jar  not built")

    if CONFIG.exists():
        try:
            config = load_toml(CONFIG)
            secret = str(config.get("forwarding-secret", "") or "")
            bind = config.get("bind", "?")
            print(f"bind {bind}")
            if secret:
                print(f"secret fingerprint {fingerprint(secret.strip())}")
            servers = config.get("servers", {}) or {}
            heading("Backends")
            linked = backends()
            for name, address in servers.items():
                host, _, port = str(address).rpartition(":")
                host = host.strip("[]") or "127.0.0.1"
                state = (Style.green("up") if port.isdigit() and port_open(host, int(port))
                         else Style.red("down"))
                managed = ""
                if name in linked:
                    pid = backend_pid(name)
                    managed = (Style.dim(f"  started by this script, PID {pid}") if pid
                               else Style.dim("  linked"))
                print(f"{name:<12} {str(address):<24} {state}{managed}")
            for name in linked:
                if name not in servers:
                    print(Style.yellow(f"{name:<12} linked but not in relay.toml [servers]"))
        except Exception as error:  # noqa: BLE001
            print(Style.red(f"could not read relay.toml: {error}"))
    return 0


def dashboard_servers() -> list[dict] | None:
    """The backend list from the dashboard, or None if it is not reachable."""
    import json as _json
    import urllib.error
    import urllib.request

    port = 8080
    if CONFIG.exists():
        try:
            companions = load_toml(CONFIG).get("companions", {}) or {}
            env = (companions.get("dashboard", {}) or {}).get("environment", {}) or {}
            port = int(env.get("RELAY_DASHBOARD_PORT", port))
        except Exception:
            pass
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/servers", timeout=5) as response:
            return _json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None


def cmd_fake(args) -> int:
    """
    Connects fake players, then reports where the proxy put them.

    The tool holds the connections; this process prints the distribution
    alongside it, because "where did they land" is the question being asked and
    reading it off a dashboard by hand defeats the point.
    """
    jar = jar_path()
    if jar is None or jar_is_stale():
        print(Style.yellow("Building first."))
        if run_gradle([":proxy:build"]) != 0:
            return 1
        jar = jar_path()
    if jar is None:
        print(Style.red("No jar found. Run `py relay.py build`."))
        return 1

    # Checked before anything connects: a proxy in online mode refuses every fake
    # player identically, and forty copies of that is not a useful way to find out.
    if CONFIG.exists():
        try:
            if load_toml(CONFIG).get("online-mode", True):
                print(Style.red("online-mode = true in relay.toml."))
                print("  Fake players cannot authenticate with Mojang. Set it to false,")
                print("  restart the proxy, load test, then set it back.")
                return 1
        except Exception:
            pass

    if not relay_processes():
        print(Style.red("Relay is not running. Start it with `py relay.py up`."))
        return 1

    command = ["java", "-cp", str(jar), "dev.relay.tools.FakePlayers",
               "--host", args.host, "--port", str(args.port),
               "--count", str(args.count), "--stagger", str(args.stagger)]

    print(Style.dim("$ " + " ".join(command)))
    process = subprocess.Popen(command, cwd=str(PROJECT))
    try:
        # Long enough for the staggered logins plus the switch each one makes.
        time.sleep(args.count * args.stagger / 1000 + 6)
        show_distribution()
        print()
        print("Holding them online. Ctrl+C to disconnect and stop.")
        process.wait()
    except KeyboardInterrupt:
        pass
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
    return 0


def show_distribution() -> None:
    servers = dashboard_servers()
    if servers is None:
        print()
        print(Style.yellow("The dashboard is not reachable, so the distribution "
                           "cannot be read. Try /glist in game."))
        return

    print()
    heading("Where they landed")
    total = sum(s.get("players", 0) for s in servers)
    if total == 0:
        print(Style.yellow("Nobody is on any backend."))
        return

    width = max(len(s.get("name", "")) for s in servers)
    for server in servers:
        count = server.get("players", 0)
        share = count / total if total else 0
        # A bar, because the question is whether the split is even and two columns
        # of numbers make that surprisingly hard to see.
        bar = "#" * round(share * 30)
        state = server.get("status", "")
        note = "" if state == "HEALTHY" else Style.yellow(f"  [{state.lower()}]")
        print(f"  {server.get('name', ''):<{width}}  {count:>4}  {bar}{note}")
    print(f"  {'total':<{width}}  {total:>4}")


def cmd_ping(args) -> int:
    host, port = args.host, args.port
    if port is None:
        port = 25565
        if CONFIG.exists():
            try:
                bind = str(load_toml(CONFIG).get("bind", ""))
                if ":" in bind:
                    port = int(bind.rpartition(":")[2])
            except Exception:  # noqa: BLE001
                pass

    print(Style.dim(f"pinging {host}:{port}"))
    try:
        started = time.monotonic()
        status = status_ping(host, port, protocol=args.protocol)
        elapsed = (time.monotonic() - started) * 1000
    except Exception as error:  # noqa: BLE001
        print(Style.red(f"no response: {error}"))
        return 1

    version = status.get("version", {})
    players = status.get("players", {})
    print(Style.green(f"responded in {elapsed:.0f} ms"))
    print(f"  version   {version.get('name')} (protocol {version.get('protocol')})")
    print(f"  players   {players.get('online')}/{players.get('max')}")
    for entry in players.get("sample", []) or []:
        print(f"            {entry.get('name')}")
    return 0


def cmd_paper_debug(args) -> int:
    """
    Writes the log4j2 config that reveals Paper's packet-level logging.

    Paper logs pipeline faults and every packet at DEBUG, which is off by
    default --- so a backend that drops a connection for protocol reasons
    normally says nothing at all.
    """
    directory = paper_dirs().get(args.name)
    if directory is None:
        print(Style.red(f"No backend named '{args.name}'. Link it first:"))
        print(f"  py relay.py link {args.name} <path-to-paper-server>")
        return 1

    target = Path(directory) / "log4j2-debug.xml"
    target.write_text(
        """<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="[%d{HH:mm:ss} %level]: %msg%n%throwable"/>
    </Console>
  </Appenders>
  <Loggers>
    <!-- Packet-level tracing, plus the pipeline exceptions Paper otherwise swallows. -->
    <Logger name="net.minecraft.network" level="DEBUG"/>
    <Logger name="io.netty" level="DEBUG"/>
    <Root level="INFO"><AppenderRef ref="Console"/></Root>
  </Loggers>
</Configuration>
""",
        encoding="utf-8",
    )
    print(Style.green(f"Wrote {target}"))
    print()
    print("Start that server with:")
    print(Style.cyan(f'  java -Dlog4j.configurationFile=log4j2-debug.xml -Xmx2G -jar server.jar --nogui'))
    return 0


def cmd_logs(args) -> int:
    log = PROJECT / "logs" / "relay.log"
    if not log.exists():
        print(Style.red(f"No log at {log}"))
        return 1
    if not args.follow:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
        for line in lines[-args.lines:]:
            print(line)
        return 0

    print(Style.dim(f"following {log}, Ctrl-C to stop"))
    with log.open("r", encoding="utf-8", errors="replace") as handle:
        handle.seek(0, os.SEEK_END)
        try:
            while True:
                line = handle.readline()
                if line:
                    print(line.rstrip())
                else:
                    time.sleep(0.25)
        except KeyboardInterrupt:
            return 0


def cmd_test(args) -> int:
    tasks = [":proxy:test"]
    if args.filter:
        tasks += ["--tests", args.filter]
    return run_gradle(tasks)


# --------------------------------------------------------------------------- cli


def build_parser() -> argparse.ArgumentParser:
    """
    The command surface, built once and shared.

    The control console dispatches typed lines through this same parser, so every
    command behaves identically whether it came from the shell or the command
    line, and neither can drift from the other.
    """
    parser = argparse.ArgumentParser(
        prog="relay.py",
        description="Development helper for the Relay Minecraft proxy.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""typical loop:
  py relay.py link lobby C:/mc/lobby   once, per backend
  py relay.py doctor                   catch the mismatches that fail silently
  py relay.py up                       tabs for the backends and the proxy, then a
                                       control console in this terminal
  py relay.py down                     stop all of it

other:
  py relay.py start lobby --debug   one backend, with Paper's packet logging
  py relay.py plugin lobby          install the backend-side plugin
  py relay.py run                   just the proxy, rebuilding if stale
  py relay.py ping                  prove the proxy answers a server-list ping
  py relay.py logs -f               follow the proxy log
""",
    )
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("doctor", help="cross-check Relay and backend configuration").set_defaults(
        func=cmd_doctor)

    link = sub.add_parser("link", help="record a backend's Paper install")
    link.add_argument("name")
    link.add_argument("path")
    link.add_argument("--jar", help="server jar name, if not server.jar")
    link.add_argument("--memory", help="heap size, e.g. 4G (default 2G)")
    link.add_argument("--java", help="java executable, for a backend needing a different JDK")
    link.set_defaults(func=cmd_link)

    unlink = sub.add_parser("unlink", help="forget a linked backend")
    unlink.add_argument("name")
    unlink.set_defaults(func=cmd_unlink)

    start = sub.add_parser("start", help="start a backend (or all of them)")
    start.add_argument("name", nargs="?", help="backend name, or 'all'")
    start.add_argument("--debug", action="store_true", help="enable Paper's packet logging")
    start.add_argument("--tabs", action="store_true",
                       help="one window, a tab per backend (default when starting several)")
    start.add_argument("--console", action="store_true",
                       help="a separate window per backend")
    start.add_argument("--background", action="store_true",
                       help="detach with output to a file; nothing to type into")
    start.set_defaults(func=cmd_start)

    shutdown = sub.add_parser("shutdown", help="stop a background backend, saving the world")
    shutdown.add_argument("name", nargs="?", help="backend name, or 'all'")
    shutdown.add_argument("--force", action="store_true",
                          help="hard-kill if there is no RCON; may lose recent chunks")
    shutdown.set_defaults(func=cmd_shutdown)

    up = sub.add_parser("up", help="start every backend in its own window, then run the proxy")
    up.add_argument("--debug", action="store_true", help="enable Paper's packet logging too")
    up.add_argument("--timeout", type=float, default=90.0, help="seconds to wait per backend")
    up.add_argument("--windows", action="store_true",
                    help="a separate window per backend instead of tabs")
    up.add_argument("--background", action="store_true",
                    help="no consoles at all; log backends to files instead")
    up.add_argument("--build", action="store_true")
    up.add_argument("--quiet", action="store_true")
    up.add_argument("--no-shell", action="store_true",
                    help="skip the control console and return to the prompt")
    up.set_defaults(func=cmd_up)

    shell = sub.add_parser("console", help="a control console for the running stack")
    shell.set_defaults(func=cmd_shell)

    down = sub.add_parser("down", help="stop the proxy and every backend")
    down.add_argument("--force", action="store_true",
                      help="hard-kill backends without RCON; may lose recent chunks")
    down.set_defaults(func=cmd_down)

    run = sub.add_parser("run", help="run the proxy, rebuilding if the jar is stale")
    run.add_argument("--build", action="store_true", help="always rebuild first")
    run.add_argument("--quiet", action="store_true", help="omit DEBUG logging")
    run.set_defaults(func=cmd_run)

    build = sub.add_parser("build", help="build the shaded jar")
    build.add_argument("--clean", action="store_true", help="clean first")
    build.add_argument("--skip-tests", action="store_true")
    build.set_defaults(func=cmd_build)

    plugin = sub.add_parser("plugin", help="build and install the Paper-side plugin")
    plugin.add_argument("name", nargs="?", help="backend name, or 'all'")
    plugin.set_defaults(func=cmd_plugin)

    sub.add_parser("stop", help="stop a running proxy").set_defaults(func=cmd_stop)
    sub.add_parser("status", help="what is running, and which backends are up").set_defaults(
        func=cmd_status)

    ping = sub.add_parser("ping", help="send a real server-list ping")
    ping.add_argument("--host", default="127.0.0.1")
    ping.add_argument("--port", type=int, default=None)
    ping.add_argument("--protocol", type=int, default=764, help="client protocol to claim")
    ping.set_defaults(func=cmd_ping)

    fake = sub.add_parser("fake", help="connect fake players and show where they land")
    fake.add_argument("count", type=int, nargs="?", default=20, help="how many (default 20)")
    fake.add_argument("--host", default="127.0.0.1")
    fake.add_argument("--port", type=int, default=25565)
    fake.add_argument("--stagger", type=int, default=150,
                      help="milliseconds between connections (default 150)")
    fake.set_defaults(func=cmd_fake)

    paper = sub.add_parser("paper-debug", help="write Paper's debug log4j2 config")
    paper.add_argument("name")
    paper.set_defaults(func=cmd_paper_debug)

    logs = sub.add_parser("logs", help="show the proxy log")
    logs.add_argument("-n", "--lines", type=int, default=60)
    logs.add_argument("-f", "--follow", action="store_true")
    logs.set_defaults(func=cmd_logs)

    test = sub.add_parser("test", help="run the test suite")
    test.add_argument("--filter", help="e.g. dev.relay.api.*")
    test.set_defaults(func=cmd_test)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        return 0
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
