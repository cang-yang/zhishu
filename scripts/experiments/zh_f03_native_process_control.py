#!/usr/bin/env python3
"""Fail-closed lifecycle control for ZH-F03 harness-owned Windows processes."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any, Callable


SCHEMA_VERSION = "ZH-F03-NATIVE-PROCESS/1"
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0
ENV_PATTERN = re.compile(r"^\$\{([A-Z0-9_]+)\}$")


class DriverFailure(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def _read_environment(path: Path) -> dict[str, str]:
    """Read dotenv values without ever putting them into controller state."""
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as exc:
        raise DriverFailure(f"cannot read application environment file: {path}") from exc
    values: dict[str, str] = {}
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        values[key] = value
    return values


def _probe_windows_process(pid: int) -> dict[str, Any] | None:
    process_query_limited_information = 0x1000
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.QueryFullProcessImageNameW.argtypes = [
        wintypes.HANDLE,
        wintypes.DWORD,
        wintypes.LPWSTR,
        ctypes.POINTER(wintypes.DWORD),
    ]
    kernel32.GetProcessTimes.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    ]

    handle = kernel32.OpenProcess(process_query_limited_information, False, pid)
    if not handle:
        return None
    try:
        buffer = ctypes.create_unicode_buffer(32768)
        size = wintypes.DWORD(len(buffer))
        if not kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
            return None
        created = wintypes.FILETIME()
        exited = wintypes.FILETIME()
        kernel = wintypes.FILETIME()
        user = wintypes.FILETIME()
        if not kernel32.GetProcessTimes(
            handle,
            ctypes.byref(created),
            ctypes.byref(exited),
            ctypes.byref(kernel),
            ctypes.byref(user),
        ):
            return None
        created_ticks = (created.dwHighDateTime << 32) | created.dwLowDateTime
        created_at_ns = (created_ticks - 116444736000000000) * 100
        executable = Path(buffer.value).resolve()
        return {
            "pid": int(pid),
            "createdAtNs": int(created_at_ns),
            "executablePath": str(executable),
            "executableSha256": _sha256(executable),
        }
    except OSError:
        return None
    finally:
        kernel32.CloseHandle(handle)


def _probe_process(pid: int) -> dict[str, Any] | None:
    if os.name != "nt":
        raise DriverFailure("native process control is supported only on Windows")
    return _probe_windows_process(pid)


def _start_process(service: str, spec: dict[str, Any]) -> dict[str, Any]:
    command = spec.get("command")
    working_directory = spec.get("workingDirectory")
    if not isinstance(command, list) or not command or not all(isinstance(item, str) and item for item in command):
        raise DriverFailure(f"invalid command for service: {service}")
    if not isinstance(working_directory, str) or not Path(working_directory).is_dir():
        raise DriverFailure(f"invalid working directory for service: {service}")
    frozen_files = spec.get("frozenFiles", [])
    if not isinstance(frozen_files, list):
        raise DriverFailure(f"invalid frozen file list for service: {service}")
    for entry in frozen_files:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or not isinstance(entry.get("sha256"), str):
            raise DriverFailure(f"invalid frozen file entry for service: {service}")
        frozen_path = Path(entry["path"])
        if not frozen_path.is_file() or _sha256(frozen_path) != entry["sha256"].upper():
            raise DriverFailure(f"frozen file hash mismatch for service: {service}")

    environment = os.environ.copy()
    environment_path = spec.get("environmentFile")
    if environment_path:
        environment.update(_read_environment(Path(environment_path)))
    overrides = spec.get("environment", {})
    if not isinstance(overrides, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in overrides.items()):
        raise DriverFailure(f"invalid environment overrides for service: {service}")
    environment.update(overrides)

    log_directory = Path(spec.get("logDirectory", Path(working_directory) / ".runtime"))
    log_directory.mkdir(parents=True, exist_ok=True)
    stdout_path = log_directory / f"{service}.stdout.log"
    stderr_path = log_directory / f"{service}.stderr.log"
    with stdout_path.open("ab") as stdout_handle, stderr_path.open("ab") as stderr_handle:
        process = subprocess.Popen(
            command,
            cwd=working_directory,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=stdout_handle,
            stderr=stderr_handle,
            shell=False,
            creationflags=CREATE_NO_WINDOW,
        )
    identity = None
    for _ in range(50):
        identity = _probe_process(process.pid)
        if identity is not None:
            break
        if process.poll() is not None:
            break
        time.sleep(0.1)
    if identity is None:
        raise DriverFailure(f"cannot establish process identity after starting service: {service}")

    stability_seconds = spec.get("startupStabilitySeconds", 0)
    if not isinstance(stability_seconds, int) or stability_seconds < 0 or stability_seconds > 120:
        raise DriverFailure(f"invalid startup stability window for service: {service}")
    deadline = time.monotonic() + stability_seconds
    required = ("pid", "createdAtNs", "executablePath", "executableSha256")
    while time.monotonic() < deadline:
        current = _probe_process(process.pid)
        if current is None or any(identity.get(field) != current.get(field) for field in required):
            raise DriverFailure(f"process exited or changed identity during startup stability window: {service}")
        time.sleep(min(0.25, max(0.01, deadline - time.monotonic())))
    return identity


def _stop_process(identity: dict[str, Any]) -> None:
    result = subprocess.run(
        ["taskkill", "/PID", str(identity["pid"]), "/T", "/F"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        shell=False,
        timeout=30,
        check=False,
        creationflags=CREATE_NO_WINDOW,
    )
    if result.returncode != 0 and _probe_process(int(identity["pid"])) is not None:
        raise DriverFailure(f"cannot stop harness-owned process: pid={identity['pid']}")


class NativeProcessController:
    """Manage only processes created and recorded by this experiment harness."""

    SERVICES = frozenset({"kafka", "backend"})

    def __init__(
        self,
        config: dict[str, Any],
        state_path: Path,
        *,
        process_probe: Callable[[int], dict[str, Any] | None] = _probe_process,
        process_start: Callable[[str, dict[str, Any]], dict[str, Any]] = _start_process,
        process_stop: Callable[[dict[str, Any]], None] = _stop_process,
    ) -> None:
        self.config = config
        self.state_path = Path(state_path)
        self.process_probe = process_probe
        self.process_start = process_start
        self.process_stop = process_stop

    def _service_spec(self, service: str) -> dict[str, Any]:
        if service not in self.SERVICES:
            raise DriverFailure(f"unsupported service: {service}")
        services = self.config.get("services")
        if not isinstance(services, dict) or not isinstance(services.get(service), dict):
            raise DriverFailure(f"missing service configuration: {service}")
        return services[service]

    def _load_state(self) -> dict[str, Any]:
        if not self.state_path.exists():
            return {"schemaVersion": SCHEMA_VERSION, "managedByHarness": True, "services": {}}
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as exc:
            raise DriverFailure(f"cannot read native process state: {self.state_path}") from exc
        if not isinstance(state, dict) or state.get("schemaVersion") != SCHEMA_VERSION:
            raise DriverFailure("unsupported native process state schema")
        return state

    def _save_state(self, state: dict[str, Any]) -> None:
        _write_json(self.state_path, state)

    @staticmethod
    def _identity_matches(expected: Any, current: Any) -> bool:
        required = ("pid", "createdAtNs", "executablePath", "executableSha256")
        return (
            isinstance(expected, dict)
            and isinstance(current, dict)
            and all(expected.get(field) == current.get(field) for field in required)
        )

    def status(self, service: str) -> dict[str, Any]:
        self._service_spec(service)
        state = self._load_state()
        entry = state.get("services", {}).get(service, {})
        identity = entry.get("identity") if isinstance(entry, dict) else None
        current = self.process_probe(int(identity["pid"])) if isinstance(identity, dict) and "pid" in identity else None
        return {
            "service": service,
            "desiredRunning": bool(entry.get("desiredRunning")) if isinstance(entry, dict) else False,
            "running": self._identity_matches(identity, current),
            "identity": identity,
        }

    def start(self, service: str) -> dict[str, Any]:
        spec = self._service_spec(service)
        state = self._load_state()
        if state.get("managedByHarness") is not True:
            raise DriverFailure("native process state is not managed by this harness")
        services = state.setdefault("services", {})
        entry = services.setdefault(service, {"desiredRunning": True, "identity": None})
        identity = entry.get("identity")
        current = self.process_probe(int(identity["pid"])) if isinstance(identity, dict) and "pid" in identity else None
        if self._identity_matches(identity, current):
            entry["desiredRunning"] = True
            self._save_state(state)
            return identity
        if current is not None:
            raise DriverFailure(f"process identity mismatch for service: {service}")
        identity = self.process_start(service, spec)
        entry["desiredRunning"] = True
        entry["identity"] = identity
        self._save_state(state)
        return identity

    def stop(self, service: str) -> dict[str, Any]:
        self._service_spec(service)
        state = self._load_state()
        if state.get("managedByHarness") is not True:
            raise DriverFailure("native process state is not managed by this harness")
        entry = state.get("services", {}).get(service)
        if not isinstance(entry, dict) or not isinstance(entry.get("identity"), dict):
            raise DriverFailure(f"service has no harness-owned process identity: {service}")
        expected = entry["identity"]
        current = self.process_probe(int(expected["pid"]))
        if not self._identity_matches(expected, current):
            raise DriverFailure(f"process identity mismatch for service: {service}")
        self.process_stop(expected)
        entry["identity"] = None
        self._save_state(state)
        return {"service": service, "stopped": True}

    def ensure_running(self) -> dict[str, Any]:
        for service in self.config.get("services", {}):
            self.start(service)
        return self.snapshot()

    def restore(self) -> dict[str, Any]:
        state = self._load_state()
        if state.get("managedByHarness") is not True:
            raise DriverFailure("native process state is not managed by this harness")
        desired = [
            service
            for service, entry in state.get("services", {}).items()
            if isinstance(entry, dict) and entry.get("desiredRunning") is True
        ]
        for service in desired:
            self.start(service)
        return self.snapshot()

    def snapshot(self) -> dict[str, Any]:
        return self._load_state()


def _expand_environment(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _expand_environment(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_expand_environment(item) for item in value]
    if isinstance(value, str):
        match = ENV_PATTERN.fullmatch(value)
        if match:
            resolved = os.environ.get(match.group(1))
            if not resolved:
                raise DriverFailure(f"required environment variable is missing: {match.group(1)}")
            return resolved
    return value


def _load_control_config(path: Path) -> dict[str, Any]:
    try:
        root = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DriverFailure(f"cannot read native process configuration: {path}") from exc
    control = root.get("processControl") if isinstance(root, dict) else None
    if not isinstance(control, dict) or control.get("mode") != "WINDOWS_LOCAL":
        raise DriverFailure("configuration does not select WINDOWS_LOCAL process control")
    return _expand_environment(control)


def main() -> int:
    parser = argparse.ArgumentParser(description="Control harness-owned ZH-F03 Windows processes")
    parser.add_argument("--action", required=True, choices=("ensure-running", "status", "start", "stop", "restore"))
    parser.add_argument("--service", choices=("kafka", "backend"))
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    if args.action in ("status", "start", "stop") and not args.service:
        parser.error(f"--service is required for --action {args.action}")
    try:
        control = _load_control_config(Path(args.config).resolve())
        state_path = control.get("statePath")
        if not isinstance(state_path, str) or not state_path:
            raise DriverFailure("processControl.statePath is missing")
        controller = NativeProcessController(control, Path(state_path))
        if args.action == "ensure-running":
            result = controller.ensure_running()
        elif args.action == "restore":
            result = controller.restore()
        elif args.action == "status":
            result = controller.status(args.service)
        elif args.action == "start":
            result = controller.start(args.service)
        else:
            result = controller.stop(args.service)
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return 0
    except DriverFailure as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
