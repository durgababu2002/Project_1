// Determine API base URL dynamically.
// When served by the Control Plane (port 8080) all API/WS calls are same-origin;
// otherwise (standalone dashboard from another port or file://) target localhost:8080.
const BASE_URL = (window.location.protocol.startsWith('http') && window.location.port === '8080')
    ? window.location.origin
    : 'http://localhost:8080';

const TARGET_API_URL = 'http://localhost:8081';

// Setup Chart.js
const ctx = document.getElementById('metricsChart').getContext('2d');
Chart.defaults.color = '#94a3b8';
Chart.defaults.font.family = 'Inter';

const metricsChart = new Chart(ctx, {
    type: 'line',
    data: {
        labels: [],
        datasets: [
            {
                label: 'Response Time (ms)',
                data: [],
                borderColor: '#3b82f6',
                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                borderWidth: 2,
                fill: true,
                tension: 0.4
            }
        ]
    },
    options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: { display: false }
        },
        scales: {
            y: {
                beginAtZero: true,
                grid: { color: 'rgba(255, 255, 255, 0.05)' }
            },
            x: {
                grid: { display: false },
                ticks: { maxTicksLimit: 10 }
            }
        },
        animation: false
    }
});

// UI Elements
const connectionDot = document.getElementById('connectionDot');
const connectionText = document.getElementById('connectionText');
const btnStart = document.getElementById('btnStart');
const btnStop = document.getElementById('btnStop');

const latencySlider = document.getElementById('latencySlider');
const latencyValue = document.getElementById('latencyValue');
const errorSlider = document.getElementById('errorSlider');
const errorValue = document.getElementById('errorValue');

const rpsValue = document.getElementById('rpsValue');
const latencyAvgValue = document.getElementById('latencyAvgValue');
const errorRateValue = document.getElementById('errorRateValue');

let isRunning = false;
let currentTestId = null;

// Metrics aggregation
let requestsCount = 0;
let errorsCount = 0;
let totalLatency = 0;
let lastReset = Date.now();

let stompClient = null;
let wsSocket = null;
let reconnectTimer = null;

function connectWebSocket() {
    // Cancel any pending reconnect before opening a new connection
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }

    // Tear down any stale connection first — prevents stacking duplicate
    // SockJS clients across repeated reconnect cycles
    try { if (stompClient) { stompClient.disconnect(function() {}); } } catch(e) {}
    try { if (wsSocket) { wsSocket.close(); } } catch(e) {}
    stompClient = null;
    wsSocket = null;

    try {
        wsSocket = new SockJS(`${BASE_URL}/ws`);
        stompClient = Stomp.over(wsSocket);
        stompClient.debug = null; // Disable debug logs

        stompClient.connect({}, function (frame) {
            console.log("Connected to STOMP WebSocket server!");
            connectionDot.className = 'dot connected';
            connectionText.textContent = 'Connected to Control Plane';

            stompClient.subscribe('/topic/metrics', function (message) {
                try {
                    let metric = JSON.parse(message.body);
                    processMetric(metric);
                } catch(e) {
                    console.error("Failed to parse metric:", e);
                }
            });

            // Week 2: live SLA breach notifications from the validator
            stompClient.subscribe('/topic/alerts', function (message) {
                try {
                    const alert = JSON.parse(message.body);
                    showSlaAlert(alert);
                } catch(e) {
                    console.error("Failed to parse alert:", e);
                }
            });
        }, function(error) {
            console.error("STOMP connection error:", error);
            connectionDot.className = 'dot disconnected';
            connectionText.textContent = 'Disconnected (Retrying...)';
            reconnectTimer = setTimeout(connectWebSocket, 3000);
        });
    } catch(e) {
        console.error("WebSocket setup error:", e);
        connectionDot.className = 'dot disconnected';
        connectionText.textContent = 'Disconnected (Retrying...)';
        reconnectTimer = setTimeout(connectWebSocket, 3000);
    }
}

connectWebSocket();

// Process incoming metrics
function processMetric(metric) {
    if (typeof metric === 'string') {
        try {
            metric = JSON.parse(metric);
        } catch(e) {}
    }
    
    requestsCount++;
    const respTime = parseFloat(metric.response_time) || 0;
    totalLatency += respTime;
    
    if (metric.status === 'failure' || (metric.status_code && metric.status_code >= 500)) {
        errorsCount++;
    }
}

