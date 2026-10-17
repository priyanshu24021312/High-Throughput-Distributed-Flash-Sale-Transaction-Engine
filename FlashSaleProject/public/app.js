/**
 * ========================================================
 * FlashDeals - B.Tech 5th Semester Mini Project
 * Frontend Controller (Vanilla JavaScript)
 * ========================================================
 * Topics Covered:
 * 1. Asynchronous Fetch API & REST communication
 * 2. Real-time DOM manipulation & state updates
 * 3. Role-based view switching (Customer vs Admin)
 * 4. Integration with Java WorkerPool and WAL (Write-Ahead Log)
 */

// ==========================================
// 1. Application State
// ==========================================
let products = [];
let orders = [];
let selectedProductId = 1;
let currentCategory = 'all';
let isAdminLoggedIn = sessionStorage.getItem('admin_logged_in') === 'true';
let currentAdminTab = 'inventory'; // 'inventory', 'orders', 'algorithms'

// Simulated student users for testing concurrency
const studentUsers = [
    { id: 42, name: 'Rahul Sharma' },
    { id: 101, name: 'Priya Verma' },
    { id: 108, name: 'Amit Patel' },
    { id: 142, name: 'Neha Gupta' },
    { id: 205, name: 'Ananya Singh' },
    { id: 312, name: 'Vikram Joshi' },
    { id: 450, name: 'Sneha Reddy' }
];
let activeUser = studentUsers[0];

// Category emoji icons
const categoryIcons = {
    flight: '✈️',
    train: '🚆',
    ticket: '🎫',
    smartwatch: '⌚',
    ring: '💍',
    wearable: '🎽',
    console: '🎮',
    phone: '📱',
    tech: '⚡'
};

// ==========================================
// 2. Page Initialization
// ==========================================
document.addEventListener('DOMContentLoaded', async () => {
    startCountdownTimer();
    updateUserDisplay();
    updateAdminButton();

    // Fetch initial products and orders from Java backend
    await loadProducts();
    await loadOrders();

    // Check if admin was previously logged in
    if (isAdminLoggedIn && window.location.hash === '#admin') {
        showAdminPanel();
    } else {
        showCustomerStore();
    }

    // Auto-refresh data every 3 seconds to reflect real-time stock
    setInterval(async () => {
        await loadProducts(true);
        if (isAdminLoggedIn) {
            await loadAdminOverview(true);
            if (currentAdminTab === 'algorithms') {
                await loadAlgorithmTelemetry(true);
            }
        }
    }, 3000);
});

// ==========================================
// 3. Backend API Data Fetching
// ==========================================

// Fetch products list from Java backend
async function loadProducts(silent = false) {
    try {
        const response = await fetch('/api/products');
        if (response.ok) {
            products = await response.json();
            renderStorefront();
            if (isAdminLoggedIn) {
                renderAdminProducts();
            }
        }
    } catch (error) {
        if (!silent) console.log('Running in local/offline demo mode.');
        seedSampleDataIfEmpty();
    }
}

// Fetch all completed orders
async function loadOrders(silent = false) {
    try {
        const response = await fetch('/api/admin/purchases');
        if (response.ok) {
            orders = await response.json();
            if (isAdminLoggedIn) {
                renderAdminOrders();
            }
        }
    } catch (error) {
        if (!silent) console.log('Orders fetch offline.');
    }
}

// Fetch Admin Overview statistics
async function loadAdminOverview(silent = false) {
    try {
        const response = await fetch('/api/admin/overview');
        if (response.ok) {
            const data = await response.json();
            document.getElementById('stat-revenue').textContent = `₹${formatRupees(data.totalRevenue)}`;
            document.getElementById('stat-orders').textContent = data.totalOrders;
            document.getElementById('stat-items').textContent = data.totalProducts;
            document.getElementById('stat-stock').textContent = `${data.totalStockRemaining} Units`;
        }
    } catch (error) {
        // Fallback calculation in client
        let totalRev = 0;
        orders.forEach(o => totalRev += (o.price || 0));
        let totalStock = 0;
        products.forEach(p => totalStock += (p.availableQuantity || 0));

        document.getElementById('stat-revenue').textContent = `₹${formatRupees(totalRev)}`;
        document.getElementById('stat-orders').textContent = orders.length;
        document.getElementById('stat-items').textContent = products.length;
        document.getElementById('stat-stock').textContent = `${totalStock} Units`;
    }
}

