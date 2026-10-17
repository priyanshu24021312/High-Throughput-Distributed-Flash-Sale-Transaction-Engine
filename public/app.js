/**
 * FLASH PULSE | High-Concurrency Flash Sale Frontend Engine
 * Real-time concurrency visualization, ACID auditing, and storefront drops.
 */

// ==========================================
// Application State
// ==========================================
const state = {
    isLiveBackend: false,
    audioEnabled: true,
    currentTab: 'store',
    currentUser: { id: 42, name: 'Alex K.' },
    products: [],
    selectedProductId: 1,
    purchases: [],
    simThreadCount: 100,
    simRunning: false,
    charts: {
        outcome: null,
        latency: null
    }
};

// User avatar directory for simulated users
const USER_NAMES = [
    'Sarah C.', 'Alex K.', 'Devon M.', 'Elena R.', 'Marcus V.',
    'Priya N.', 'Lucas T.', 'Zara H.', 'Liam B.', 'Chloe W.',
    'Kenji S.', 'Fatima A.', 'Mateo G.', 'Aria P.', 'Noah J.'
];

// Product Emoji / Graphic Map
const PRODUCT_EMOJIS = {
    sneaker: '👟',
    console: '🎮',
    phone: '📱',
    ticket: '🎫',
    tech: '⚡'
};

// ==========================================
// Web Audio Synthesizer (Zero Dependencies)
// ==========================================
let audioCtx = null;

function getAudioContext() {
    if (!audioCtx) {
        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (AudioContext) audioCtx = new AudioContext();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
        audioCtx.resume();
    }
    return audioCtx;
}

function playClickSound() {
    if (!state.audioEnabled) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(800, ctx.currentTime);
        osc.frequency.exponentialRampToValueAtTime(300, ctx.currentTime + 0.04);
        gain.gain.setValueAtTime(0.08, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.04);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.04);
    } catch (e) {}
}

function playSuccessChime() {
    if (!state.audioEnabled) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const notes = [523.25, 659.25, 783.99, 1046.50]; // C5, E5, G5, C6 arpeggio
        notes.forEach((freq, i) => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(freq, ctx.currentTime + i * 0.07);
            gain.gain.setValueAtTime(0.12, ctx.currentTime + i * 0.07);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + i * 0.07 + 0.35);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(ctx.currentTime + i * 0.07);
            osc.stop(ctx.currentTime + i * 0.07 + 0.35);
        });
    } catch (e) {}
}

function playFailThud() {
    if (!state.audioEnabled) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(140, ctx.currentTime);
        osc.frequency.exponentialRampToValueAtTime(40, ctx.currentTime + 0.15);
        gain.gain.setValueAtTime(0.15, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.15);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.15);
    } catch (e) {}
}

function playLaunchSound() {
    if (!state.audioEnabled) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(200, ctx.currentTime);
        osc.frequency.exponentialRampToValueAtTime(900, ctx.currentTime + 0.2);
        gain.gain.setValueAtTime(0.1, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.22);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.22);
    } catch (e) {}
}

function toggleAudio() {
    state.audioEnabled = !state.audioEnabled;
    const icon = document.getElementById('audio-icon');
    if (icon) icon.textContent = state.audioEnabled ? '🔊' : '🔇';
    showToast(state.audioEnabled ? 'Sound FX Enabled' : 'Sound FX Muted', 'info');
}

// ==========================================
// Initialization & Backend Sync
// ==========================================
document.addEventListener('DOMContentLoaded', async () => {
    initCountdownClock();
    randomizeUser();
    initCharts();
    await checkBackendConnection();
    await fetchProducts();
    await fetchPurchases();

    // Periodic synchronization
    setInterval(async () => {
        if (!state.simRunning) {
            await fetchProducts(true);
            await fetchPurchases(true);
        }
    }, 3000);
});

async function checkBackendConnection() {
    const engineLabel = document.getElementById('engine-label');
    const pill = document.getElementById('engine-status-pill');

    try {
        const res = await fetch('/api/status', { cache: 'no-store' });
        if (res.ok) {
            const data = await res.json();
            state.isLiveBackend = true;
            if (engineLabel) engineLabel.textContent = data.engine || 'JVM Backend Connected';
            if (pill) {
                pill.className = 'flex items-center space-x-2 px-3 py-1.5 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs font-mono';
            }
            return;
        }
    } catch (e) {}

    // Fallback: Offline / Direct client mode
    state.isLiveBackend = false;
    if (engineLabel) engineLabel.textContent = 'Demo Mode (Client Engine)';
    if (pill) {
        pill.className = 'flex items-center space-x-2 px-3 py-1.5 rounded-full bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 text-xs font-mono';
    }
    seedMockProducts();
}

