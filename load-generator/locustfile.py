from locust import HttpUser, task, between, events
import time
import json
import redis
import os

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
redis_client = None

try:
    redis_client = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0)
except Exception as e:
    print(f"Failed to connect to Redis: {e}")

@events.request.add_listener
def on_request(request_type, name, response_time, response_length, response, context, exception, start_time, url, **kwargs):
    if not redis_client:
        return
        
    status = "success" if exception is None else "failure"
    status_code = response.status_code if response else 0
    
    metric = {
        "timestamp": time.time(),
        "request_type": request_type,
        "name": name,
        "response_time": response_time,
        "status_code": status_code,
        "status": status,
    }
    
    try:
        redis_client.publish("locust_metrics", json.dumps(metric))
    except Exception as e:
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