// Update UI every second
let isZeroed = false;
setInterval(() => {
    if (requestsCount === 0) {
        if (isZeroed) return;
        isZeroed = true;
    } else {
        isZeroed = false;
    }

    const now = Date.now();
    const elapsedSec = Math.max((now - lastReset) / 1000, 0.1);
    
    const rps = Math.round(requestsCount / elapsedSec);
    const avgLatency = Math.round(totalLatency / Math.max(requestsCount, 1));
    const errorRate = ((errorsCount / Math.max(requestsCount, 1)) * 100).toFixed(2);

    rpsValue.innerHTML = `${rps} <span class="unit">req/s</span>`;
    latencyAvgValue.innerHTML = `${avgLatency} <span class="unit">ms</span>`;
    errorRateValue.innerHTML = `${errorRate} <span class="unit">%</span>`;

    // Update Chart
    const timeLabel = new Date().toLocaleTimeString([], {hour: '2-digit', minute:'2-digit', second:'2-digit'});
    metricsChart.data.labels.push(timeLabel);
    metricsChart.data.datasets[0].data.push(avgLatency);

    if (metricsChart.data.labels.length > 50) {
        metricsChart.data.labels.shift();
        metricsChart.data.datasets[0].data.shift();
    }
    metricsChart.update();

    // Reset aggregation
    requestsCount = 0;
    errorsCount = 0;
    totalLatency = 0;
    lastReset = now;

}, 1000);

// SLA engine: read the currently configured policy into the panel
function updateSlaLimits() {
    const p99 = parseFloat(document.getElementById('slaLimitP99Input').value) || 500;
    const err = parseFloat(document.getElementById('slaLimitErrInput').value) || 1;
    document.getElementById('slaLimitP99').textContent = p99;
    document.getElementById('slaLimitErr').textContent = err;
    return { p99, err };
}
updateSlaLimits();

// SLA engine: poll true percentiles computed from Redis aggregates
async function refreshSlaStatus() {
    try {
        const res = await fetch(`${BASE_URL}/api/sla/status`);
        if (!res.ok) return;
        const data = await res.json();
        const ms = (v) => `${Math.round(v * 10) / 10} <span class="unit">ms</span>`;
        document.getElementById('slaP50').innerHTML = ms(data.p50);
        document.getElementById('slaP95').innerHTML = ms(data.p95);
        document.getElementById('slaP99').innerHTML = ms(data.p99);
        document.getElementById('slaMin').innerHTML = ms(data.min);
        document.getElementById('slaMax').innerHTML = ms(data.max);
        document.getElementById('slaErrRate').innerHTML = `${(data.error_rate * 100).toFixed(2)} <span class="unit">%</span>`;
        document.getElementById('slaSamples').textContent = data.sample_count;
    } catch(e) {
        // Control Plane not reachable yet; keep last rendered values.
    }
}
setInterval(refreshSlaStatus, 2000);

// SLA engine: breach banner pushed over /topic/alerts
function showSlaAlert(alert) {
    document.getElementById('alertReason').textContent = alert.reason || 'SLA limits exceeded';
    document.getElementById('alertActions').textContent =
        'Auto-remediation: ' + (alert.actions || []).join(' · ');
    document.getElementById('alertBanner').classList.remove('hidden');
    // The validator already stopped the load & rolled back chaos server-side.
    if (isRunning) {
        btnStart.disabled = false;
        btnStop.disabled = true;
        isRunning = false;
    }
}

document.getElementById('alertDismiss').addEventListener('click', () => {
    document.getElementById('alertBanner').classList.add('hidden');
});

// API Controls
btnStart.addEventListener('click', async () => {
    try {
        const sla = updateSlaLimits();
        const res = await fetch(`${BASE_URL}/api/tests/start`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ name: 'UI Triggered Test', targetUsers: 100, spawnRate: 10, slaMaxP99Latency: sla.p99, slaMaxErrorRate: sla.err })
        });
        const data = await res.json();
        currentTestId = data.id;
        
        btnStart.disabled = true;
        btnStop.disabled = false;
        isRunning = true;

        // Clear chart
        metricsChart.data.labels = [];
        metricsChart.data.datasets[0].data = [];
        metricsChart.update();
    } catch(e) {
        console.error(e);
        alert("Failed to start test. Is Control Plane running?");
    }
});

btnStop.addEventListener('click', async () => {
    if(!currentTestId) return;
    try {
        await fetch(`${BASE_URL}/api/tests/${currentTestId}/stop`, { method: 'POST' });
        btnStart.disabled = false;
        btnStop.disabled = true;
        isRunning = false;
    } catch(e) {
        console.error(e);
    }
});

// Chaos Engineering Controls
latencySlider.addEventListener('input', (e) => {
    const val = e.target.value;
    latencyValue.textContent = `${val} ms`;
});

latencySlider.addEventListener('change', async (e) => {
    const val = e.target.value;
    try {
        await fetch(`${TARGET_API_URL}/api/products/chaos/delay?ms=${val}`, { method: 'POST' });
    } catch(e) {
        console.error(e);
    }
});

errorSlider.addEventListener('input', (e) => {
    const val = e.target.value;
    errorValue.textContent = `${val}%`;
});

errorSlider.addEventListener('change', async (e) => {
    const val = e.target.value;
    try {
        await fetch(`${TARGET_API_URL}/api/products/chaos/errors?active=${val > 0}&ratePercent=${val}`, { method: 'POST' });
    } catch(e) {
        console.error(e);
    }
});