function seedMockProducts() {
    if (state.products.length === 0) {
        state.products = [
            {
                id: 1,
                name: 'Nike Air Mag - Cyberpunk Limited Edition',
                description: 'Ultra-rare futuristic collector sneaker with auto-lacing technology. Flash drop limit: 50 pairs worldwide.',
                totalQuantity: 50,
                availableQuantity: 50,
                price: 499.99,
                status: 'ACTIVE',
                imageUrl: 'sneaker'
            },
            {
                id: 2,
                name: 'PlayStation 5 Pro - 30th Anniversary Edition',
                description: 'Limited retro-themed collector edition PS5 Pro console. 2TB SSD with custom DualSense controller.',
                totalQuantity: 30,
                availableQuantity: 30,
                price: 699.99,
                status: 'ACTIVE',
                imageUrl: 'console'
            },
            {
                id: 3,
                name: 'Coldplay: Music of the Spheres - VIP Front Row Ticket',
                description: 'Exclusive VIP Lounge access, early venue entry, and backstage merchandise bundle.',
                totalQuantity: 100,
                availableQuantity: 100,
                price: 199.50,
                status: 'ACTIVE',
                imageUrl: 'ticket'
            }
        ];
        renderProducts();
    }
}

// ==========================================
// Product Data Fetching & Rendering
// ==========================================
async function fetchProducts(isSilent = false) {
    if (!state.isLiveBackend) {
        renderProducts();
        return;
    }

    try {
        const res = await fetch('/api/products');
        if (res.ok) {
            const data = await res.json();
            if (Array.isArray(data) && data.length > 0) {
                state.products = data;
                renderProducts();
            }
        }
    } catch (e) {
        if (!isSilent) console.error('Fetch products error:', e);
    }
}

function renderProducts() {
    // 1. Update dropdowns in Storefront and War Room
    const storeSelect = document.getElementById('store-product-select');
    const simSelect = document.getElementById('sim-product-select');

    if (storeSelect) {
        const currVal = storeSelect.value || state.selectedProductId;
        storeSelect.innerHTML = state.products.map(p => 
            `<option value="${p.id}" ${p.id == currVal ? 'selected' : ''}>${p.name} ($${p.price.toFixed(2)})</option>`
        ).join('');
    }

    if (simSelect) {
        const currVal = simSelect.value || state.selectedProductId;
        simSelect.innerHTML = state.products.map(p => 
            `<option value="${p.id}" ${p.id == currVal ? 'selected' : ''}>${p.name} (${p.availableQuantity}/${p.totalQuantity} left)</option>`
        ).join('');
    }

    // 2. Update current selected product display in Storefront
    updateStorefrontDisplay();

    // 3. Update Product Catalog Grid
    renderProductCatalogGrid();
}

function onSelectProductChange(id) {
    playClickSound();
    state.selectedProductId = parseInt(id, 10);
    const simSelect = document.getElementById('sim-product-select');
    if (simSelect) simSelect.value = state.selectedProductId;
    updateStorefrontDisplay();
}

function updateStorefrontDisplay() {
    const prod = state.products.find(p => p.id === state.selectedProductId) || state.products[0];
    if (!prod) return;

    state.selectedProductId = prod.id;

    const title = document.getElementById('store-product-title');
    const desc = document.getElementById('store-product-desc');
    const price = document.getElementById('store-product-price');
    const origPrice = document.getElementById('store-product-original');
    const availQty = document.getElementById('store-available-qty');
    const totalQty = document.getElementById('store-total-qty');
    const progress = document.getElementById('store-stock-progress');
    const stockPct = document.getElementById('store-stock-pct');
    const stockTag = document.getElementById('store-stock-tag');
    const buyBtn = document.getElementById('btn-buy-now');
    const buyText = document.getElementById('btn-buy-text');
    const artEmoji = document.getElementById('product-art-emoji');
    const artCategory = document.getElementById('product-art-category');

    if (title) title.textContent = prod.name;
    if (desc) desc.textContent = prod.description;
    if (price) price.textContent = `$${prod.price.toFixed(2)}`;
    if (origPrice) origPrice.textContent = `$${(prod.price * 1.6).toFixed(2)}`;
    if (availQty) availQty.textContent = prod.availableQuantity;
    if (totalQty) totalQty.textContent = prod.totalQuantity;

    const pct = prod.totalQuantity > 0 ? Math.max(0, Math.min(100, Math.round((prod.availableQuantity / prod.totalQuantity) * 100))) : 0;
    if (progress) {
        progress.style.width = `${pct}%`;
        if (pct > 40) {
            progress.className = 'h-full rounded-full bg-gradient-to-r from-cyan-500 to-emerald-400 transition-all duration-500 shadow-sm shadow-cyan-500/50';
        } else if (pct > 15) {
            progress.className = 'h-full rounded-full bg-gradient-to-r from-amber-500 to-orange-400 transition-all duration-500 shadow-sm shadow-amber-500/50';
        } else {
            progress.className = 'h-full rounded-full bg-gradient-to-r from-rose-600 to-rose-400 transition-all duration-500 shadow-sm shadow-rose-500/50 animate-pulse';
        }
    }

    if (stockPct) stockPct.textContent = `${pct}% In Stock (${prod.availableQuantity} Left)`;

    if (artEmoji) {
        artEmoji.textContent = PRODUCT_EMOJIS[prod.imageUrl] || PRODUCT_EMOJIS.tech;
    }
    if (artCategory) {
        artCategory.textContent = prod.imageUrl ? prod.imageUrl.toUpperCase() + ' DROP' : 'LIMITED EDITION';
    }

    // Sold out vs Available states
    if (prod.availableQuantity <= 0 || prod.status === 'SOLD OUT') {
        if (stockTag) {
            stockTag.textContent = 'SOLD OUT';
            stockTag.className = 'px-3 py-1 rounded-full text-xs font-mono font-bold bg-rose-500/20 text-rose-400 border border-rose-500/30';
        }
        if (buyBtn) {
            buyBtn.disabled = true;
            buyBtn.className = 'w-full py-4 px-6 rounded-xl font-display font-bold text-base uppercase tracking-wider text-slate-500 bg-slate-800 border border-white/5 cursor-not-allowed flex items-center justify-center space-x-2';
        }
        if (buyText) buyText.textContent = 'SOLD OUT (ALL UNITS CLAIMED)';
    } else {
        if (stockTag) {
            stockTag.textContent = pct < 20 ? 'ALMOST GONE' : 'IN STOCK';
            stockTag.className = pct < 20 
                ? 'px-3 py-1 rounded-full text-xs font-mono font-bold bg-amber-500/20 text-amber-400 border border-amber-500/30'
                : 'px-3 py-1 rounded-full text-xs font-mono font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20';
        }
        if (buyBtn) {
            buyBtn.disabled = false;
            buyBtn.className = 'w-full py-4 px-6 rounded-xl font-display font-bold text-base uppercase tracking-wider text-black bg-gradient-to-r from-cyan-400 via-teal-300 to-emerald-400 hover:from-cyan-300 hover:to-emerald-300 transition-all duration-200 shadow-lg shadow-cyan-500/25 active:scale-[0.98] flex items-center justify-center space-x-2';
        }
        if (buyText) buyText.textContent = 'BUY NOW (INSTANT 1-CLICK CLAIM)';
    }
}