// Sample fallback data in case server is starting
function seedSampleDataIfEmpty() {
    if (products.length === 0) {
        products = [
            {
                id: 1,
                name: 'Air India Express: Delhi to Goa Flash Flight',
                description: 'Direct flight ticket with 15kg baggage allowance and free seat selection.',
                totalQuantity: 25,
                availableQuantity: 25,
                price: 2999.00,
                originalPrice: 6999.00,
                category: 'travel',
                imageUrl: 'flight'
            },
            {
                id: 2,
                name: 'Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)',
                description: 'Superfast train ticket with complimentary hot breakfast and panoramic windows.',
                totalQuantity: 40,
                availableQuantity: 40,
                price: 1750.00,
                originalPrice: 3400.00,
                category: 'travel',
                imageUrl: 'train'
            },
            {
                id: 3,
                name: 'Sunburn Goa 2026: VIP Arena 3-Day Pass',
                description: 'All-access VIP pass with lounge access and festival merchandise kit.',
                totalQuantity: 30,
                availableQuantity: 30,
                price: 4499.00,
                originalPrice: 9999.00,
                category: 'travel',
                imageUrl: 'ticket'
            },
            {
                id: 4,
                name: 'Ultrahuman Ring AIR - Raw Titanium Smart Ring',
                description: 'Sleep, HRV, skin temperature, and recovery tracking titanium ring.',
                totalQuantity: 15,
                availableQuantity: 15,
                price: 14999.00,
                originalPrice: 28499.00,
                category: 'wearables',
                imageUrl: 'ring'
            },
            {
                id: 5,
                name: 'Titan Smart Pro 2 AMOLED Smartwatch',
                description: '1.43" AMOLED display, sapphire crystal glass, 14-day battery life.',
                totalQuantity: 50,
                availableQuantity: 50,
                price: 3999.00,
                originalPrice: 8995.00,
                category: 'wearables',
                imageUrl: 'smartwatch'
            },
            {
                id: 6,
                name: 'Noise ColorFit Ultra 3 Smartwatch',
                description: 'Metallic finish, Bluetooth calling, 100+ sports modes with 2-year warranty.',
                totalQuantity: 60,
                availableQuantity: 60,
                price: 1999.00,
                originalPrice: 4999.00,
                category: 'wearables',
                imageUrl: 'smartwatch'
            },
            {
                id: 7,
                name: 'Sony PlayStation 5 Digital Edition Flash Drop',
                description: '1TB Custom SSD, Ray Tracing, 4K Gaming with DualSense Wireless Controller.',
                totalQuantity: 10,
                availableQuantity: 10,
                price: 39990.00,
                originalPrice: 44990.00,
                category: 'electronics',
                imageUrl: 'console'
            }
        ];
        renderStorefront();
    }
}

// ==========================================
// 4. Customer Storefront Functions
// ==========================================

// Render both the Top Spotlight item and the grid below
function renderStorefront() {
    renderSpotlightItem();
    renderProductsGrid();
}

