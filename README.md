# JaCoCo OTel Agent

A Java agent that reads live code coverage from a running application's JaCoCo agent and exports it as OpenTelemetry metrics.

## How it works

```
Running JVM
├── Your application
├── JaCoCo agent                  ──► registers org.jacoco:type=Runtime MBean (always)
└── jacoco-otel-agent              ──► reads MBean ──► pushes to OTel collector
         │
         ├── If app already has OTel configured (zero-code agent / manual SDK):
         │     Inherits GlobalOpenTelemetry — reuses all tags, endpoints, etc.
         └── If no OTel present:
               Creates its own OTel SDK using JACOCO_OTEL_* env vars / config file
```

### Metrics exported

| Metric name | Description | Values |
|---|---|---|
| `jacoco.coverage.instructions.ratio` | Instruction coverage | 0.0 – 1.0 |
| `jacoco.coverage.branches.ratio` | Branch coverage | 0.0 – 1.0 |
| `jacoco.coverage.lines.ratio` | Line coverage | 0.0 – 1.0 |
| `jacoco.coverage.methods.ratio` | Method coverage | 0.0 – 1.0 |
| `jacoco.coverage.classes.ratio` | Class coverage | 0.0 – 1.0 |

All metrics carry the attribute `scope=total`.

---

## Project layout

```
jacoco-otel/
├── jacoco-otel-agent/          Java agent source (JDK 8+ compatible)
│   ├── Dockerfile              Builds the agent jar using Docker (JDK 8)
│   └── src/
├── demo-app/                   Spring Boot demo application
│   └── Dockerfile
├── docker-compose.yml          LGTM stack + demo app
├── jacoco-otel-agent.properties.example
└── README.md
```

---

## Prerequisites

- **Docker** and **Docker Compose** (no local JDK required)

---

## Quick start

### Step 1 — Start the full stack

```bash
docker compose up --build
```

Docker builds everything from source — the coverage agent jar (compiled against JDK 8) and the demo app — so no local Java installation or pre-built jars are needed.

This starts:
| Service | URL | Credentials |
|---|---|---|
| Grafana | http://localhost:3000 | admin / admin |
| OTLP HTTP collector | http://localhost:4318 | — |
| OTLP gRPC collector | http://localhost:4317 | — |
| Demo app | http://localhost:8080 | — |

Wait ~30 seconds for all services to be healthy (the demo app logs `Registered 5 OTel gauge metrics` when the first export fires).

### Step 2 — Generate coverage traffic

Call the demo endpoints to exercise different parts of the code:

```bash
# GreetingService — HIGH coverage
curl "http://localhost:8080/api/greet?name=Alice"
curl "http://localhost:8080/api/greet?name=Bob&title=Dr"
curl "http://localhost:8080/api/farewell?name=Alice"

# CalculatorService — MEDIUM coverage (add/subtract only)
curl "http://localhost:8080/api/calculate?op=add&a=5&b=3"
curl "http://localhost:8080/api/calculate?op=sub&a=10&b=4"

# ValidationService — LOW branch coverage (happy path only)
curl "http://localhost:8080/api/validate?email=user@example.com"

# Health check
curl "http://localhost:8080/actuator/health"
```

### Step 3 — View metrics in Grafana

1. Open http://localhost:3000 and log in as `admin` / `admin`
2. Go to **Explore** → select **Prometheus** datasource
3. Run these PromQL queries:

```promql
jacoco_coverage_lines_ratio{scope="total"}
jacoco_coverage_branches_ratio{scope="total"}
jacoco_coverage_instructions_ratio{scope="total"}
jacoco_coverage_methods_ratio{scope="total"}
jacoco_coverage_classes_ratio{scope="total"}
```

> **Note:** OTel metric names use dots; Prometheus converts them to underscores automatically.

You should see values between 0 and 1 updating approximately every 30 seconds.

---

## Configuration reference

### Agent arguments

Attach to any JVM:

```bash
-javaagent:jacoco-otel-agent.jar                              # all defaults
-javaagent:jacoco-otel-agent.jar=config=/path/file.properties # config file
-javaagent:jacoco-otel-agent.jar=interval=60,endpoint=http://localhost:4318
```

### Properties (file or inline) and environment variables

**Collection timing:**

| Property | Env var | Default | Description |
|---|---|---|---|
| `coverage.interval` | `JACOCO_OTEL_INTERVAL` | `60` | Export interval (seconds) |
| `coverage.grace.period` | `JACOCO_OTEL_GRACE_PERIOD` | `30` | Startup delay before first collection (seconds) |

**OTel resolution mode:**

| Property | Env var | Default | Description |
|---|---|---|---|
| `otel.mode` | `JACOCO_OTEL_MODE` | `auto` | `auto`, `inherit`, or `standalone` (see below) |

**Standalone SDK settings** (used when mode is `standalone`, or when `auto` finds no existing OTel):