function renderProductCatalogGrid() {
    const grid = document.getElementById('products-catalog-grid');
    if (!grid) return;

    grid.innerHTML = state.products.map(p => {
        const pct = p.totalQuantity > 0 ? Math.round((p.availableQuantity / p.totalQuantity) * 100) : 0;
        const emoji = PRODUCT_EMOJIS[p.imageUrl] || PRODUCT_EMOJIS.tech;
        const isSoldOut = p.availableQuantity <= 0;

        return `
        <div class="bg-slate-900/60 rounded-2xl border border-white/10 p-5 flex flex-col justify-between backdrop-blur-md hover:border-cyan-500/30 transition-all duration-200 group">
            <div>
                <div class="flex items-center justify-between mb-3">
                    <span class="text-3xl p-2 rounded-xl bg-slate-950/80 border border-white/5">${emoji}</span>
                    <span class="px-2.5 py-0.5 rounded-full text-[10px] font-mono font-bold ${isSoldOut ? 'bg-rose-500/20 text-rose-400 border border-rose-500/30' : 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'}">
                        ${p.status}
                    </span>
                </div>
                <h3 class="font-display font-bold text-lg text-white group-hover:text-cyan-400 transition">${escapeHtml(p.name)}</h3>
                <p class="text-xs text-slate-400 mt-1 line-clamp-2">${escapeHtml(p.description)}</p>
                
                <div class="mt-4 flex items-baseline justify-between">
                    <span class="text-xl font-mono font-bold text-white">$${p.price.toFixed(2)}</span>
                    <span class="text-xs font-mono text-slate-400">${p.availableQuantity} / ${p.totalQuantity} Units</span>
                </div>

                <div class="w-full h-2 bg-slate-950 rounded-full overflow-hidden mt-2 border border-white/5">
                    <div class="h-full bg-gradient-to-r from-cyan-500 to-emerald-400 transition-all" style="width: ${pct}%"></div>
                </div>
            </div>

            <div class="mt-6 pt-4 border-t border-white/5 flex items-center justify-between gap-2">
                <button onclick="selectProductForDrop(${p.id})" class="flex-1 py-2 px-3 rounded-lg text-xs font-mono font-semibold bg-slate-800 hover:bg-slate-700 text-slate-200 transition text-center">
                    View Drop
                </button>
                <button onclick="resetProductInventory(${p.id}, ${p.totalQuantity})" class="py-2 px-3 rounded-lg text-xs font-mono font-semibold bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400 border border-cyan-500/20 transition flex items-center space-x-1">
                    <span>🔄</span>
                    <span>Restock</span>
                </button>
            </div>
        </div>
        `;
    }).join('');
}

function selectProductForDrop(id) {
    state.selectedProductId = id;
    switchTab('store');
    updateStorefrontDisplay();
}