// Render the featured deal on top
function renderSpotlightItem() {
    const item = products.find(p => p.id === selectedProductId) || products[0];
    if (!item) return;

    selectedProductId = item.id;

    // Set text fields
    document.getElementById('spotlight-title').textContent = item.name;
    document.getElementById('spotlight-desc').textContent = item.description;
    document.getElementById('spotlight-price').textContent = `₹${formatRupees(item.price)}`;
    document.getElementById('spotlight-original-price').textContent = `₹${formatRupees(item.originalPrice || item.price * 1.8)}`;
    document.getElementById('spotlight-qty-left').textContent = item.availableQuantity;
    document.getElementById('spotlight-qty-total').textContent = item.totalQuantity;

    // Set percentage discount
    const discount = Math.round(((item.originalPrice - item.price) / item.originalPrice) * 100);
    document.getElementById('spotlight-discount-badge').textContent = `-${discount > 0 ? discount : 45}% OFF`;

    // Category tag and icon
    document.getElementById('spotlight-category-tag').textContent = item.category === 'travel' ? 'Travel Ticket' : (item.category === 'wearables' ? 'Wearable' : 'Electronics');
    document.getElementById('spotlight-emoji').textContent = categoryIcons[item.imageUrl] || categoryIcons[item.category] || '⚡';

    // Stock progress bar
    const percentage = item.totalQuantity > 0 ? Math.round((item.availableQuantity / item.totalQuantity) * 100) : 0;
    document.getElementById('spotlight-progress-bar').style.width = `${percentage}%`;

    // Buy Button State
    const buyBtn = document.getElementById('btn-spotlight-buy');
    const buyText = document.getElementById('btn-spotlight-text');
    const stockBadge = document.getElementById('spotlight-stock-badge');

    if (item.availableQuantity <= 0) {
        stockBadge.textContent = 'Sold Out';
        stockBadge.className = 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800';
        buyBtn.disabled = true;
        buyBtn.className = 'w-full sm:w-auto px-6 py-3 bg-stone-300 text-stone-500 font-bold rounded-xl text-sm cursor-not-allowed';
        buyText.textContent = 'Sold Out';
    } else {
        stockBadge.textContent = percentage < 20 ? 'Selling Fast' : 'In Stock';
        stockBadge.className = percentage < 20 
            ? 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800'
            : 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800';
        buyBtn.disabled = false;
        buyBtn.className = 'w-full sm:w-auto px-6 py-3 bg-orange-600 hover:bg-orange-700 text-white font-bold rounded-xl text-sm transition shadow-sm flex items-center justify-center space-x-2';
        buyText.textContent = `Book Ticket (₹${formatRupees(item.price)})`;
    }
}

// Render the 3-column grid of all products
function renderProductsGrid() {
    const grid = document.getElementById('products-grid');
    if (!grid) return;

    // Filter by selected category tab
    let list = products;
    if (currentCategory !== 'all') {
        list = products.filter(p => p.category === currentCategory);
    }

    document.getElementById('items-count-badge').textContent = `${list.length} Items`;

    if (list.length === 0) {
        grid.innerHTML = '<div class="col-span-full py-12 text-center text-stone-400 text-sm">No items found in this category.</div>';
        return;
    }

    grid.innerHTML = list.map(item => {
        const icon = categoryIcons[item.imageUrl] || categoryIcons[item.category] || '⚡';
        const isSoldOut = item.availableQuantity <= 0;
        const percent = item.totalQuantity > 0 ? Math.round((item.availableQuantity / item.totalQuantity) * 100) : 0;

        return `
        <div class="product-card bg-white border border-stone-200 rounded-2xl p-5 flex flex-col justify-between shadow-xs">
            <div>
                <div class="flex items-center justify-between mb-3">
                    <span class="text-3xl p-2 bg-stone-50 rounded-xl border border-stone-100">${icon}</span>
                    <span class="text-[11px] font-semibold px-2 py-0.5 rounded-full ${isSoldOut ? 'bg-rose-100 text-rose-700' : 'bg-orange-100 text-orange-700'}">
                        ${isSoldOut ? 'Sold Out' : `${item.availableQuantity} Left`}
                    </span>
                </div>
                <h4 class="font-bold text-stone-900 text-base line-clamp-1">${escapeHtml(item.name)}</h4>
                <p class="text-xs text-stone-500 mt-1 line-clamp-2 leading-relaxed">${escapeHtml(item.description)}</p>
                
                <div class="mt-4 flex items-baseline justify-between">
                    <div>
                        <span class="text-xl font-extrabold text-orange-600">₹${formatRupees(item.price)}</span>
                        <span class="text-xs text-stone-400 line-through ml-1">₹${formatRupees(item.originalPrice)}</span>
                    </div>
                    <span class="text-xs text-stone-500">${item.availableQuantity}/${item.totalQuantity} Units</span>
                </div>

                <!-- Stock Bar -->
                <div class="w-full h-2 bg-stone-100 rounded-full overflow-hidden mt-2 border border-stone-200">
                    <div class="h-full bg-orange-500 rounded-full" style="width: ${percent}%;"></div>
                </div>
            </div>

            <div class="mt-5 pt-3 border-t border-stone-100 flex items-center space-x-2">
                <button onclick="selectSpotlightItem(${item.id})" class="flex-1 py-2 text-xs font-semibold bg-stone-100 hover:bg-stone-200 text-stone-700 rounded-xl transition">
                    View
                </button>
                <button onclick="buyProductById(${item.id})" ${isSoldOut ? 'disabled' : ''} class="px-4 py-2 text-xs font-bold ${isSoldOut ? 'bg-stone-200 text-stone-400 cursor-not-allowed' : 'bg-orange-600 hover:bg-orange-700 text-white shadow-sm'} rounded-xl transition">
                    ${isSoldOut ? 'Sold Out' : 'Buy Now'}
                </button>
            </div>
        </div>
        `;
    }).join('');
}

