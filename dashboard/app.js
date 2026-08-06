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

// WebSocket Connection
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);
stompClient.debug = () => {}; // Disable debug logging

stompClient.connect({}, function (frame) {
    connectionDot.className = 'dot connected';
    connectionText.textContent = 'Connected to Control Plane';

    stompClient.subscribe('/topic/metrics', function (message) {
        const metric = JSON.parse(message.body);
        processMetric(metric);
    });
}, function(error) {
    connectionDot.className = 'dot disconnected';
    connectionText.textContent = 'Disconnected';
});

// Process incoming metrics
function processMetric(metric) {
    requestsCount++;
    totalLatency += metric.response_time;
    if (metric.status === 'failure' || metric.status_code >= 500) {
        errorsCount++;
    }
}

// Update UI every second
setInterval(() => {
    if (requestsCount === 0) return;

    const now = Date.now();
    const elapsedSec = (now - lastReset) / 1000;
    
    const rps = Math.round(requestsCount / elapsedSec);
    const avgLatency = Math.round(totalLatency / requestsCount);
    const errorRate = ((errorsCount / requestsCount) * 100).toFixed(2);

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

// API Controls
btnStart.addEventListener('click', async () => {
    try {
        const res = await fetch('http://localhost:8080/api/tests/start', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ name: 'UI Triggered Test', targetUsers: 100, spawnRate: 10, slaMaxP99Latency: 500, slaMaxErrorRate: 1.0 })
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
        await fetch(`http://localhost:8080/api/tests/${currentTestId}/stop`, { method: 'POST' });
        btnStart.disabled = false;
        btnStop.disabled = true;
        isRunning = false;
    } catch(e) {
        console.error(e);
    }
});

// Chaos Engineering Controls (direct to Target API for simplicity, assuming it's on port 8081)
latencySlider.addEventListener('input', (e) => {
    const val = e.target.value;
    latencyValue.textContent = `${val} ms`;
});

latencySlider.addEventListener('change', async (e) => {
    const val = e.target.value;
    try {
        await fetch(`http://localhost:8081/api/products/chaos/delay?ms=${val}`, { method: 'POST' });
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
        await fetch(`http://localhost:8081/api/products/chaos/errors?active=${val > 0}&ratePercent=${val}`, { method: 'POST' });
    } catch(e) {
        console.error(e);
    }
});