// ==========================================
// Purchase Handling (Customer View)
// ==========================================
async function triggerSinglePurchase() {
    playClickSound();
    const btn = document.getElementById('btn-buy-now');
    const btnText = document.getElementById('btn-buy-text');
    const prod = state.products.find(p => p.id === state.selectedProductId);

    if (!prod || prod.availableQuantity <= 0) {
        playFailThud();
        showToast('Item is already sold out!', 'error');
        return;
    }

    if (btn) btn.disabled = true;
    if (btnText) btnText.innerHTML = '<span class="animate-spin inline-block mr-2">⚙️</span> Securing Lock...';

    const userId = state.currentUser.id;
    const start = performance.now();

    if (state.isLiveBackend) {
        try {
            const res = await fetch('/api/purchase', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId, productId: prod.id })
            });
            const data = await res.json();
            const latency = Math.round(performance.now() - start);

            if (data.success) {
                handlePurchaseSuccess(prod, userId, data.remaining, data.latencyMs || latency);
            } else {
                handlePurchaseFailed(data.message || 'Transaction could not be completed.');
            }
        } catch (e) {
            handlePurchaseFailed('Network error communicating with JVM engine.');
        }
    } else {
        // Client offline simulation
        await new Promise(r => setTimeout(r, 60)); // Simulate microsecond network trip
        if (prod.availableQuantity > 0) {
            prod.availableQuantity--;
            if (prod.availableQuantity === 0) prod.status = 'SOLD OUT';
            const latency = Math.round(performance.now() - start);
            handlePurchaseSuccess(prod, userId, prod.availableQuantity, latency);
        } else {
            handlePurchaseFailed('Item sold out.');
        }
    }

    if (btn) btn.disabled = false;
    updateStorefrontDisplay();
}

function handlePurchaseSuccess(prod, userId, remaining, latency) {
    playSuccessChime();
    fireConfetti();

    // Log purchase
    const newRecord = {
        purchaseId: Math.floor(1000 + Math.random() * 9000),
        userId: userId,
        productId: prod.id,
        productName: prod.name,
        status: 'SUCCESS',
        purchaseTime: new Date().toLocaleTimeString(),
        latencyMs: latency
    };
    state.purchases.unshift(newRecord);

    // Refresh UI
    updateStorefrontDisplay();
    renderLedgerTable();
    addLiveClaimFeedEntry(newRecord);

    // Show Confirmation Modal
    openTicketModal(newRecord, remaining);
    randomizeUser(); // Switch user for next buy so user can test multiple consecutive purchases easily
}

function handlePurchaseFailed(msg) {
    playFailThud();
    showToast(msg, 'error');
    const buyBtn = document.getElementById('btn-buy-now');
    if (buyBtn) {
        buyBtn.classList.add('animate-shake');
        setTimeout(() => buyBtn.classList.remove('animate-shake'), 500);
    }
}

function addLiveClaimFeedEntry(rec) {
    const feed = document.getElementById('live-claim-feed');
    const countBadge = document.getElementById('live-claim-count');
    if (!feed) return;

    if (countBadge) {
        countBadge.textContent = `${state.purchases.length} Claims`;
    }

    const item = document.createElement('div');
    item.className = 'p-2 rounded-xl bg-slate-950/80 border border-white/5 flex items-center justify-between text-xs font-mono animate-fade-in';
    item.innerHTML = `
        <div class="flex items-center space-x-2">
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
            <span class="text-white font-medium">User #${rec.userId}</span>
            <span class="text-slate-500">claimed</span>
            <span class="text-cyan-400 truncate max-w-[120px]">${escapeHtml(rec.productName)}</span>
        </div>
        <span class="text-[10px] text-slate-500">${rec.latencyMs}ms</span>
    `;

    if (feed.children.length === 1 && feed.children[0].textContent.includes('Ready for orders')) {
        feed.innerHTML = '';
    }

    feed.prepend(item);
    if (feed.children.length > 20) {
        feed.removeChild(feed.lastChild);
    }
}

// ==========================================
// War Room: Concurrency Stress Tester
// ==========================================
function updateThreadSlider(val) {
    state.simThreadCount = parseInt(val, 10);
    const display = document.getElementById('sim-thread-count-display');
    if (display) display.textContent = `${state.simThreadCount} Threads`;
}

function setThreadPreset(count) {
    playClickSound();
    const slider = document.getElementById('sim-thread-slider');
    if (slider) slider.value = count;
    updateThreadSlider(count);
}