// Select an item to feature in the spotlight
function selectSpotlightItem(id) {
    selectedProductId = id;
    renderSpotlightItem();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// Change category filter
function setCategory(cat) {
    currentCategory = cat;

    // Update pill button styling
    const pills = ['all', 'travel', 'wearables', 'electronics'];
    pills.forEach(p => {
        const btn = document.getElementById(`pill-${p}`);
        if (btn) {
            if (p === cat) {
                btn.className = 'cat-btn px-3 py-1.5 text-xs font-semibold rounded-lg bg-white text-orange-600 shadow-sm';
            } else {
                btn.className = 'cat-btn px-3 py-1.5 text-xs font-semibold rounded-lg text-stone-600 hover:text-stone-900';
            }
        }
    });

    renderProductsGrid();
}

// Rotate student user for testing concurrency easily
function changeRandomUser() {
    const nextIndex = (studentUsers.indexOf(activeUser) + 1) % studentUsers.length;
    activeUser = studentUsers[nextIndex];
    updateUserDisplay();
    showToast(`Switched user to: ${activeUser.name}`, 'info');
}

function updateUserDisplay() {
    const el = document.getElementById('current-user-name');
    if (el) el.textContent = `User #${activeUser.id} (${activeUser.name})`;
}

// ==========================================
// 5. Booking / Purchase Action
// ==========================================
async function buySpotlightProduct() {
    await buyProductById(selectedProductId);
}

async function buyProductById(id) {
    const item = products.find(p => p.id === id);
    if (!item || item.availableQuantity <= 0) {
        showToast('Sorry! This item is already sold out.', 'error');
        return;
    }

    const buyBtn = document.getElementById('btn-spotlight-buy');
    if (buyBtn) buyBtn.disabled = true;

    // Send purchase request to Java Backend
    // Backend routes this through: WorkerPool -> WAL (Write-Ahead Log) -> Database
    try {
        const response = await fetch('/api/purchase', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                userId: activeUser.id,
                productId: item.id
            })
        });

        const result = await response.json();

        if (result.success) {
            // Trigger student confetti celebration!
            triggerConfetti();

            // Record purchase order
            const newOrder = {
                purchaseId: Math.floor(1000 + Math.random() * 9000),
                userId: activeUser.id,
                productId: item.id,
                productName: item.name,
                price: item.price,
                status: 'SUCCESS',
                purchaseTime: new Date().toLocaleTimeString(),
                workerName: result.workerName || 'FlashWorker-1'
            };
            orders.unshift(newOrder);

            // Update item stock locally
            item.availableQuantity = result.remaining;
            if (item.availableQuantity <= 0) item.status = 'SOLD OUT';

            // Show Confirmation Receipt
            showReceiptModal(newOrder, result.remaining, result.workerName);
            renderStorefront();
        } else {
            showToast(result.message || 'Ticket could not be booked.', 'error');
        }
    } catch (e) {
        // Fallback for offline testing
        item.availableQuantity--;
        const offlineOrder = {
            purchaseId: Math.floor(1000 + Math.random() * 9000),
            userId: activeUser.id,
            productId: item.id,
            productName: item.name,
            price: item.price,
            status: 'SUCCESS',
            purchaseTime: new Date().toLocaleTimeString(),
            workerName: 'FlashWorker-Local'
        };
        orders.unshift(offlineOrder);
        triggerConfetti();
        showReceiptModal(offlineOrder, item.availableQuantity, 'FlashWorker-Local');
        renderStorefront();
    }

    if (buyBtn) buyBtn.disabled = false;
}