| Property | Env var | Default | Description |
|---|---|---|---|
| `otel.endpoint` | `JACOCO_OTEL_ENDPOINT` | — | OTLP base URL (e.g. `http://localhost:4318`) |
| `otel.service.name` | `JACOCO_OTEL_SERVICE_NAME` | `unknown-service` | Service name reported in metrics |
| `otel.protocol` | `JACOCO_OTEL_PROTOCOL` | `http/protobuf` | `http/protobuf` or `grpc` |
| `otel.headers` | `JACOCO_OTEL_HEADERS` | — | Extra headers: `key=val,key=val` |

In `inherit` mode, `otel.*` settings are ignored — endpoint, service name, and all resource attributes come from the app's own OTel setup.

### How OTel is resolved

The `otel.mode` property controls how the agent finds or creates an OTel SDK:

| Mode | Behaviour |
|---|---|
| `auto` (default) | Checks OTel env vars and `GlobalOpenTelemetry` state. Uses **inherit** if either signals an existing SDK; falls back to **standalone** otherwise. |
| `inherit` | Always uses `GlobalOpenTelemetry.get()`. All resource attributes, tags, and exporter config come from the app's existing OTel setup. Use when auto-detection is unreliable. |
| `standalone` | Always creates the agent's own OTel SDK from `otel.*` config, regardless of what the app has configured. |

**Auto-detection checks (in order):**

1. Are standard OTel env vars set (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, etc.)? → **inherit**
2. Has the app already registered a non-noop `GlobalOpenTelemetry`? → **inherit**
3. None of the above → **standalone** (creates its own SDK using `otel.*` settings above)

### JaCoCo requirement

The JaCoCo agent must be attached. The coverage agent reads data directly via JaCoCo's `RT` API (no JMX required). Use `output=none` to skip writing a `.exec` file at shutdown since we read data live:

```bash
-javaagent:jacoco-agent.jar=output=none
```

Any other valid output mode (`file`, `tcpserver`, `tcpclient`) also works — the RT API is available regardless of the `output` setting.

---

## Testing all three modes with the demo app

The demo app supports all three `otel.mode` values without any code changes — just pass different environment variables to `docker run` or override them in `docker-compose.yml`.

Start the LGTM stack first (it provides the OTel collector and Grafana):

```bash
docker compose up lgtm
```

Build the demo image once (context is the repo root):

```bash
docker build -t demo-app -f demo-app/Dockerfile .
```

---

### Mode 1: `standalone` (default for the demo)

The agent creates its own OTel SDK and pushes directly to the collector using `JACOCO_OTEL_ENDPOINT`. This is what the normal `docker compose up` runs.

```bash
docker run --rm -p 8080:8080 \
  -e JACOCO_OTEL_MODE=standalone \
  -e JACOCO_OTEL_ENDPOINT=http://host.docker.internal:4318 \
  -e JACOCO_OTEL_SERVICE_NAME=demo-app-standalone \
  -e JACOCO_OTEL_INTERVAL=30 \
  -e JACOCO_OTEL_GRACE_PERIOD=10 \
  demo-app
```

**Expected log lines:**

```
[jacoco-otel-agent] OTel mode: standalone (forced) — creating agent's own SDK.
[jacoco-otel-agent] Standalone OTel SDK created. Endpoint: http://host.docker.internal:4318, service: demo-app-standalone
[jacoco-otel-agent] Registered 5 OTel gauge metrics (jacoco.coverage.*.ratio)
```

---

### Mode 2: `auto` — detects OTel env vars → inherit

Set standard OTel env vars (`OTEL_*`) instead of `JACOCO_OTEL_*` ones. The agent sees them during auto-detection and switches to inherit mode, using the `OTEL_EXPORTER_OTLP_ENDPOINT` the app's SDK would use.

```bash
docker run --rm -p 8080:8080 \
  -e JACOCO_OTEL_MODE=auto \
  -e JACOCO_OTEL_INTERVAL=30 \
  -e JACOCO_OTEL_GRACE_PERIOD=10 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4318 \
  -e OTEL_SERVICE_NAME=demo-app-auto \
  demo-app
```

**Expected log lines:**

```
[jacoco-otel-agent] OTel environment variables detected
[jacoco-otel-agent] OTel mode: inherit — existing OTel configuration detected; ...
[jacoco-otel-agent] Registered 5 OTel gauge metrics (jacoco.coverage.*.ratio)
```

> The agent inherits `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SERVICE_NAME` from the environment via `GlobalOpenTelemetry`. No `JACOCO_OTEL_ENDPOINT` needed.

---

### Mode 3: `inherit` (forced)

Force inherit mode explicitly. Useful when you know the app has OTel configured and you don't want the agent to second-guess it. The demo app doesn't ship its own OTel SDK, so `GlobalOpenTelemetry` will return a noop — metrics won't actually be exported, but you can confirm the mode is respected in the logs.

To see a working inherit scenario, set standard OTel env vars alongside `mode=inherit`:

```bash
docker run --rm -p 8080:8080 \
  -e JACOCO_OTEL_MODE=inherit \
  -e JACOCO_OTEL_INTERVAL=30 \
  -e JACOCO_OTEL_GRACE_PERIOD=10 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4318 \
  -e OTEL_SERVICE_NAME=demo-app-inherit \
  demo-app
```