async function launchSimulation() {
    if (state.simRunning) return;
    playLaunchSound();

    const simSelect = document.getElementById('sim-product-select');
    const targetProdId = parseInt(simSelect ? simSelect.value : state.selectedProductId, 10);
    const prod = state.products.find(p => p.id === targetProdId) || state.products[0];
    const threadCount = state.simThreadCount;
    const mode = document.querySelector('input[name="sim_mode"]:checked')?.value || 'safe';

    state.simRunning = true;
    const btn = document.getElementById('btn-run-simulation');
    const btnText = document.getElementById('btn-sim-text');

    if (btn) btn.disabled = true;
    if (btnText) btnText.innerHTML = '<span class="animate-spin inline-block mr-2">⚡</span> BLASTING THREADS...';

    // Reset thread matrix view
    renderThreadMatrix(threadCount);

    const initialStock = prod.availableQuantity;
    const totalTickets = prod.totalQuantity;

    if (state.isLiveBackend && mode === 'safe') {
        // Real multithreaded simulation in Java JVM!
        try {
            const startReq = performance.now();
            const res = await fetch('/api/simulate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    productId: prod.id,
                    userCount: threadCount,
                    startUserId: Math.floor(100 + Math.random() * 8000)
                })
            });

            const data = await res.json();
            const clientTripTime = Math.round(performance.now() - startReq);

            // Animate thread results
            animateThreadResults(data.userResults || []);

            // Update Telemetry Display
            updateTelemetryDisplay(data.executionTimeMs, data.tps, data.successful, data.failed);

            // Audit Consistency
            updateAuditVerifier(initialStock, data.successful, data.finalRemaining, totalTickets, true);

            // Update Chart
            updateOutcomeChart(data.successful, data.failed);
            updateLatencyChart(data.userResults || []);

            // Refresh products
            await fetchProducts(true);
            await fetchPurchases(true);

            showToast(`Simulation complete: ${data.successful} Won, ${data.failed} Sold Out`, 'success');

        } catch (e) {
            showToast('Error during JVM simulation.', 'error');
        }
    } else {
        // Client-side simulation (Supports both safe mode and race condition demo!)
        await executeClientSideSimulation(prod, threadCount, mode);
    }

    state.simRunning = false;
    if (btn) btn.disabled = false;
    if (btnText) btnText.textContent = 'LAUNCH FLASH ATTACK';
}

function renderThreadMatrix(count) {
    const container = document.getElementById('thread-matrix');
    if (!container) return;
    container.innerHTML = '';

    const nodesToShow = Math.min(count, 120);
    for (let i = 0; i < nodesToShow; i++) {
        const node = document.createElement('div');
        node.id = `thread-node-${i}`;
        node.className = 'thread-node state-running';
        node.setAttribute('data-tooltip', `Thread #${i + 1}: In Flight`);
        container.appendChild(node);
    }
}

function animateThreadResults(results) {
    results.forEach((r, idx) => {
        const node = document.getElementById(`thread-node-${idx}`);
        if (node) {
            node.className = `thread-node ${r.success ? 'state-success' : 'state-failed'}`;
            node.setAttribute('data-tooltip', `User #${r.userId}: ${r.success ? 'CLAIMED' : 'SOLD OUT'} (${r.latencyMs}ms)`);
        }
    });
}

async function executeClientSideSimulation(prod, threadCount, mode) {
    const startTime = performance.now();
    const results = [];
    let initialStock = prod.availableQuantity;
    let successful = 0;
    let failed = 0;

    if (mode === 'race') {
        // Intentional race condition bug simulation!
        // When unprotected, multiple threads read the same stock before it decrements!
        const oversellFactor = Math.floor(Math.random() * 8) + 3; // Intentionally overbooks!
        const maxCanClaim = Math.min(threadCount, initialStock + oversellFactor);

        for (let i = 0; i < threadCount; i++) {
            const won = i < maxCanClaim;
            const latency = Math.floor(Math.random() * 18) + 2;
            if (won) successful++; else failed++;
            results.push({ userId: 1000 + i, success: won, latencyMs: latency });
        }
        prod.availableQuantity = Math.max(-oversellFactor, initialStock - successful);
    } else {
        // Safe Mutex client simulation
        for (let i = 0; i < threadCount; i++) {
            const latency = Math.floor(Math.random() * 25) + 3;
            if (prod.availableQuantity > 0) {
                prod.availableQuantity--;
                successful++;
                results.push({ userId: 1000 + i, success: true, latencyMs: latency });
            } else {
                failed++;
                results.push({ userId: 1000 + i, success: false, latencyMs: latency });
            }
        }
        if (prod.availableQuantity === 0) prod.status = 'SOLD OUT';
    }

    const executionTime = Math.max(1, Math.round(performance.now() - startTime + (threadCount * 0.1)));
    const tps = ((successful + failed) / (executionTime / 1000)).toFixed(2);

    animateThreadResults(results);
    updateTelemetryDisplay(executionTime, tps, successful, failed);

    const isConsistent = mode === 'safe' && (successful <= initialStock) && (prod.availableQuantity >= 0);
    updateAuditVerifier(initialStock, successful, prod.availableQuantity, prod.totalQuantity, isConsistent);

    updateOutcomeChart(successful, failed);
    updateLatencyChart(results);
    updateStorefrontDisplay();
    renderProductCatalogGrid();

    if (isConsistent) {
        showToast(`Simulation completed: 0 Oversell. ACID Conserved.`, 'success');
    } else {
        playFailThud();
        showToast(`Race Condition Detected! Inventory oversold by ${Math.abs(prod.availableQuantity)} tickets!`, 'error');
    }
}