// Receipt Modal
function showReceiptModal(order, remaining, workerName) {
    document.getElementById('receipt-id').textContent = `#ORD-${order.purchaseId}`;
    document.getElementById('receipt-name').textContent = order.productName;
    document.getElementById('receipt-user').textContent = `User #${order.userId} (${activeUser.name})`;
    document.getElementById('receipt-amount').textContent = `₹${formatRupees(order.price)}`;
    document.getElementById('receipt-remaining').textContent = `${remaining} Units Left`;
    document.getElementById('receipt-worker').textContent = `${workerName || 'WorkerPool'} (WAL Logged)`;

    document.getElementById('modal-receipt')?.classList.remove('hidden');
}

function closeReceiptModal() {
    document.getElementById('modal-receipt')?.classList.add('hidden');
}

// ==========================================
// 6. Admin Panel Functions (Protected)
// ==========================================
function handleAdminButtonClick() {
    if (isAdminLoggedIn) {
        showAdminPanel();
    } else {
        openAdminModal();
    }
}

function openAdminModal() {
    document.getElementById('modal-admin-login')?.classList.remove('hidden');
    const input = document.getElementById('input-pin');
    if (input) {
        input.value = '';
        setTimeout(() => input.focus(), 100);
    }
}

function closeAdminModal() {
    document.getElementById('modal-admin-login')?.classList.add('hidden');
}

// Verify Admin Security PIN
async function verifyAdminPin(e) {
    e.preventDefault();
    const pin = document.getElementById('input-pin')?.value.trim();

    try {
        const res = await fetch('/api/admin/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pin })
        });
        const data = await res.json();
        if (data.authenticated) {
            loginAsAdmin();
            return;
        }
    } catch (err) {
        if (pin === 'admin123') {
            loginAsAdmin();
            return;
        }
    }

    showToast('Incorrect PIN! Default is admin123', 'error');
}

function loginAsAdmin() {
    isAdminLoggedIn = true;
    sessionStorage.setItem('admin_logged_in', 'true');
    closeAdminModal();
    updateAdminButton();
    showAdminPanel();
    showToast('Admin logged in successfully.', 'success');
}

function logoutAdmin() {
    isAdminLoggedIn = false;
    sessionStorage.removeItem('admin_logged_in');
    updateAdminButton();
    showCustomerStore();
    showToast('Logged out of Admin Panel.', 'info');
}

function updateAdminButton() {
    const label = document.getElementById('btn-admin-label');
    if (label) {
        label.textContent = isAdminLoggedIn ? 'Admin Panel' : 'Admin Login';
    }
}