**Expected log lines:**

```
[jacoco-otel-agent] OTel mode: inherit (forced) — using existing OTel configuration; ...
[jacoco-otel-agent] Registered 5 OTel gauge metrics (jacoco.coverage.*.ratio)
```

> If `JACOCO_OTEL_ENDPOINT` is also set, the agent will log a notice that it is not being used, since the export destination comes from the inherited OTel setup.

---

### Verifying in Grafana

After generating some traffic with any mode:

```bash
curl "http://localhost:8080/api/greet?name=Alice"
curl "http://localhost:8080/api/calculate?op=add&a=5&b=3"
curl "http://localhost:8080/api/validate?email=user@example.com"
```

Open Grafana at http://localhost:3000 (admin / admin) → **Explore** → **Prometheus**, then query by service name:

```promql
jacoco_coverage_lines_ratio{service_name="demo-app-standalone"}
jacoco_coverage_lines_ratio{service_name="demo-app-auto"}
jacoco_coverage_lines_ratio{service_name="demo-app-inherit"}
```

Each run appears as a separate `service_name` label so you can compare them side by side.

---

## Attaching to an existing running application

The agent can be attached dynamically (without restarting) using the Java Attach API:

```bash
# Find the PID
jps -l

# Attach the agent
java -cp jacoco-otel-agent.jar \
     io.jacoco.otel.attach.AgentAttacher \
     <pid> \
     "config=/path/to/config.properties"
```

Or via tools like [byte-buddy-agent](https://github.com/raphw/byte-buddy) or `jattach`:

```bash
jattach <pid> load instrument false \
  "jacoco-otel-agent.jar=config=/path/to/config.properties"
```

---

## Scenario: app already uses the OTel zero-code agent

When the target app runs with the OTel Java zero-code agent, the coverage agent automatically detects this and switches to inherit mode — no extra configuration needed:

```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -javaagent:jacoco-agent.jar=output=none \
  -javaagent:jacoco-otel-agent.jar \
  -Dotel.service.name=my-app \
  -Dotel.exporter.otlp.endpoint=http://collector:4318 \
  -jar my-app.jar
```

No `JACOCO_OTEL_*` configuration needed — the coverage agent inherits the service name, endpoint, resource attributes, and all other OTel settings from the zero-code agent.

---

## Deploy the LGTM stack only (without the demo app)

If you want to test against your own application:

```bash
docker run -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm:latest
```

Then add the two agents to your application's JVM startup and point `JACOCO_OTEL_ENDPOINT` at `http://localhost:4318`.

---

## Building the demo app independently

The demo-app Dockerfile uses the repo root as its build context so it can build the agent and app together. Build and run it directly with:

```bash
# Build (context must be the repo root)
docker build -t demo-app -f demo-app/Dockerfile .

# Run against an already-running LGTM stack
docker run --rm -p 8080:8080 \
  -e JACOCO_OTEL_ENDPOINT=http://host.docker.internal:4318 \
  -e JACOCO_OTEL_SERVICE_NAME=demo-app \
  demo-app
```

## Extracting the agent jar for use with your own application

If you want to attach the agent to an application outside of Docker, build and export just the jar:

```bash
docker build \
  -f jacoco-otel-agent/Dockerfile \
  --target export \
  -o ./agents \
  ./jacoco-otel-agent
# Produces: ./agents/jacoco-otel-agent.jar
```

Then add it to your JVM startup alongside the JaCoCo agent:

```bash
java \
  -javaagent:/path/to/jacoco-agent.jar=output=none \
  -javaagent:/path/to/jacoco-otel-agent.jar \
  -jar your-app.jar
```

---

## Troubleshooting

### No metrics appear in Grafana

1. Check demo-app logs for `[jacoco-otel-agent]` lines:
   ```bash
   docker compose logs demo-app
   ```
2. Confirm JaCoCo MBean is registered:
   ```
   [jacoco-otel-agent] Read execution data: N class(es) tracked by JaCoCo
   ```
3. Confirm OTel mode was detected correctly:
   ```
   [jacoco-otel-agent] OTel mode: standalone — no existing OTel configuration detected.
   ```
4. Confirm gauge registration:
   ```
   [jacoco-otel-agent] Registered 5 OTel gauge metrics (jacoco.coverage.*.ratio)
   ```

### "JaCoCo agent RT not accessible" warning

The JaCoCo agent is not attached or its classes are not visible. Check that:
```bash
-javaagent:jacoco-agent.jar=output=none
```
is present in the JVM startup command and comes **before** `jacoco-otel-agent.jar` in the argument list. The grace period (`JACOCO_OTEL_GRACE_PERIOD`) exists for this reason — increase it if JaCoCo takes longer to start than the default 20 seconds.

### Coverage stays at 0.0

No traffic has hit the application yet, or JaCoCo is excluding your classes. Call the API endpoints first, then wait for the next collection interval.