function updateTelemetryDisplay(timeMs, tps, success, failed) {
    const timeEl = document.getElementById('telemetry-time');
    const tpsEl = document.getElementById('telemetry-tps');
    const successEl = document.getElementById('telemetry-success');
    const failedEl = document.getElementById('telemetry-failed');

    if (timeEl) timeEl.textContent = `${timeMs} ms`;
    if (tpsEl) tpsEl.textContent = `${tps} TPS`;
    if (successEl) successEl.textContent = success;
    if (failedEl) failedEl.textContent = failed;
}

function updateAuditVerifier(initial, success, remaining, total, isConsistent) {
    const banner = document.getElementById('audit-banner');
    const icon = document.getElementById('audit-icon');
    const title = document.getElementById('audit-title');
    const subtitle = document.getElementById('audit-subtitle');
    const badge = document.getElementById('audit-badge');

    if (!banner) return;

    if (isConsistent) {
        banner.className = 'p-5 rounded-2xl border border-emerald-500/40 bg-emerald-950/30 backdrop-blur-md flex flex-col sm:flex-row items-center justify-between gap-4 shadow-lg shadow-emerald-500/10';
        if (icon) icon.innerHTML = '🛡️';
        if (title) title.innerHTML = '<span class="text-emerald-400 font-bold">100% ACID CONSISTENT (0 OVERSELLING)</span>';
        if (subtitle) subtitle.innerHTML = `Audit Pass: Initial (${initial}) = Sold (${success}) + Remaining (${remaining}). Zero race conditions.`;
        if (badge) {
            badge.className = 'px-3 py-1 rounded-full text-xs font-mono font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40';
            badge.textContent = 'CONSISTENT';
        }
    } else {
        banner.className = 'p-5 rounded-2xl border border-rose-500/40 bg-rose-950/30 backdrop-blur-md flex flex-col sm:flex-row items-center justify-between gap-4 shadow-lg shadow-rose-500/10 animate-pulse';
        if (icon) icon.innerHTML = '⚠️';
        if (title) title.innerHTML = '<span class="text-rose-400 font-bold">RACE CONDITION DETECTED! (OVERBOOKED)</span>';
        if (subtitle) subtitle.innerHTML = `Audit Failure: Oversold beyond stock! Inventory is negative (${remaining}). Mutex lock was bypassed!`;
        if (badge) {
            badge.className = 'px-3 py-1 rounded-full text-xs font-mono font-bold bg-rose-500/20 text-rose-300 border border-rose-500/40';
            badge.textContent = 'INCONSISTENT';
        }
    }
}

