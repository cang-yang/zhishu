#!/usr/bin/env python3
"""Reviewed REAL fault driver for the six ZH-F03 reliability cases.

The driver talks directly to the running application, MySQL, MinIO,
Elasticsearch and the named Docker containers. It never accepts commands or
hooks from its configuration. Every mutation is limited to a run-specific
document, one fixed test trigger, the knowledge_base write-block setting, or
the two explicitly configured containers. Faults are restored in ``finally``.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any, Callable


EXPERIMENT_ID = "ZH-EXP-F03-LIGHT-RELIABILITY"
CASE_WINDOWS = {
    "F03-L-001": 120,
    "F03-L-002": 120,
    "F03-L-003": 120,
    "F03-L-004": 120,
    "F03-L-005": 180,
    "F03-L-006": 120,
}
OUTBOX_TRIGGER = "zh_f03_fail_outbox_insert"
ENV_PATTERN = re.compile(r"^\$\{([A-Z0-9_]+)}$")


class DriverFailure(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def expand_environment(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: expand_environment(item) for key, item in value.items()}
    if isinstance(value, list):
        return [expand_environment(item) for item in value]
    if isinstance(value, str):
        match = ENV_PATTERN.fullmatch(value)
        if match:
            name = match.group(1)
            resolved = os.environ.get(name)
            if not resolved:
                raise DriverFailure(f"required environment variable is missing: {name}")
            return resolved
    return value


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DriverFailure(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise DriverFailure(f"JSON root must be an object: {path}")
    return value


def persist_es_write_block_backup(run_dir: Path, original: Any) -> None:
    write_json(run_dir / "es-write-block-backup.json", {
        "schemaVersion": "ZH-F03-ES-WRITE-BLOCK-BACKUP/1",
        "present": original is not None,
        "value": original,
    })


def load_es_write_block_backup(run_dir: Path) -> Any:
    backup = load_json(run_dir / "es-write-block-backup.json")
    if backup.get("schemaVersion") != "ZH-F03-ES-WRITE-BLOCK-BACKUP/1":
        raise DriverFailure("invalid ES write-block backup schema")
    if backup.get("present") is True:
        return backup.get("value")
    if backup.get("present") is False:
        return None
    raise DriverFailure("ES write-block backup is missing presence metadata")


def persist_docker_state_backup(run_dir: Path, containers: dict[str, dict[str, Any]]) -> None:
    write_json(run_dir / "docker-state-backup.json", {
        "schemaVersion": "ZH-F03-DOCKER-STATE-BACKUP/1",
        "containers": containers,
    })


def load_docker_state_backup(run_dir: Path) -> dict[str, dict[str, Any]]:
    backup = load_json(run_dir / "docker-state-backup.json")
    if backup.get("schemaVersion") != "ZH-F03-DOCKER-STATE-BACKUP/1":
        raise DriverFailure("invalid Docker state backup schema")
    containers = backup.get("containers")
    if not isinstance(containers, dict):
        raise DriverFailure("Docker state backup is missing containers")
    return containers


def require_keys(value: dict, path: str, keys: tuple[str, ...]) -> None:
    missing = [key for key in keys if key not in value or value[key] in (None, "")]
    if missing:
        raise DriverFailure(f"{path} missing required fields: {', '.join(missing)}")


def materialize_dataset(manifest: dict, case_id: str, run_id: str) -> tuple[str, bytes, str]:
    if manifest.get("schemaVersion") != "ZH-F03-DATASET/1":
        raise DriverFailure("dataset manifest schemaVersion must be ZH-F03-DATASET/1")
    cases = manifest.get("cases")
    if not isinstance(cases, dict) or not isinstance(cases.get(case_id), dict):
        raise DriverFailure(f"dataset manifest has no entry for {case_id}")
    entry = cases[case_id]
    require_keys(entry, f"dataset.cases.{case_id}", ("fileName", "contentUtf8"))
    content = entry["contentUtf8"].replace("{{RUN_ID}}", run_id).replace("{{CASE_ID}}", case_id)
    payload = content.encode("utf-8")
    if not payload:
        raise DriverFailure("materialized dataset is empty")
    file_name = entry["fileName"].replace("{{RUN_ID}}", run_id)
    if not file_name.lower().endswith(".txt"):
        raise DriverFailure("REAL driver currently accepts frozen TXT cases only")
    marker = entry.get("searchMarker", "ZH_F03_{{RUN_ID}}")
    marker = marker.replace("{{RUN_ID}}", run_id).replace("{{CASE_ID}}", case_id)
    if marker not in content:
        raise DriverFailure("dataset searchMarker must occur in materialized content")
    return file_name, payload, marker


class RealFaultDriver:
    def __init__(self, args: argparse.Namespace, config: dict, dataset: dict):
        self.args = args
        self.config = config
        self.dataset = dataset
        self.run_dir = Path(args.run_dir).resolve()
        self.raw_path = self.run_dir / "raw-state.json"
        self.reset_path = self.run_dir / "state-reset-proof.json"
        self.started_at = utc_now()
        self.started_clock = time.monotonic()
        self.deadline = self.started_clock + CASE_WINDOWS[args.case_id]
        self.fault_events: list[dict] = []
        self.task_history: list[dict] = []
        self.outbox_history: list[dict] = []
        self.file_history: list[dict] = []
        self.reset_actions: list[dict] = []
        self.cleanup_callbacks: list[tuple[str, Callable[[], None]]] = []
        self.process_controller = None
        self.task_id: str | None = None
        self.file_id: int | None = None
        self.version: int | None = None
        self.file_md5: str | None = None
        self.file_name: str | None = None
        self.payload: bytes | None = None
        self.search_marker: str | None = None
        self._load_dependencies()

    def _load_dependencies(self) -> None:
        try:
            import pymysql
            import requests
            from minio import Minio
        except ImportError as exc:
            raise DriverFailure(
                "REAL driver dependencies are missing; install requirements-zh-f03-real.txt"
            ) from exc
        self.pymysql = pymysql
        self.requests = requests
        self.Minio = Minio

    def validate_config(self) -> None:
        if self.config.get("schemaVersion") != "ZH-F03-REAL-DRIVER/1":
            raise DriverFailure("driver config schemaVersion must be ZH-F03-REAL-DRIVER/1")
        require_keys(self.config, "config", (
            "appBaseUrl", "mysql", "minio", "elasticsearch", "kafka", "auth", "legacyProbe"
        ))
        require_keys(self.config["mysql"], "mysql", ("host", "port", "database", "user", "password"))
        require_keys(self.config["minio"], "minio", ("endpoint", "accessKey", "secretKey", "bucket"))
        require_keys(self.config["elasticsearch"], "elasticsearch", ("url", "username", "password"))
        require_keys(self.config["kafka"], "kafka", ("consumerGroup",))
        require_keys(self.config["auth"], "auth", ("ownerToken", "adminToken", "otherToken"))
        require_keys(self.config["legacyProbe"], "legacyProbe", (
            "query", "fileMd5", "fileOwnerUserId", "queryOwnerUserId", "expectedVisible"
        ))
        if self.runtime_mode() == "DOCKER":
            require_keys(self.config, "config", ("docker",))
            require_keys(self.config["docker"], "docker", ("kafkaContainer", "backendContainer"))
            for container in (
                self.config["docker"]["kafkaContainer"], self.config["docker"]["backendContainer"]
            ):
                if not re.fullmatch(r"[A-Za-z0-9_.-]+", container):
                    raise DriverFailure(f"unsafe Docker container name: {container}")
        else:
            control = self.config.get("processControl")
            if not isinstance(control, dict):
                raise DriverFailure("WINDOWS_LOCAL requires processControl configuration")
            require_keys(control, "processControl", ("statePath", "services", "kafkaConsumerGroupsPath"))

    def runtime_mode(self) -> str:
        mode = self.config.get("processControl", {}).get("mode", "DOCKER")
        if mode not in ("DOCKER", "WINDOWS_LOCAL"):
            raise DriverFailure(f"unsupported processControl.mode: {mode}")
        return mode

    def _container_for(self, service: str) -> str:
        key_by_service = {"kafka": "kafkaContainer", "backend": "backendContainer"}
        if service not in key_by_service:
            raise DriverFailure(f"unsupported runtime service: {service}")
        return self.config["docker"][key_by_service[service]]

    def _native_process_controller(self):
        if self.process_controller is not None:
            return self.process_controller
        try:
            from zh_f03_native_process_control import NativeProcessController
        except ImportError as exc:
            raise DriverFailure("native process controller module is unavailable") from exc
        control = self.config.get("processControl", {})
        state_path = control.get("statePath")
        if not isinstance(state_path, str) or not state_path:
            raise DriverFailure("WINDOWS_LOCAL requires processControl.statePath")
        self.process_controller = NativeProcessController(control, Path(state_path))
        return self.process_controller

    def stop_runtime(self, service: str) -> None:
        if self.runtime_mode() == "DOCKER":
            self.docker_stop(self._container_for(service))
            return
        self._native_process_controller().stop(service)

    def start_runtime(self, service: str) -> None:
        if self.runtime_mode() == "DOCKER":
            self.docker_start(self._container_for(service))
            return
        self._native_process_controller().start(service)

    def runtime_preflight(self) -> None:
        if self.runtime_mode() == "DOCKER":
            subprocess.run(
                ["docker", "version", "--format", "{{.Server.Version}}"],
                text=True, capture_output=True, timeout=10, check=True,
            )
            container_states = {}
            for key in ("kafkaContainer", "backendContainer"):
                container = self.config["docker"][key]
                inspected = subprocess.run(
                    ["docker", "inspect", "--format", "{{.State.Running}}", container],
                    text=True, capture_output=True, timeout=10, check=True,
                )
                running = inspected.stdout.strip().lower() == "true"
                container_states[key] = {"name": container, "running": running}
            persist_docker_state_backup(self.run_dir, container_states)
            stopped = [state["name"] for state in container_states.values() if not state["running"]]
            if stopped:
                raise DriverFailure(
                    "REAL preflight requires configured containers to be running: " + ", ".join(stopped)
                )
            return

        controller = self._native_process_controller()
        statuses = [controller.status(service) for service in ("kafka", "backend")]
        stopped = [status["service"] for status in statuses if status.get("running") is not True]
        if stopped:
            raise DriverFailure(
                "REAL preflight requires harness-owned services to be running: " + ", ".join(stopped)
            )
        write_json(self.run_dir / "native-process-state-backup.json", controller.snapshot())

    def runtime_restore(self) -> None:
        if self.runtime_mode() == "DOCKER":
            for service in ("kafka", "backend"):
                self.start_runtime(service)
            return
        self._native_process_controller().restore()

    def ensure_time(self, reserve_seconds: float = 0.0) -> None:
        if time.monotonic() + reserve_seconds >= self.deadline:
            raise DriverFailure(f"case observation window exhausted ({CASE_WINDOWS[self.args.case_id]}s)")

    def event(self, event_type: str, **details: Any) -> None:
        self.fault_events.append({"type": event_type, "capturedAt": utc_now(), "details": details})

    def register_cleanup(self, name: str, callback: Callable[[], None]) -> None:
        self.cleanup_callbacks.append((name, callback))

    @contextmanager
    def db(self):
        connection = self.pymysql.connect(
            host=self.config["mysql"]["host"],
            port=int(self.config["mysql"]["port"]),
            user=self.config["mysql"]["user"],
            password=self.config["mysql"]["password"],
            database=self.config["mysql"]["database"],
            charset="utf8mb4",
            cursorclass=self.pymysql.cursors.DictCursor,
            autocommit=True,
        )
        try:
            yield connection
        finally:
            connection.close()

    def sql(self, statement: str, params: tuple = (), one: bool = False):
        self.ensure_time(2)
        with self.db() as connection, connection.cursor() as cursor:
            cursor.execute(statement, params)
            if cursor.description is None:
                return cursor.rowcount
            return cursor.fetchone() if one else cursor.fetchall()

    def docker(self, action: str, container: str, check: bool = True) -> subprocess.CompletedProcess:
        if action not in ("start", "stop"):
            raise DriverFailure(f"unsupported Docker action: {action}")
        self.ensure_time(5)
        return subprocess.run(
            ["docker", action, container], text=True, capture_output=True,
            timeout=15, check=check,
        )

    def docker_start(self, container: str) -> None:
        self.docker("start", container)

    def docker_stop(self, container: str) -> None:
        self.docker("stop", container)

    def http(self, method: str, path: str, token: str | None = None, **kwargs):
        self.ensure_time(5)
        headers = dict(kwargs.pop("headers", {}))
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return self.requests.request(
            method,
            self.config["appBaseUrl"].rstrip("/") + path,
            headers=headers,
            timeout=min(15, max(1, int(self.deadline - time.monotonic()))),
            **kwargs,
        )

    def es(self, method: str, path: str, **kwargs):
        self.ensure_time(5)
        config = self.config["elasticsearch"]
        response = self.requests.request(
            method,
            config["url"].rstrip("/") + path,
            auth=(config["username"], config["password"]),
            verify=bool(config.get("verifyTls", True)),
            timeout=min(15, max(1, int(self.deadline - time.monotonic()))),
            **kwargs,
        )
        return response

    def minio(self):
        config = self.config["minio"]
        return self.Minio(
            config["endpoint"],
            access_key=config["accessKey"],
            secret_key=config["secretKey"],
            secure=bool(config.get("secure", False)),
        )

    def preflight(self) -> None:
        self.validate_config()
        mapping = self.es("GET", "/knowledge_base/_mapping")
        if mapping.status_code != 200:
            raise DriverFailure(f"cannot read knowledge_base mapping: HTTP {mapping.status_code}")
        properties = mapping.json().get("knowledge_base", {}).get("mappings", {}).get("properties", {})
        if properties.get("fileUploadId", {}).get("type") != "long":
            raise DriverFailure("REAL preflight requires fileUploadId mapping type long")
        if properties.get("processingVersion", {}).get("type") != "integer":
            raise DriverFailure("REAL preflight requires processingVersion mapping type integer")
        self.sql("SELECT 1", one=True)
        if not self.minio().bucket_exists(self.config["minio"]["bucket"]):
            raise DriverFailure("configured MinIO bucket does not exist")
        self.runtime_preflight()

    def prepare_dataset(self) -> None:
        self.file_name, self.payload, self.search_marker = materialize_dataset(
            self.dataset, self.args.case_id, self.args.run_id
        )
        self.file_md5 = hashlib.md5(self.payload).hexdigest()

    def upload_chunks(self) -> None:
        chunk_size = 5 * 1024 * 1024
        chunks = [self.payload[index:index + chunk_size] for index in range(0, len(self.payload), chunk_size)]
        owner = self.config["auth"]["ownerToken"]
        for index, chunk in enumerate(chunks):
            response = self.http(
                "POST", "/api/v1/upload/chunk", owner,
                data={
                    "fileMd5": self.file_md5,
                    "chunkIndex": str(index),
                    "totalSize": str(len(self.payload)),
                    "fileName": self.file_name,
                    "totalChunks": str(len(chunks)),
                    "isPublic": "false",
                },
                files={"file": (self.file_name, chunk, "text/plain")},
            )
            if response.status_code // 100 != 2:
                raise DriverFailure(f"chunk upload failed: HTTP {response.status_code}")

    def merge(self):
        response = self.http(
            "POST", "/api/v1/upload/merge", self.config["auth"]["ownerToken"],
            json={"fileMd5": self.file_md5, "fileName": self.file_name},
        )
        body = response.json() if response.content else {}
        if response.status_code // 100 == 2:
            data = body.get("data") or {}
            self.task_id = data.get("taskId")
            self.version = data.get("processingVersion")
            file_row = self.file_row()
            self.file_id = file_row["id"] if file_row else None
        return response, body

    def file_row(self):
        if not self.file_md5:
            return None
        return self.sql(
            "SELECT id,file_md5,user_id,status,latest_processing_version,active_processing_version "
            "FROM file_upload WHERE file_md5=%s ORDER BY id DESC LIMIT 1",
            (self.file_md5,), one=True,
        )

    def task_row(self, task_id: str | None = None):
        target = task_id or self.task_id
        if not target:
            return None
        return self.sql(
            "SELECT task_id,file_upload_id,processing_version,status,current_stage,retry_count,error_message,"
            "source_object_key,execution_token,lease_expire_at,created_at,updated_at,completed_at "
            "FROM document_processing_task WHERE task_id=%s",
            (target,), one=True,
        )

    def outbox_row(self, task_id: str | None = None):
        target = task_id or self.task_id
        if not target:
            return None
        return self.sql(
            "SELECT event_id,task_id,status,retry_count,next_retry_at,last_error,created_at,updated_at "
            "FROM outbox_event WHERE task_id=%s", (target,), one=True,
        )

    def snapshot_task(self, task_id: str | None = None):
        row = self.task_row(task_id)
        if row:
            self.task_history.append(self.json_safe(row))
        return row

    def snapshot_outbox(self, task_id: str | None = None):
        row = self.outbox_row(task_id)
        if row:
            self.outbox_history.append(self.json_safe(row))
        return row

    def snapshot_file(self):
        row = self.file_row()
        if row:
            self.file_history.append(self.file_state(row))
        return row

    def poll(self, description: str, reader: Callable[[], Any], predicate: Callable[[Any], bool]):
        interval = float(self.config.get("pollIntervalSeconds", 0.25))
        while True:
            self.ensure_time(5)
            value = reader()
            if predicate(value):
                return value
            time.sleep(interval)

    def wait_task(self, statuses: tuple[str, ...], task_id: str | None = None):
        row = self.poll(
            f"task status in {statuses}",
            lambda: self.task_row(task_id),
            lambda value: isinstance(value, dict) and value.get("status") in statuses,
        )
        self.task_history.append(self.json_safe(row))
        return row

    def wait_outbox(self, status: str, task_id: str | None = None):
        row = self.poll(
            f"outbox status {status}",
            lambda: self.outbox_row(task_id),
            lambda value: isinstance(value, dict) and value.get("status") == status,
        )
        self.outbox_history.append(self.json_safe(row))
        return row

    def kafka_offset(self) -> int:
        group = self.config["kafka"]["consumerGroup"]
        command = self.kafka_consumer_group_command(group)
        result = subprocess.run(command, text=True, capture_output=True, timeout=15, check=True)
        offsets = []
        for line in result.stdout.splitlines():
            columns = line.split()
            if len(columns) >= 5 and columns[1] == "document-processing-v2" and columns[3].isdigit():
                offsets.append(int(columns[3]))
        if not offsets:
            raise DriverFailure("cannot derive document-processing-v2 consumer offset")
        return sum(offsets)

    def dlt_offset(self) -> int:
        group = self.config["kafka"].get("dltConsumerGroup", "document-processing-v2-dlt-group")
        result = subprocess.run(
            self.kafka_consumer_group_command(group),
            text=True, capture_output=True, timeout=15, check=True,
        )
        offsets = []
        for line in result.stdout.splitlines():
            columns = line.split()
            if len(columns) >= 5 and columns[1] == "document-processing-v2-dlt" and columns[3].isdigit():
                offsets.append(int(columns[3]))
        return sum(offsets) if offsets else 0

    def kafka_consumer_group_command(self, group: str) -> list[str]:
        bootstrap_server = self.config["kafka"].get("bootstrapServer", "localhost:9092")
        arguments = ["--bootstrap-server", bootstrap_server, "--describe", "--group", group]
        if self.runtime_mode() == "DOCKER":
            return [
                "docker", "exec", self._container_for("kafka"),
                "/opt/kafka/bin/kafka-consumer-groups.sh", *arguments,
            ]
        control = self.config["processControl"]
        script = Path(control["kafkaConsumerGroupsPath"]).resolve()
        if not script.is_file():
            raise DriverFailure(f"Kafka consumer-groups launcher does not exist: {script}")
        expected_hash = control.get("kafkaConsumerGroupsSha256")
        actual_hash = hashlib.sha256(script.read_bytes()).hexdigest().upper()
        if not isinstance(expected_hash, str) or actual_hash != expected_hash.upper():
            raise DriverFailure("Kafka consumer-groups launcher hash mismatch")
        return [str(script), *arguments]

    def chunks(self, file_id: int | None = None, version: int | None = None):
        target_file = file_id or self.file_id
        target_version = self.version if version is None else version
        if target_file is None or target_version is None:
            return []
        return self.sql(
            "SELECT file_upload_id,processing_version,chunk_id,content_hash "
            "FROM document_vectors WHERE file_upload_id=%s AND processing_version=%s ORDER BY chunk_id",
            (target_file, target_version),
        )

    def es_documents(self, file_id: int | None = None, version: int | None = None):
        target_file = file_id or self.file_id
        if target_file is None:
            return []
        filters = [{"term": {"fileUploadId": target_file}}]
        if version is not None:
            filters.append({"term": {"processingVersion": version}})
        response = self.es("POST", "/knowledge_base/_search", json={
            "size": 1000,
            "query": {"bool": {"filter": filters}},
            "_source": ["fileUploadId", "processingVersion", "chunkId", "contentHash", "fileMd5"],
        })
        if response.status_code != 200:
            raise DriverFailure(f"ES state query failed: HTTP {response.status_code}")
        documents = []
        for hit in response.json().get("hits", {}).get("hits", []):
            source = dict(hit.get("_source") or {})
            source["id"] = hit.get("_id")
            documents.append(source)
        return documents

    def search(self, query: str, token: str):
        response = self.http("GET", "/api/v1/search/hybrid", token, params={"query": query, "topK": 50})
        if response.status_code // 100 != 2:
            raise DriverFailure(f"search failed: HTTP {response.status_code}")
        body = response.json()
        if body.get("code") != 200:
            raise DriverFailure(f"search body failed: code={body.get('code')}")
        return body.get("data") or []

    def permission_checks(self, task_id: str):
        checks = []
        for actor, token in (
            ("owner", self.config["auth"]["ownerToken"]),
            ("admin", self.config["auth"]["adminToken"]),
            ("other", self.config["auth"]["otherToken"]),
        ):
            response = self.http("GET", f"/api/v1/document-processing/{task_id}", token)
            checks.append({"actor": actor, "status": response.status_code})
        return checks

    def legacy_visibility(self):
        probe = self.config["legacyProbe"]
        hits = self.search(probe["query"], self.config["auth"]["ownerToken"])
        actual = any(hit.get("fileMd5") == probe["fileMd5"] for hit in hits if isinstance(hit, dict))
        return [{
            "fileOwnerUserId": str(probe["fileOwnerUserId"]),
            "queryOwnerUserId": str(probe["queryOwnerUserId"]),
            "expectedVisible": bool(probe["expectedVisible"]),
            "actualVisible": actual,
            "fileMd5": probe["fileMd5"],
        }]

    def run_case(self) -> dict:
        handlers = {
            "F03-L-001": self.case_outbox_kafka_recovery,
            "F03-L-002": self.case_duplicate_delivery,
            "F03-L-003": self.case_expired_lease,
            "F03-L-004": self.case_es_bulk_failure,
            "F03-L-005": self.case_dlt_reprocess,
            "F03-L-006": self.case_finalize_rollback,
        }
        return handlers[self.args.case_id]()

    def case_outbox_kafka_recovery(self) -> dict:
        self.stop_runtime("kafka")
        self.register_cleanup("start Kafka", lambda: self.start_runtime("kafka"))
        self.event("KAFKA_UNAVAILABLE", runtimeMode=self.runtime_mode())
        self.upload_chunks()
        response, _ = self.merge()
        if response.status_code // 100 != 2:
            raise DriverFailure(f"merge failed while Kafka was unavailable: HTTP {response.status_code}")
        initial = self.snapshot_outbox()
        if not initial or initial["status"] not in ("NEW", "SENDING"):
            raise DriverFailure("outbox was not recoverable while Kafka was unavailable")
        retried = self.poll(
            "outbox retry while Kafka unavailable", self.outbox_row,
            lambda row: isinstance(row, dict) and int(row.get("retry_count") or 0) > 0,
        )
        self.outbox_history.append(self.json_safe(retried))
        self.start_runtime("kafka")
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "start Kafka"]
        self.event("KAFKA_RESTORED", runtimeMode=self.runtime_mode())
        self.wait_outbox("PUBLISHED")
        processing = self.wait_task(("PROCESSING", "COMPLETED"))
        if processing["status"] != "COMPLETED":
            self.wait_task(("COMPLETED",))
        return {}

    def case_duplicate_delivery(self) -> dict:
        self.upload_chunks()
        response, _ = self.merge()
        if response.status_code // 100 != 2:
            raise DriverFailure(f"merge failed: HTTP {response.status_code}")
        self.wait_task(("COMPLETED",))
        outbox = self.wait_outbox("PUBLISHED")
        mysql_before = self.business_keys(self.chunks())
        es_before = sorted(document["id"] for document in self.es_documents(self.file_id, self.version))
        offset_before = self.kafka_offset()
        updated = self.sql(
            "UPDATE outbox_event SET status='NEW',next_retry_at=NOW(6),last_error='ZH-F03 duplicate injection' "
            "WHERE task_id=%s AND status='PUBLISHED'", (self.task_id,),
        )
        if updated != 1:
            raise DriverFailure("could not reset the unique outbox event for duplicate delivery")
        self.event("DUPLICATE_KAFKA_DELIVERY", eventId=outbox["event_id"])
        self.wait_outbox("PUBLISHED")
        offset_after = self.poll("consumer offset after duplicate", self.kafka_offset, lambda value: value > offset_before)
        mysql_after = self.business_keys(self.chunks())
        es_after = sorted(document["id"] for document in self.es_documents(self.file_id, self.version))
        first = self.chunks()[0]
        attempted_hash = "0" * 64 if first["content_hash"] != "0" * 64 else "1" * 64
        error_code = None
        error_message = ""
        sql_state = None
        try:
            self.sql(
                "INSERT INTO document_vectors "
                "(file_md5,chunk_id,file_upload_id,processing_version,content_hash,text_content,user_id,is_public) "
                "VALUES (%s,%s,%s,%s,%s,'ZH-F03 conflict','zh-f03-driver',0)",
                (self.file_md5, first["chunk_id"], self.file_id, self.version, attempted_hash),
            )
        except self.pymysql.MySQLError as exc:
            if exc.args and isinstance(exc.args[0], int):
                error_code = exc.args[0]
            error_message = str(exc)[:512]
            sql_state = getattr(exc, "sqlstate", None)
        if error_code != 1062:
            raise DriverFailure(
                f"conflicting business-key insert was not rejected by the unique key (errorCode={error_code})"
            )
        self.event(
            "MYSQL_HASH_CONFLICT",
            businessKey=mysql_before[0],
            errorCode=error_code,
            sqlState=sql_state,
        )
        return {
            "kafkaDelivery": {
                "eventId": outbox["event_id"], "duplicateEventId": outbox["event_id"],
                "offsetBefore": offset_before, "offsetAfter": offset_after,
            },
            "idempotency": {
                "mysqlBusinessKeysBefore": mysql_before,
                "mysqlBusinessKeysAfter": mysql_after,
                "esIdsBefore": es_before,
                "esIdsAfter": es_after,
                "hashConflict": {
                    "existingHash": first["content_hash"],
                    "attemptedHash": attempted_hash,
                    "errorCode": error_code,
                    "errorMessage": error_message,
                    "sqlState": sql_state,
                },
            },
        }

    def case_expired_lease(self) -> dict:
        self.stop_runtime("kafka")
        self.register_cleanup("start Kafka", lambda: self.start_runtime("kafka"))
        self.upload_chunks()
        response, _ = self.merge()
        if response.status_code // 100 != 2:
            raise DriverFailure(f"merge failed: HTTP {response.status_code}")
        self.start_runtime("kafka")
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "start Kafka"]
        claimed = self.wait_task(("PROCESSING",))
        self.stop_runtime("backend")
        self.register_cleanup("start backend", lambda: self.start_runtime("backend"))
        self.event("WORKER_TERMINATED", executionToken=claimed.get("execution_token"))
        self.poll(
            "lease expiration", self.task_row,
            lambda row: isinstance(row, dict) and row.get("lease_expire_at") is not None
            and row["lease_expire_at"] < datetime.now(),
        )
        self.event("LEASE_EXPIRED", taskId=self.task_id)
        self.stop_runtime("kafka")
        self.register_cleanup("start Kafka", lambda: self.start_runtime("kafka"))
        self.start_runtime("backend")
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "start backend"]
        self.wait_task(("RETRY_WAIT",))
        self.snapshot_outbox()
        self.start_runtime("kafka")
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "start Kafka"]
        self.wait_outbox("PUBLISHED")
        self.wait_task(("COMPLETED",))
        return {}

    def case_es_bulk_failure(self) -> dict:
        self.upload_chunks()
        before = self.snapshot_file()
        settings_response = self.es("GET", "/knowledge_base/_settings?flat_settings=true")
        if settings_response.status_code != 200:
            raise DriverFailure(
                f"could not read knowledge_base write-block setting: HTTP {settings_response.status_code}"
            )
        settings = settings_response.json()
        original = settings.get("knowledge_base", {}).get("settings", {}).get("index.blocks.write")
        persist_es_write_block_backup(self.run_dir, original)
        response = self.es("PUT", "/knowledge_base/_settings", json={"index.blocks.write": True})
        if response.status_code // 100 != 2:
            raise DriverFailure("could not enable knowledge_base write block")
        self.register_cleanup("restore ES write block", lambda: self.restore_es_write_block(original))
        probe_id = f"zh-f03-bulk-probe-{self.args.run_id}"
        bulk = self.es(
            "POST", "/_bulk", headers={"Content-Type": "application/x-ndjson"},
            data=(json.dumps({"index": {"_index": "knowledge_base", "_id": probe_id}})
                  + "\n" + json.dumps({"textContent": self.search_marker}) + "\n"),
        )
        bulk_body = bulk.json()
        failed_items = []
        for item in bulk_body.get("items", []):
            result = item.get("index") or {}
            if int(result.get("status", 0)) >= 400:
                failed_items.append({
                    "documentId": result.get("_id"),
                    "status": int(result.get("status")),
                    "errorType": (result.get("error") or {}).get("type", "unknown"),
                })
        if not failed_items:
            raise DriverFailure("ES write block did not produce a bulk item failure")
        self.event("ES_BULK_ITEM_REJECTED", failedItemCount=len(failed_items))
        response, _ = self.merge()
        if response.status_code // 100 != 2:
            raise DriverFailure(f"merge failed before ES consumer fault: HTTP {response.status_code}")
        self.wait_task(("FAILED",))
        after = self.snapshot_file()
        hits = self.search(self.search_marker, self.config["auth"]["ownerToken"])
        sample_hits = [hit for hit in hits if hit.get("fileMd5") == self.file_md5]
        if sample_hits:
            raise DriverFailure("failed ES version became searchable")
        return {
            "esBulk": {"failedItems": failed_items},
            "fileHistory": [self.file_state(before), self.file_state(after)],
        }

    def case_dlt_reprocess(self) -> dict:
        self.stop_runtime("kafka")
        self.register_cleanup("start Kafka", lambda: self.start_runtime("kafka"))
        self.upload_chunks()
        response, _ = self.merge()
        if response.status_code // 100 != 2:
            raise DriverFailure(f"merge failed: HTTP {response.status_code}")
        object_key = f"merged/{self.file_md5}"
        backup_path = self.run_dir / "source-backup.bin"
        client = self.minio()
        source = client.get_object(self.config["minio"]["bucket"], object_key)
        try:
            backup_path.write_bytes(source.read())
        finally:
            source.close()
            source.release_conn()
        write_json(self.run_dir / "source-backup-meta.json", {"objectKey": object_key})
        client.remove_object(self.config["minio"]["bucket"], object_key)
        self.register_cleanup("restore MinIO source", lambda: self.restore_minio_source(object_key, backup_path))
        self.event("MINIO_SOURCE_UNAVAILABLE", objectKey=object_key)
        dlt_before = self.dlt_offset()
        self.start_runtime("kafka")
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "start Kafka"]
        failed = self.wait_task(("FAILED",))
        dlt_after = self.poll("DLT offset", self.dlt_offset, lambda value: value > dlt_before)
        self.event("DLT_RECORD_OBSERVED", offsetBefore=dlt_before, offsetAfter=dlt_after)
        self.restore_minio_source(object_key, backup_path)
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "restore MinIO source"]
        old_version = int(failed["processing_version"])
        old_task = failed["task_id"]
        response = self.http(
            "POST", f"/api/v1/document-processing/{old_task}/reprocess",
            self.config["auth"]["ownerToken"],
        )
        if response.status_code // 100 != 2:
            raise DriverFailure(f"owner reprocess failed: HTTP {response.status_code}")
        data = response.json().get("data") or {}
        self.task_id = data.get("taskId")
        self.version = int(data.get("processingVersion"))
        if self.version != old_version + 1:
            raise DriverFailure("reprocess did not create version+1")
        self.wait_task(("COMPLETED",))
        return {
            "dlt": {"offsetBefore": dlt_before, "offsetAfter": dlt_after},
            "reprocess": {
                "httpStatus": response.status_code,
                "oldVersion": old_version,
                "newVersion": self.version,
            },
            "taskHistory": [self.json_safe(failed), self.json_safe(self.task_row())],
        }

    def case_finalize_rollback(self) -> dict:
        self.upload_chunks()
        before_file = self.file_row()
        before = {
            "fileVersion": int(before_file["latest_processing_version"]),
            "taskRows": self.count_for_file("document_processing_task", before_file["id"]),
            "outboxRows": self.count_outbox_for_file(before_file["id"]),
        }
        self.drop_outbox_trigger()
        self.sql(
            f"CREATE TRIGGER {OUTBOX_TRIGGER} BEFORE INSERT ON outbox_event FOR EACH ROW "
            "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ZH-F03 injected outbox failure'"
        )
        self.register_cleanup("drop outbox trigger", self.drop_outbox_trigger)
        self.event("MYSQL_FINALIZE_REJECTED", trigger=OUTBOX_TRIGGER)
        response, _ = self.merge()
        if response.status_code < 500:
            raise DriverFailure(f"outbox failure did not fail merge: HTTP {response.status_code}")
        after_file = self.file_row()
        after = {
            "fileVersion": int(after_file["latest_processing_version"]),
            "taskRows": self.count_for_file("document_processing_task", before_file["id"]),
            "outboxRows": self.count_outbox_for_file(before_file["id"]),
        }
        client = self.minio()
        object_key = f"merged/{self.file_md5}"
        client.stat_object(self.config["minio"]["bucket"], object_key)
        before_retry = True
        self.drop_outbox_trigger()
        self.cleanup_callbacks = [item for item in self.cleanup_callbacks if item[0] != "drop outbox trigger"]
        retry, _ = self.merge()
        if retry.status_code // 100 != 2:
            raise DriverFailure(f"merge retry failed: HTTP {retry.status_code}")
        self.wait_task(("COMPLETED",))
        client.stat_object(self.config["minio"]["bucket"], object_key)
        return {
            "transaction": {
                "httpStatus": response.status_code,
                "before": before,
                "afterFailure": after,
                "retryHttpStatus": retry.status_code,
            },
            "minioProof": {
                "mergedObjectBeforeRetry": before_retry,
                "mergedObjectAfterRetry": True,
            },
        }

    def count_for_file(self, table: str, file_id: int) -> int:
        if table not in ("document_processing_task",):
            raise DriverFailure("unsafe count table")
        row = self.sql(f"SELECT COUNT(*) AS count_value FROM {table} WHERE file_upload_id=%s", (file_id,), one=True)
        return int(row["count_value"])

    def count_outbox_for_file(self, file_id: int) -> int:
        row = self.sql(
            "SELECT COUNT(*) AS count_value FROM outbox_event o JOIN document_processing_task t "
            "ON t.task_id=o.task_id WHERE t.file_upload_id=%s", (file_id,), one=True,
        )
        return int(row["count_value"])

    def drop_outbox_trigger(self) -> None:
        self.sql(f"DROP TRIGGER IF EXISTS {OUTBOX_TRIGGER}")

    def restore_es_write_block(self, original: Any) -> None:
        value = original if original is not None else None
        response = self.es("PUT", "/knowledge_base/_settings", json={"index.blocks.write": value})
        if response.status_code // 100 != 2:
            raise DriverFailure(f"failed to restore ES write block: HTTP {response.status_code}")

    def restore_minio_source(self, object_key: str, backup_path: Path) -> None:
        self.minio().fput_object(
            self.config["minio"]["bucket"], object_key, str(backup_path), content_type="text/plain"
        )

    def business_keys(self, rows: list[dict]) -> list[str]:
        return [f"{row['file_upload_id']}:{row['processing_version']}:{row['chunk_id']}" for row in rows]

    def file_state(self, row: dict | None) -> dict:
        if not row:
            return {"latestProcessingVersion": None, "activeProcessingVersion": None}
        return {
            "latestProcessingVersion": row.get("latest_processing_version"),
            "activeProcessingVersion": row.get("active_processing_version"),
        }

    def json_safe(self, value: Any) -> Any:
        if isinstance(value, dict):
            return {self.camel(key): self.json_safe(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self.json_safe(item) for item in value]
        if isinstance(value, datetime):
            return value.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
        return value

    def camel(self, value: str) -> str:
        parts = value.split("_")
        return parts[0] + "".join(part.capitalize() for part in parts[1:])

    def collect_common(self) -> dict:
        file_row = self.file_row()
        task = self.task_row()
        version = task.get("processing_version") if task else self.version
        chunk_rows = self.chunks(self.file_id, version)
        active_version = file_row.get("active_processing_version") if file_row else None
        active_rows = self.chunks(self.file_id, active_version) if active_version is not None else []
        es_rows = self.es_documents(self.file_id, active_version) if active_version is not None else []
        expected_ids = self.business_keys(active_rows)
        actual_ids = sorted(row["id"] for row in es_rows)
        search_hits = []
        if self.search_marker:
            for hit in self.search(self.search_marker, self.config["auth"]["ownerToken"]):
                if hit.get("fileMd5") != self.file_md5:
                    continue
                matching = [row for row in es_rows if row.get("chunkId") == hit.get("chunkId")]
                search_hits.append({
                    "fileMd5": hit.get("fileMd5"),
                    "chunkId": hit.get("chunkId"),
                    "processingVersion": matching[0].get("processingVersion") if matching else None,
                    "activeProcessingVersion": active_version,
                })
        return {
            "task": self.json_safe(task),
            "outbox": self.json_safe(self.outbox_row()),
            "file": self.file_state(file_row),
            "chunks": self.json_safe(chunk_rows),
            "activeChunks": self.json_safe(active_rows),
            "expectedActiveEsIds": sorted(expected_ids),
            "actualActiveEsIds": actual_ids,
            "kafka": {"topic": "document-processing-v2", "dltTopic": "document-processing-v2-dlt"},
            "minio": {"objectKey": f"merged/{self.file_md5}", "reusable": True},
            "searchHits": search_hits,
            "permissionChecks": self.permission_checks(self.task_id) if self.task_id else [],
            "legacyVisibilityChecks": self.legacy_visibility(),
        }

    def cleanup(self) -> bool:
        success = True
        for name, callback in reversed(self.cleanup_callbacks):
            try:
                callback()
                self.reset_actions.append({"action": name, "status": "RESTORED", "capturedAt": utc_now()})
            except Exception as exc:  # cleanup evidence must preserve every failure
                success = False
                self.reset_actions.append({
                    "action": name, "status": "FAILED", "capturedAt": utc_now(),
                    "error": f"{type(exc).__name__}: {exc}",
                })
        write_json(self.reset_path, {
            "status": "RESTORED" if success else "RESET_FAILED",
            "capturedAt": utc_now(),
            "actions": self.reset_actions,
        })
        return success

    def execute(self) -> int:
        case_facts: dict = {}
        failure = None
        reset_ok = False
        try:
            self.preflight()
            self.prepare_dataset()
            case_facts = self.run_case()
            common = self.collect_common()
        except Exception as exc:
            failure = f"{type(exc).__name__}: {exc}"
            common = {}
        finally:
            try:
                reset_ok = self.cleanup()
            except Exception as cleanup_exc:
                reset_ok = False
                failure = failure or f"cleanup failed: {cleanup_exc}"
        outcome = "PASS" if failure is None and reset_ok else "FAIL"
        raw = {
            "experimentId": EXPERIMENT_ID,
            "runType": self.args.run_type,
            "caseId": self.args.case_id,
            "runId": self.args.run_id,
            "attempt": self.args.attempt,
            "recordedOutcome": outcome,
            "failureReason": failure,
            "observation": {"startedAt": self.started_at, "completedAt": utc_now()},
            "faultEvents": self.fault_events,
            "taskHistory": self.task_history,
            "outboxHistory": self.outbox_history,
            "fileHistory": self.file_history,
        }
        raw.update(case_facts)
        raw.update(common)
        write_json(self.raw_path, raw)
        return 0 if outcome == "PASS" else 30


def emergency_reset(config: dict, run_dir: Path) -> int:
    errors = []
    try:
        import pymysql
        mysql = config["mysql"]
        connection = pymysql.connect(
            host=mysql["host"], port=int(mysql["port"]), user=mysql["user"],
            password=mysql["password"], database=mysql["database"], autocommit=True,
        )
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP TRIGGER IF EXISTS {OUTBOX_TRIGGER}")
        finally:
            connection.close()
    except Exception as exc:
        errors.append(f"drop trigger: {exc}")
    runtime_mode = config.get("processControl", {}).get("mode", "DOCKER")
    docker_backup_path = run_dir / "docker-state-backup.json"
    if runtime_mode == "WINDOWS_LOCAL":
        try:
            from zh_f03_native_process_control import NativeProcessController
            control = config.get("processControl", {})
            NativeProcessController(control, Path(control["statePath"])).restore()
        except Exception as exc:
            errors.append(f"restore native runtime: {exc}")
    elif runtime_mode == "DOCKER" and docker_backup_path.is_file():
        try:
            container_states = load_docker_state_backup(run_dir)
            for key in ("kafkaContainer", "backendContainer"):
                state = container_states.get(key)
                if not isinstance(state, dict) or state.get("name") != config["docker"][key]:
                    raise DriverFailure(f"Docker backup does not match configured {key}")
                if state.get("running") is not True:
                    raise DriverFailure(f"Docker backup did not record {key} as initially running")
                subprocess.run(
                    ["docker", "start", state["name"]],
                    text=True, capture_output=True, timeout=20, check=True,
                )
        except Exception as exc:
            errors.append(f"restore Docker state: {exc}")
    elif runtime_mode not in ("DOCKER", "WINDOWS_LOCAL"):
        errors.append(f"unsupported processControl.mode: {runtime_mode}")
    es_backup_path = run_dir / "es-write-block-backup.json"
    if es_backup_path.is_file():
        try:
            import requests
            es = config["elasticsearch"]
            original = load_es_write_block_backup(run_dir)
            response = requests.put(
                es["url"].rstrip("/") + "/knowledge_base/_settings",
                auth=(es["username"], es["password"]), verify=bool(es.get("verifyTls", True)),
                json={"index.blocks.write": original}, timeout=15,
            )
            response.raise_for_status()
        except Exception as exc:
            errors.append(f"restore ES write block: {exc}")
    meta_path = run_dir / "source-backup-meta.json"
    backup_path = run_dir / "source-backup.bin"
    if meta_path.is_file() and backup_path.is_file():
        try:
            from minio import Minio
            meta = load_json(meta_path)
            minio = config["minio"]
            client = Minio(minio["endpoint"], access_key=minio["accessKey"],
                           secret_key=minio["secretKey"], secure=bool(minio.get("secure", False)))
            client.fput_object(minio["bucket"], meta["objectKey"], str(backup_path), content_type="text/plain")
        except Exception as exc:
            errors.append(f"restore MinIO source: {exc}")
    write_json(run_dir / "state-reset-proof.json", {
        "status": "EMERGENCY_RESET_OK" if not errors else "EMERGENCY_RESET_FAILED",
        "capturedAt": utc_now(), "errors": errors,
    })
    return 0 if not errors else 31


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="ZH-F03 reviewed REAL fault driver")
    parser.add_argument("--case-id", choices=tuple(CASE_WINDOWS), required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-type", choices=("REAL",), required=True)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--attempt", type=int, required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--dataset-manifest", required=True)
    parser.add_argument("--reset-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    run_dir = Path(args.run_dir).resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    try:
        config = expand_environment(load_json(Path(args.config).resolve()))
        dataset = load_json(Path(args.dataset_manifest).resolve())
        if args.reset_only:
            return emergency_reset(config, run_dir)
        return RealFaultDriver(args, config, dataset).execute()
    except Exception as exc:
        write_json(run_dir / "raw-state.json", {
            "experimentId": EXPERIMENT_ID,
            "runType": args.run_type,
            "caseId": args.case_id,
            "runId": args.run_id,
            "attempt": args.attempt,
            "recordedOutcome": "FAIL",
            "failureReason": f"{type(exc).__name__}: {exc}",
            "observation": {"startedAt": utc_now(), "completedAt": utc_now()},
            "faultEvents": [],
        })
        return 32


if __name__ == "__main__":
    raise SystemExit(main())
