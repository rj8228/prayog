# syntax=docker/dockerfile:1.7
# Simulated traders (S13): an ordinary API client of the exchange, packaged with the SDK it uses.
# Built from the repository root: docker build -f deploy/docker/agents.Dockerfile .

FROM ghcr.io/astral-sh/uv:0.12.22 AS uv

FROM python:3.12.15-slim
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
COPY sdk/python sdk/python
COPY services/agents services/agents
# Exact versions from uv.lock; no dev tools in the image.
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
RUN useradd --system --uid 10002 agents
USER agents
CMD ["/app/.venv/bin/python", "-m", "prayog_agents"]
