from locust import HttpUser, task, between, events
import time
import json
import redis
import os
import threading
from prometheus_client import start_http_server, Counter, Gauge, Histogram

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
redis_client = None

# Prometheus exporter configuration
PROM_PORT = int(os.getenv("PROM_PORT", 9189))

# Prometheus metrics exposed at /metrics on PROM_PORT
REQUESTS_TOTAL = Counter(
    "locust_requests_total", "Total HTTP requests", ["method", "status"]
)
FAILURES_TOTAL = Counter(
    "locust_failures_total", "Failed HTTP requests", ["method"]
)
RESPONSE_TIME = Histogram(
    "locust_response_time_seconds",
    "HTTP response time in seconds",
    ["method"],
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
)
CURRENT_RPS = Gauge("locust_current_rps", "Current requests per second")
ACTIVE_USERS = Gauge("locust_active_users", "Active simulated users")
FAIL_RATIO = Gauge("locust_failure_ratio", "Request failure ratio")

# Holds a reference to the Locust runner once a test starts
_runner = {"ref": None}

# Per-request latency samples accumulate here and are flushed to Redis as
# periodic aggregates (locust_aggregates) so the Control Plane can compute
# real p50/p95/p99/min/max percentile math over a sliding window.
_latencies_buffer = []
_buffer_lock = threading.Lock()
_total_requests = 0
_total_errors = 0

AGG_BATCH_SECONDS = float(os.getenv("AGG_BATCH_SECONDS", "2"))


def _record_sample(response_time_ms, failed):
    global _total_requests, _total_errors
    with _buffer_lock:
        _latencies_buffer.append(float(response_time_ms))
        _total_requests += 1
        if failed:
            _total_errors += 1


def _publish_aggregates_to_redis():
    """Flush buffered latency samples to the locust_aggregates channel periodically."""
    global _total_requests, _total_errors
    while True:
        time.sleep(AGG_BATCH_SECONDS)
        with _buffer_lock:
            batch = _latencies_buffer[:]
            total_requests = _total_requests
            total_errors = _total_errors
            _latencies_buffer.clear()
            # Reset cumulative counters so each published aggregate carries only
            # the requests/errors observed during THIS flush interval. Without
            # this, later aggregates over-report and the windowed error rate
            # computed by the Control Plane drifts upward batch after batch.
            _total_requests = 0
            _total_errors = 0

        if not batch:
            continue

        payload = {
            # Milliseconds, matching the Control Plane's window arithmetic
            # (System.currentTimeMillis() / AggregateWindow.windowMs).
            "timestamp": int(time.time() * 1000),
            "sample_count": len(batch),
            "latencies": batch,
            "error_count": total_errors,
            "total_requests": total_requests,
        }
        try:
            client = get_redis()
            if client:
                client.publish("locust_aggregates", json.dumps(payload))
        except Exception:
            pass


def _publish_stats_loop():
    """Periodically refresh gauge values so Prometheus can scrape them."""
    while True:
        runner = _runner["ref"]
        if runner is not None:
            try:
                stats = runner.stats.total
                CURRENT_RPS.set(stats.current_rps or 0)
                ACTIVE_USERS.set(getattr(runner, "user_count", 0) or 0)
                FAIL_RATIO.set(stats.fail_ratio or 0)
            except Exception:
                pass
        time.sleep(1)


_exporter_started = False

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    global _exporter_started
    _runner["ref"] = environment.runner
    
    if not _exporter_started:
        try:
            # start_http_server runs in a background daemon thread (non-blocking)
            start_http_server(PROM_PORT)
            print(f"Prometheus exporter listening on :{PROM_PORT}/metrics")
            threading.Thread(target=_publish_stats_loop, daemon=True).start()
            threading.Thread(target=_publish_aggregates_to_redis, daemon=True).start()
            _exporter_started = True
        except Exception as e:
            print("Failed to start Prometheus exporter:", e)


def get_redis():
    global redis_client
    if redis_client is None:
        try:
            redis_client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0, socket_timeout=2.0)
        except Exception:
            redis_client = None
    return redis_client

@events.request.add_listener
def on_request(request_type, name, response_time, response_length, response, context, exception, start_time, url, **kwargs):
    status = "success" if exception is None else "failure"
    status_code = 0
    if exception is None and response is not None:
        try:
            status_code = getattr(response, 'status_code', 200) or 0
        except Exception:
            status_code = 0

    method = request_type or "unknown"

    # Record the raw latency for the SLA aggregate window.
    _record_sample(response_time, status == "failure")

    # Always record Prometheus metrics (independent of Redis availability)
    REQUESTS_TOTAL.labels(method=method, status=str(status_code)).inc()
    if status == "failure":
        FAILURES_TOTAL.labels(method=method).inc()
    RESPONSE_TIME.labels(method=method).observe(response_time / 1000.0)

    metric = {
        "timestamp": time.time(),
        "request_type": request_type,
        "name": name,
        "response_time": response_time,
        "status_code": status_code,
        "status": status,
    }

    # Publish to Redis for the real-time dashboard (best-effort)
    try:
        client = get_redis()
        if client:
            client.publish("locust_metrics", json.dumps(metric))
    except Exception:
        pass

class MockApiUser(HttpUser):
    wait_time = between(1, 2)

    @task(3)
    def get_products(self):
        self.client.get("/api/products", name="GET /api/products")

    @task(1)
    def create_product(self):
        payload = {
            "name": "Load Test Product",
            "price": 99.99
        }
        self.client.post("/api/products", json=payload, name="POST /api/products")