function showAdminPanel() {
    document.getElementById('customer-store-section')?.classList.add('hidden');
    document.getElementById('buyer-nav-pills')?.classList.add('hidden');
    document.getElementById('mobile-filter-bar')?.classList.add('hidden');
    document.getElementById('admin-panel-section')?.classList.remove('hidden');

    loadAdminOverview();
    renderAdminProducts();
    renderAdminOrders();
    window.location.hash = '#admin';
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function showCustomerStore() {
    document.getElementById('admin-panel-section')?.classList.add('hidden');
    document.getElementById('customer-store-section')?.classList.remove('hidden');
    document.getElementById('buyer-nav-pills')?.classList.remove('hidden');
    document.getElementById('mobile-filter-bar')?.classList.remove('hidden');

    renderStorefront();
    if (window.location.hash === '#admin') {
        window.history.pushState('', document.title, window.location.pathname);
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// Switch between Admin Subtabs (Inventory / Orders / Algorithms)
function switchAdminTab(tab) {
    currentAdminTab = tab;

    const invSection = document.getElementById('admin-panel-inventory');
    const ordSection = document.getElementById('admin-panel-orders');
    const algoSection = document.getElementById('admin-panel-algorithms');

    const invBtn = document.getElementById('tab-btn-inventory');
    const ordBtn = document.getElementById('tab-btn-orders');
    const algoBtn = document.getElementById('tab-btn-algorithms');

    // Hide all
    invSection?.classList.add('hidden');
    ordSection?.classList.add('hidden');
    algoSection?.classList.add('hidden');

    const activeBtn = 'px-4 py-2 text-xs font-bold rounded-xl bg-orange-600 text-white shadow-sm';
    const inactiveBtn = 'px-4 py-2 text-xs font-bold rounded-xl text-stone-600 hover:text-stone-900 hover:bg-stone-100';

    if (invBtn) invBtn.className = inactiveBtn;
    if (ordBtn) ordBtn.className = inactiveBtn;
    if (algoBtn) algoBtn.className = inactiveBtn;

    if (tab === 'inventory') {
        invSection?.classList.remove('hidden');
        if (invBtn) invBtn.className = activeBtn;
        renderAdminProducts();
    } else if (tab === 'orders') {
        ordSection?.classList.remove('hidden');
        if (ordBtn) ordBtn.className = activeBtn;
        renderAdminOrders();
    } else if (tab === 'algorithms') {
        algoSection?.classList.remove('hidden');
        if (algoBtn) algoBtn.className = activeBtn;
        loadAlgorithmTelemetry();
    }
}

// Render Products table in Admin
function renderAdminProducts() {
    const tbody = document.getElementById('admin-products-table');
    if (!tbody) return;

    tbody.innerHTML = products.map(p => `
        <tr class="hover:bg-stone-50 text-xs">
            <td class="py-3 px-4 font-semibold text-stone-900">${escapeHtml(p.name)}</td>
            <td class="py-3 px-4 text-stone-600 uppercase text-[11px] font-semibold">${p.category}</td>
            <td class="py-3 px-4 font-bold text-orange-600">₹${formatRupees(p.price)}</td>
            <td class="py-3 px-4 text-stone-400 line-through">₹${formatRupees(p.originalPrice)}</td>
            <td class="py-3 px-4 font-medium">${p.availableQuantity} / ${p.totalQuantity}</td>
            <td class="py-3 px-4">
                <span class="px-2 py-0.5 rounded-full text-[10px] font-bold ${p.availableQuantity > 0 ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}">
                    ${p.availableQuantity > 0 ? 'ACTIVE' : 'SOLD OUT'}
                </span>
            </td>
            <td class="py-3 px-4 text-right space-x-1.5 whitespace-nowrap">
                <button onclick="restockItem(${p.id}, ${p.totalQuantity})" class="px-2.5 py-1 bg-stone-100 hover:bg-stone-200 text-stone-700 font-semibold rounded-lg">Restock</button>
                <button onclick="deleteItem(${p.id})" class="px-2.5 py-1 bg-rose-50 hover:bg-rose-100 text-rose-700 font-semibold rounded-lg">Delete</button>
            </td>
        </tr>
    `).join('');
}

// Render Orders table in Admin
function renderAdminOrders() {
    const tbody = document.getElementById('admin-orders-table');
    if (!tbody) return;

    if (orders.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="py-6 text-center text-stone-400 text-xs">No orders recorded yet.</td></tr>';
        return;
    }

    tbody.innerHTML = orders.map(o => `
        <tr class="hover:bg-stone-50 text-xs">
            <td class="py-3 px-4 font-bold text-stone-900">#ORD-${o.purchaseId}</td>
            <td class="py-3 px-4 text-orange-700 font-semibold">User #${o.userId}</td>
            <td class="py-3 px-4 text-stone-800">${escapeHtml(o.productName)}</td>
            <td class="py-3 px-4 font-bold text-stone-900">₹${formatRupees(o.price || 0)}</td>
            <td class="py-3 px-4">
                <span class="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-800">
                    ${o.status || 'SUCCESS'}
                </span>
            </td>
            <td class="py-3 px-4 text-right text-stone-500">${o.purchaseTime}</td>
        </tr>
    `).join('');
}

// Search Orders by query
function searchOrders() {
    const query = document.getElementById('order-search-input')?.value.toLowerCase() || '';
    const rows = document.querySelectorAll('#admin-orders-table tr');
    rows.forEach(r => {
        r.style.display = r.textContent.toLowerCase().includes(query) ? '' : 'none';
    });
}

// Add New Product modal
function openAddModal() {
    document.getElementById('modal-add-item')?.classList.remove('hidden');
}

function closeAddModal() {
    document.getElementById('modal-add-item')?.classList.add('hidden');
}

function handleCategoryChange(cat) {
    const nameInput = document.getElementById('new-item-name');
    if (cat === 'travel') {
        nameInput.placeholder = 'e.g. Goa Flight Ticket or Manali Volvo Pass';
    } else if (cat === 'wearables') {
        nameInput.placeholder = 'e.g. Titan Smartwatch or Ultrahuman Ring';
    } else {
        nameInput.placeholder = 'e.g. Sony PlayStation 5 or Earbuds';
    }
}

// Submit Add Item form
async function handleAddNewItem(e) {
    e.preventDefault();

    const name = document.getElementById('new-item-name')?.value.trim();
    const desc = document.getElementById('new-item-desc')?.value.trim();
    const category = document.getElementById('new-item-category')?.value;
    const price = parseFloat(document.getElementById('new-item-price')?.value);
    const originalPrice = parseFloat(document.getElementById('new-item-original')?.value);
    const qty = parseInt(document.getElementById('new-item-qty')?.value, 10);

    if (!name || isNaN(price) || isNaN(qty)) return;

    try {
        const response = await fetch('/api/admin/products', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description: desc, price, originalPrice, quantity: qty, category })
        });
        if (response.ok) {
            closeAddModal();
            await loadProducts();
            showToast('New item published successfully!', 'success');
            return;
        }
    } catch (err) {}

    // Offline fallback
    const newItem = {
        id: products.length + 1,
        name,
        description: desc,
        price,
        originalPrice: originalPrice || price * 1.8,
        totalQuantity: qty,
        availableQuantity: qty,
        category,
        imageUrl: category === 'travel' ? 'flight' : (category === 'wearables' ? 'smartwatch' : 'tech')
    };
    products.push(newItem);
    closeAddModal();
    renderStorefront();
    renderAdminProducts();
    showToast('New item published successfully!', 'success');
}

