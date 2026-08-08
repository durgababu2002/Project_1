from locust import HttpUser, task, between, events
import time
import json
import redis
import os

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
redis_client = None

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
    client = get_redis()
    if not client:
        return
        
    status = "success" if exception is None else "failure"
    status_code = 0
    if exception is None and response is not None:
        try:
            status_code = getattr(response, 'status_code', 200) or 0
        except Exception:
            status_code = 0
    
    metric = {
        "timestamp": time.time(),
        "request_type": request_type,
        "name": name,
        "response_time": response_time,
        "status_code": status_code,
        "status": status,
    }
    
    try:
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
