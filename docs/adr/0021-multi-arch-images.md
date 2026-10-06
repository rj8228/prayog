# 21. Multi-arch images: build once natively, one runtime stage per platform

Date: 2026-10-07

## Status

Accepted

## Context

The container contract (BUILD_PLAN section 7, #6) asks for images built for ARM and x86, non-root and tagged by git
commit. Development happens on an Apple M1 (arm64); CI and most servers are amd64. Pushing to a registry waits until
hosting is decided (16.1 #29).

## Decisions

1. **`make images`** builds all four images (exchange, post-trade, agents, web) with `docker buildx` for
   `linux/amd64,linux/arm64`, tagged `prayog/<image>:<short commit>`. They are not pushed. `PLATFORMS` and `TAG` can be
   overridden.
2. **Build stages on `$BUILDPLATFORM`.** Java jars and the web bundle are the same bytes on every platform, so Maven
   and pnpm run once, natively, and each platform's runtime stage only copies the result. Under QEMU emulation,
   compiling for the other platform would take many times longer.
3. **Agents are built per platform.** `uv sync` installs native wheels (numpy), which must match the target. It is
   small and only downloads wheels, so emulation is acceptable.
4. **CI job "Multi-arch images"** (QEMU + buildx) builds both platforms on every push.

## Testing

- Locally (M1): all four images built for both platforms in about 85 s with a warm cache. Run under amd64 emulation:
  the exchange's JVM starts (Temurin 21.0.12.1), and the agents image is `x86_64` and imports numpy 2.5.3.

## Trade-offs

- Builds are not pushed anywhere yet; the day a registry exists, `make images` gains `--push`.