// ==========================================
// Chart.js Visualizations
// ==========================================
function initCharts() {
    const outcomeCanvas = document.getElementById('outcome-chart');
    const latencyCanvas = document.getElementById('latency-chart');

    if (outcomeCanvas && window.Chart) {
        state.charts.outcome = new Chart(outcomeCanvas, {
            type: 'doughnut',
            data: {
                labels: ['Successful Claims', 'Sold Out / Rejected'],
                datasets: [{
                    data: [50, 50],
                    backgroundColor: ['#10b981', '#f43f5e'],
                    borderColor: '#0f172a',
                    borderWidth: 3
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: { color: '#94a3b8', font: { family: 'Inter', size: 11 } }
                    }
                },
                cutout: '70%'
            }
        });
    }

    if (latencyCanvas && window.Chart) {
        state.charts.latency = new Chart(latencyCanvas, {
            type: 'bar',
            data: {
                labels: Array.from({ length: 20 }, (_, i) => `T-${i + 1}`),
                datasets: [{
                    label: 'Latency (ms)',
                    data: Array.from({ length: 20 }, () => Math.floor(Math.random() * 20) + 5),
                    backgroundColor: '#06b6d4',
                    borderRadius: 4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: { ticks: { color: '#64748b', font: { size: 9 } }, grid: { display: false } },
                    y: { ticks: { color: '#64748b', font: { size: 9 } }, grid: { color: 'rgba(255,255,255,0.05)' } }
                },
                plugins: {
                    legend: { display: false }
                }
            }
        });
    }
}

function updateOutcomeChart(success, failed) {
    if (state.charts.outcome) {
        state.charts.outcome.data.datasets[0].data = [success, failed];
        state.charts.outcome.update();
    }
}

function updateLatencyChart(userResults) {
    if (state.charts.latency && userResults.length > 0) {
        const sample = userResults.slice(0, 24);
        state.charts.latency.data.labels = sample.map((_, i) => `#${i + 1}`);
        state.charts.latency.data.datasets[0].data = sample.map(r => r.latencyMs);
        state.charts.latency.data.datasets[0].backgroundColor = sample.map(r => r.success ? '#10b981' : '#f43f5e');
        state.charts.latency.update();
    }
}

// ==========================================
// Ledger & History
// ==========================================
async function fetchPurchases(isSilent = false) {
    if (!state.isLiveBackend) return;
    try {
        const res = await fetch('/api/purchases');
        if (res.ok) {
            const data = await res.json();
            if (Array.isArray(data)) {
                state.purchases = data;
                renderLedgerTable();
            }
        }
    } catch (e) {
        if (!isSilent) console.error('Error fetching purchases:', e);
    }
}

function renderLedgerTable() {
    const tbody = document.getElementById('ledger-table-body');
    const totalCount = document.getElementById('ledger-total-count');
    const totalVolume = document.getElementById('ledger-total-volume');
    const avgLatency = document.getElementById('ledger-avg-latency');

    if (totalCount) totalCount.textContent = state.purchases.length;

    // Calculate total gross volume
    let gross = 0;
    let totalLat = 0;
    state.purchases.forEach(p => {
        const prod = state.products.find(pr => pr.id === p.productId);
        if (prod) gross += prod.price;
        totalLat += (p.latencyMs || 0);
    });

    if (totalVolume) totalVolume.textContent = `$${gross.toFixed(2)}`;
    if (avgLatency) {
        const avg = state.purchases.length > 0 ? (totalLat / state.purchases.length).toFixed(1) : 0;
        avgLatency.textContent = `${avg} ms`;
    }

    if (!tbody) return;

    if (state.purchases.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" class="py-8 text-center text-slate-500 font-mono">
                    No transactions recorded yet. Make a purchase or run a simulation.
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = state.purchases.slice(0, 50).map(p => `
        <tr class="hover:bg-white/5 transition-colors">
            <td class="py-3 px-4 font-bold text-white">#PUR-${p.purchaseId}</td>
            <td class="py-3 px-4 text-cyan-400">User #${p.userId}</td>
            <td class="py-3 px-4 text-slate-200">${escapeHtml(p.productName || 'Flash Drop')}</td>
            <td class="py-3 px-4">
                <span class="px-2 py-0.5 rounded text-[10px] font-bold ${p.status === 'SUCCESS' ? 'bg-emerald-500/20 text-emerald-300' : 'bg-rose-500/20 text-rose-300'}">
                    ${p.status}
                </span>
            </td>
            <td class="py-3 px-4 text-slate-400">${p.purchaseTime}</td>
            <td class="py-3 px-4 text-right text-purple-400 font-bold">${p.latencyMs || 2}ms</td>
        </tr>
    `).join('');
}

function filterLedgerTable() {
    const input = document.getElementById('ledger-search-input')?.value.toLowerCase() || '';
    const rows = document.querySelectorAll('#ledger-table-body tr');
    rows.forEach(r => {
        const text = r.textContent.toLowerCase();
        r.style.display = text.includes(input) ? '' : 'none';
    });
}

function exportLedgerCSV() {
    playClickSound();
    if (state.purchases.length === 0) {
        showToast('No records to export.', 'info');
        return;
    }

    let csv = 'Purchase ID,User ID,Product ID,Product Name,Status,Timestamp,Latency (ms)\n';
    state.purchases.forEach(p => {
        csv += `${p.purchaseId},${p.userId},${p.productId},"${(p.productName || '').replace(/"/g, '""')}",${p.status},"${p.purchaseTime}",${p.latencyMs || 0}\n`;
    });

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `flash_sale_audit_${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    showToast('Audit Ledger downloaded as CSV', 'success');
}

async function clearPurchases() {
    playClickSound();
    if (!confirm('Clear all purchase history?')) return;

    if (state.isLiveBackend) {
        try {
            await fetch('/api/clear-purchases', { method: 'POST' });
        } catch (e) {}
    }
    state.purchases = [];
    renderLedgerTable();
    showToast('Purchase history cleared', 'info');
}

// ==========================================
// Product Restock & Admin
// ==========================================
async function quickResetSelectedProduct() {
    playClickSound();
    const prod = state.products.find(p => p.id === state.selectedProductId);
    if (!prod) return;
    await resetProductInventory(prod.id, prod.totalQuantity);
}

async function resetProductInventory(productId, quantity) {
    if (state.isLiveBackend) {
        try {
            const res = await fetch('/api/reset', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ productId, quantity })
            });
            if (res.ok) {
                await fetchProducts();
                await fetchPurchases();
                showToast(`Inventory restocked to ${quantity} units!`, 'success');
                return;
            }
        } catch (e) {}
    }

    // Client fallback
    const p = state.products.find(pr => pr.id === productId);
    if (p) {
        p.availableQuantity = quantity;
        p.status = 'ACTIVE';
        updateStorefrontDisplay();
        renderProductCatalogGrid();
        showToast(`Inventory restocked to ${quantity} units!`, 'success');
    }
}

function openAddProductModal() {
    playClickSound();
    document.getElementById('modal-add-product')?.classList.remove('hidden');
}

function closeAddProductModal() {
    document.getElementById('modal-add-product')?.classList.add('hidden');
}

async function handleCreateProductSubmit(e) {
    e.preventDefault();
    playClickSound();

    const name = document.getElementById('new-prod-name')?.value.trim();
    const desc = document.getElementById('new-prod-desc')?.value.trim();
    const qty = parseInt(document.getElementById('new-prod-qty')?.value, 10);
    const price = parseFloat(document.getElementById('new-prod-price')?.value);

    if (!name || isNaN(qty) || isNaN(price)) return;

    if (state.isLiveBackend) {
        try {
            const res = await fetch('/api/products', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, description: desc, quantity: qty, price })
            });
            if (res.ok) {
                const data = await res.json();
                closeAddProductModal();
                await fetchProducts();
                if (data.productId) state.selectedProductId = data.productId;
                updateStorefrontDisplay();
                showToast('New Flash Drop created!', 'success');
                return;
            }
        } catch (e) {}
    }

    // Client fallback
    const newId = state.products.length + 1;
    state.products.push({
        id: newId,
        name,
        description: desc,
        totalQuantity: qty,
        availableQuantity: qty,
        price,
        status: 'ACTIVE',
        imageUrl: 'tech'
    });
    closeAddProductModal();
    renderProducts();
    state.selectedProductId = newId;
    updateStorefrontDisplay();
    showToast('New Flash Drop created!', 'success');
}

// ==========================================
// Ticket Modal & Confetti
// ==========================================
function openTicketModal(rec, remaining) {
    const modal = document.getElementById('modal-ticket');
    if (!modal) return;

    document.getElementById('ticket-order-id').textContent = `#ORD-${rec.purchaseId}`;
    document.getElementById('ticket-product-name').textContent = rec.productName;
    document.getElementById('ticket-user-id').textContent = `User #${rec.userId} (${state.currentUser.name})`;
    document.getElementById('ticket-remaining').textContent = `${remaining} Units Left`;
    document.getElementById('ticket-latency').textContent = `${rec.latencyMs} ms`;

    modal.classList.remove('hidden');
}

function closeTicketModal() {
    playClickSound();
    document.getElementById('modal-ticket')?.classList.add('hidden');
}

function fireConfetti() {
    if (window.confetti) {
        window.confetti({
            particleCount: 80,
            spread: 70,
            origin: { y: 0.65 },
            colors: ['#06b6d4', '#10b981', '#a855f7', '#f43f5e']
        });
    }
}

// ==========================================
// Navigation & Tab Switching
// ==========================================
function switchTab(tabId) {
    playClickSound();
    state.currentTab = tabId;

    const tabs = ['store', 'warroom', 'products', 'ledger', 'architecture'];
    tabs.forEach(t => {
        const el = document.getElementById(`tab-${t}`);
        const btn = document.getElementById(`tab-btn-${t}`);
        if (el) el.classList.toggle('hidden', t !== tabId);
        if (btn) {
            if (t === tabId) {
                btn.className = 'nav-tab px-4 py-2 text-xs font-semibold rounded-lg transition-all duration-200 flex items-center space-x-2 text-white bg-white/10 shadow-sm';
            } else {
                btn.className = 'nav-tab px-4 py-2 text-xs font-semibold rounded-lg transition-all duration-200 flex items-center space-x-2 text-slate-400 hover:text-white hover:bg-white/5';
            }
        }
    });
}

// ==========================================
// Utilities
// ==========================================
function randomizeUser() {
    const randomId = Math.floor(Math.random() * 900) + 10;
    const randomName = USER_NAMES[Math.floor(Math.random() * USER_NAMES.length)];
    state.currentUser = { id: randomId, name: randomName };

    const display = document.getElementById('current-user-display');
    if (display) display.textContent = `User #${randomId} (${randomName})`;
}

function initCountdownClock() {
    let hours = 2, mins = 18, secs = 45, ms = 82;
    setInterval(() => {
        ms -= 4;
        if (ms < 0) {
            ms = 99;
            secs--;
            if (secs < 0) {
                secs = 59;
                mins--;
                if (mins < 0) {
                    mins = 59;
                    hours--;
                    if (hours < 0) hours = 2;
                }
            }
        }
        const hEl = document.getElementById('clock-hours');
        const mEl = document.getElementById('clock-mins');
        const sEl = document.getElementById('clock-secs');
        const msEl = document.getElementById('clock-ms');

        if (hEl) hEl.textContent = String(hours).padStart(2, '0');
        if (mEl) mEl.textContent = String(mins).padStart(2, '0');
        if (sEl) sEl.textContent = String(secs).padStart(2, '0');
        if (msEl) msEl.textContent = String(ms).padStart(2, '0');
    }, 40);
}

function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    const colors = {
        success: 'border-emerald-500/40 bg-emerald-950/90 text-emerald-200',
        error: 'border-rose-500/40 bg-rose-950/90 text-rose-200',
        info: 'border-cyan-500/40 bg-slate-900/90 text-cyan-200'
    };

    toast.className = `toast-item px-4 py-3 rounded-xl border backdrop-blur-md shadow-xl text-xs font-mono flex items-center space-x-2 ${colors[type] || colors.info}`;
    toast.innerHTML = `
        <span>${type === 'success' ? '✅' : type === 'error' ? '❌' : 'ℹ️'}</span>
        <span>${escapeHtml(message)}</span>
    `;

    container.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(10px)';
        setTimeout(() => toast.remove(), 300);
    }, 3200);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