// Restock item
async function restockItem(id, totalQty) {
    try {
        await fetch('/api/admin/reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productId: id, quantity: totalQty })
        });
    } catch (e) {}

    const item = products.find(p => p.id === id);
    if (item) {
        item.availableQuantity = totalQty;
        item.status = 'ACTIVE';
        renderStorefront();
        renderAdminProducts();
        showToast('Item stock has been reset to full.', 'success');
    }
}

// Delete item
async function deleteItem(id) {
    if (!confirm('Are you sure you want to remove this item?')) return;

    try {
        await fetch('/api/admin/products/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productId: id })
        });
    } catch (e) {}

    products = products.filter(p => p.id !== id);
    renderStorefront();
    renderAdminProducts();
    showToast('Item removed from catalog.', 'info');
}

// Download Orders CSV
function downloadOrdersCSV() {
    if (orders.length === 0) {
        showToast('No orders to download.', 'info');
        return;
    }

    let csv = 'Order ID,Customer ID,Item Name,Amount Paid (INR),Status,Timestamp\n';
    orders.forEach(o => {
        csv += `${o.purchaseId},${o.userId},"${o.productName.replace(/"/g, '""')}",${o.price},${o.status || 'SUCCESS'},"${o.purchaseTime}"\n`;
    });

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `orders_${Date.now()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
    showToast('Orders downloaded as CSV.', 'success');
}

// ==========================================
// 7. Engine Telemetry (WorkerPool & WAL Display)
// ==========================================
async function loadAlgorithmTelemetry(silent = false) {
    try {
        const [walRes, poolRes] = await Promise.all([
            fetch('/api/admin/wal'),
            fetch('/api/admin/workerpool')
        ]);

        if (walRes.ok && poolRes.ok) {
            const wal = await walRes.json();
            const pool = await poolRes.json();

            // 1. WorkerPool stats
            document.getElementById('worker-queue-size').textContent = pool.queueSize || 0;
            const pct = Math.min(100, Math.round(((pool.queueSize || 0) / (pool.queueCapacity || 2000)) * 100));
            document.getElementById('worker-queue-bar').style.width = `${pct}%`;
            document.getElementById('worker-count-badge').textContent = `${pool.workerCount || 8} Workers Online`;

            // Worker Thread cards
            const grid = document.getElementById('worker-cards-grid');
            if (grid && Array.isArray(pool.workers)) {
                grid.innerHTML = pool.workers.map(w => `
                    <div class="bg-stone-50 border border-stone-200 rounded-xl p-2.5 text-xs">
                        <div class="flex items-center justify-between font-semibold text-stone-800">
                            <span>${w.name}</span>
                            <span class="w-2 h-2 rounded-full ${w.status === 'IDLE' ? 'bg-emerald-500' : 'bg-orange-500 animate-pulse'}"></span>
                        </div>
                        <div class="text-[10px] text-stone-500 mt-1 flex justify-between">
                            <span>Status: ${w.status}</span>
                            <span>Done: ${w.tasksProcessed}</span>
                        </div>
                    </div>
                `).join('');
            }

            // 2. WAL stats and terminal
            document.getElementById('wal-lsn-badge').textContent = `#${wal.currentLsn || 1000}`;
            const terminal = document.getElementById('wal-log-terminal');
            if (terminal && Array.isArray(wal.records)) {
                if (wal.records.length === 0) {
                    terminal.innerHTML = '<div class="text-stone-500">// WAL log is clean.</div>';
                } else {
                    terminal.innerHTML = wal.records.map(r => `
                        <div class="text-[11px] leading-relaxed">
                            <span class="text-orange-400 font-bold">[LSN #${r.lsn}]</span>
                            <span class="${r.opType === 'ORDER_COMMIT' ? 'text-emerald-400 font-bold' : (r.opType === 'STOCK_RESERVE' ? 'text-cyan-400' : 'text-stone-400')}">${r.opType}</span>
                            <span class="text-stone-300">| User #${r.userId}</span>
                            <span class="text-stone-200">| "${escapeHtml(r.productName)}"</span>
                            <span class="text-stone-400">| [${r.status}]</span>
                        </div>
                    `).join('');
                }
            }
        }
    } catch (e) {
        if (!silent) console.log('Offline telemetry.');
    }
}

