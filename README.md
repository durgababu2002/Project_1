# API Resilience Engine

A high-concurrency API stress-testing and resilience validation engine.

## 🏗️ Architecture

```mermaid
graph TD
    UI[Telemetry Dashboard <br/> Vanilla JS + Chart.js]
    CP[Control Plane <br/> Spring Boot + WebSockets]
    LG[Load Generator <br/> Python Locust]
    TAPI[Target API <br/> Spring Boot]
    DB[(PostgreSQL <br/> Test Configs & SLAs)]
    CACHE[(Redis <br/> Real-time Metrics)]

    UI <-->|WebSockets :8080/ws| CP
    UI -->|Chaos API calls :8081| TAPI
    CP <-->|Persist SLAs & States| DB
    CP -->|Listen to Metrics| CACHE
    LG -->|Push Metrics (Pub/Sub)| CACHE
    LG -->|HTTP Bombardment :8081| TAPI
```

## 🛠️ Tech Stack

- **Target API:** Java (Spring Boot) - A mock microservice with fault-injection capabilities.
- **Control Plane:** Java (Spring Boot) - Orchestrates tests, evaluates SLAs, and streams metrics via WebSockets.
- **Load Generator:** Python (Locust) - Generates concurrent HTTP requests and exports latencies to Redis.
- **Telemetry Dashboard:** Vanilla HTML/CSS/JS - Responsive, glassmorphic UI for live monitoring.
- **Databases:** PostgreSQL (Relational persistence) and Redis (In-memory Pub/Sub message broker).
- **Infrastructure:** Docker & Docker Compose (Multi-stage builds).

## 🚀 Getting Started

### Prerequisites
- **Docker Desktop** (Make sure Docker Engine is running)

> **Note:** Because the project utilizes Docker multi-stage builds, you do not need to install Java, Python, PostgreSQL, or Redis natively on your machine.

### Installation

1. Clone or navigate to the repository directory:
   ```bash
   cd Project_1
   ```
2. Build and start the cluster using Docker Compose:
   ```bash
   docker compose up --build
   ```
   *(This may take a few minutes the first time as it downloads base images and compiles the Spring Boot jar files).*

## 📊 Using the Dashboard

Once the Docker containers are up and running, open the following file in your browser:

`dashboard/index.html`

### 1. Starting a Load Test
- Click the **Start Load Test** button on the Control Plane panel.
- The indicator will turn green when connected.
- You will immediately see the throughput ($req/s$), average latency, and error rate populate in real-time.
- The line chart will draw the live response time distribution.

### 2. Chaos Engineering (Injecting Faults)
While the load test is running, you can test the system's resilience by actively degrading the Target API's performance:

- **Inject DB Latency:** Use the slider to artificially introduce database sleep times (e.g., 300ms). Watch the latency metrics spike in real-time on the chart.
- **Inject Errors:** Slide the error percentage up (e.g., 10%) to force the Target API to throw random `500 Internal Server Errors`. The error rate card on the dashboard will turn red, simulating a failing service level agreement (SLA).

## 📈 Observability (Prometheus + Grafana)

The platform ships with a full cloud-native observability stack, automatically
provisioned and wired up by Docker Compose.

| Service | URL | Purpose |
|---------|-----|---------|
| **Grafana** | [http://localhost:3000](http://localhost:3000) | Dashboards (login `admin` / `admin`) |
| **Prometheus** | [http://localhost:9090](http://localhost:9090) | Metrics store, query + alerts |

Metrics scraped automatically:
- **Target API & Control Plane** → Spring Boot **Actuator + Micrometer** at
  `/actuator/prometheus` (HTTP latency histograms, JVM, custom chaos gauges).
- **Load Generator** → custom **Locust Prometheus exporter** at `:9189/metrics`
  (request counters, response-time histogram, RPS, active users, failure ratio).

The **"Resilience Engine Overview"** dashboard auto-loads on Grafana startup and
includes: throughput, error rate, p50/p95/p99 latency, chaos injection state,
Locust RPS, JVM heap, and CPU usage.

Prometheus alert rules (see `monitoring/prometheus/alerts.yml`) fire on high 5xx
error rate, high p99 latency, and high load failure ratio. The Grafana/Prometheus
config is fully **infrastructure-as-code**: nothing is clicked-by-hand; everything
lives under the `monitoring/` directory.

## 🛡️ SLA Engine (Auto-Remediation)

The Control Plane now evaluates every running load test against a **real SLA
policy** computed from the live latency distribution (no mock values):

- **Real percentiles:** the load generator publishes raw response-time samples to
  Redis every 2s; the Control Plane keeps a 30s sliding window and computes
  **p50 / p95 / p99 / min / max / error-rate** with nearest-rank math
  (`/api/sla/status`).
- **Editable policy:** set max p99 latency + max error rate before starting a test
  from the dashboard (persisted with the test).
- **On breach, automatically:** marks the test `FAILED_SLA` → **stops the Locust
  swarm** → **rolls back injected chaos** on the Target API → fires a **webhook
  alert** (`SLA_WEBHOOK_URL`, e.g. Slack/Teams/PagerDuty/n8n) → pushes a live
  breach banner to every dashboard over `/topic/alerts`.
- **Verified by JUnit + Testcontainers**: percentile math, sliding-window expiry,
  Redis aggregate ingestion, and the SLA decision logic are all covered by the
  `control-plane` test suite.

## 🔌 API Reference

If you wish to interact with the services directly via Postman or Curl:

### Target API (`localhost:8081`)
- `GET /api/products` - Fetch all mock products.
- `POST /api/products` - Create a new mock product.
- `POST /api/products/chaos/delay?ms=500` - Inject a 500ms delay into all endpoints.
- `POST /api/products/chaos/errors?active=true&ratePercent=20` - Force a 20% failure rate.

### Control Plane (`localhost:8080`)
- `GET /api/tests` - Get historical load tests.
- `POST /api/tests/start` - Register a new running load test state.
- `POST /api/tests/{id}/stop` - Mark a test as completed.

## 🤝 Future Enhancements
- Distributed Locust workers across a Kubernetes cluster.
- Long-term historical metrics analysis (ML anomaly detection / SLA prediction).
- ChatOps: natural-language commands to run tests and read SLA reports (Week 6).
