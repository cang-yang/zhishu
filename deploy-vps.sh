#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docs/docker-compose.yaml"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"

if [ ! -f "${COMPOSE_FILE}" ]; then
  echo "未找到编排文件: ${COMPOSE_FILE}" >&2
  exit 1
fi

if [ ! -f "${ENV_FILE}" ]; then
  echo "未找到环境变量文件: ${ENV_FILE}" >&2
  echo "请先执行: cp .env.example .env" >&2
  exit 1
fi

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

usage() {
  cat <<'EOF'
用法: ./deploy-vps.sh <command>

命令:
  up        拉起服务并构建最新镜像
  down      停止并移除服务
  restart   重启所有服务
  pull      先 git pull 再重新部署
  build     仅构建镜像
  logs      查看服务日志
  ps        查看服务状态
  config    检查 compose 最终配置

示例:
  ./deploy-vps.sh up
  ./deploy-vps.sh logs backend
EOF
}

COMMAND="${1:-}"
shift || true

case "${COMMAND}" in
  up)
    compose up -d --build "$@"
    ;;
  down)
    compose down "$@"
    ;;
  restart)
    compose down
    compose up -d --build "$@"
    ;;
  pull)
    git -C "${ROOT_DIR}" pull --ff-only
    compose up -d --build "$@"
    ;;
  build)
    compose build "$@"
    ;;
  logs)
    compose logs -f "$@"
    ;;
  ps)
    compose ps "$@"
    ;;
  config)
    compose config "$@"
    ;;
  *)
    usage
    exit 1
    ;;
esac