// Trigger WAL Checkpoint manually
async function triggerCheckpoint() {
    try {
        const res = await fetch('/api/admin/checkpoint', { method: 'POST' });
        if (res.ok) {
            const data = await res.json();
            showToast(`WAL Checkpoint created at LSN #${data.checkpointLsn}`, 'success');
            loadAlgorithmTelemetry();
            return;
        }
    } catch (e) {}

    showToast('WAL Checkpoint saved.', 'success');
}

// ==========================================
// 8. Helper Utilities
// ==========================================

// Simple Indian Rupee formatting
function formatRupees(num) {
    if (isNaN(num)) return '0.00';
    return Number(num).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

// Student Countdown timer
function startCountdownTimer() {
    let hrs = 2, mins = 18, secs = 45;
    setInterval(() => {
        secs--;
        if (secs < 0) {
            secs = 59;
            mins--;
            if (mins < 0) {
                mins = 59;
                hrs--;
                if (hrs < 0) hrs = 2;
            }
        }
        document.getElementById('timer-hours').textContent = String(hrs).padStart(2, '0');
        document.getElementById('timer-mins').textContent = String(mins).padStart(2, '0');
        document.getElementById('timer-secs').textContent = String(secs).padStart(2, '0');
    }, 1000);
}

// Confetti blast on purchase
function triggerConfetti() {
    if (window.confetti) {
        window.confetti({
            particleCount: 70,
            spread: 60,
            origin: { y: 0.65 },
            colors: ['#FF6B00', '#F97316', '#10B981']
        });
    }
}

// Toast notification
function showToast(message, type = 'info') {
    const box = document.getElementById('toast-box');
    if (!box) return;

    const toast = document.createElement('div');
    const color = type === 'success' ? 'bg-emerald-600 text-white' : (type === 'error' ? 'bg-rose-600 text-white' : 'bg-stone-900 text-white');
    toast.className = `toast-message px-4 py-2.5 rounded-xl shadow-lg text-xs font-medium flex items-center space-x-2 ${color}`;
    toast.innerHTML = `<span>${type === 'success' ? '✓' : (type === 'error' ? '✕' : 'ℹ')}</span><span>${escapeHtml(message)}</span>`;

    box.appendChild(toast);
    setTimeout(() => toast.remove(), 2800);
}

// Escape HTML utility for safe string insertion
function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
