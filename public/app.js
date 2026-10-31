/**
 * FlashDeals - B.Tech 5th Semester Mini Project
 * Frontend code (plain JavaScript)
 * - fetch API to talk with the Java backend
 * - customer view and admin view
 * - WorkerPool, WAL and Wait-For Graph display
 */

// ---------- 1. Variables ----------
let products = [];
let orders = [];
let selectedProductId = 1;
let currentCategory = 'all';
let isAdminLoggedIn = sessionStorage.getItem('admin_logged_in') === 'true';
let currentAdminTab = 'inventory';   // inventory, orders, algorithms

// users for testing many buyers
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

// ---------- 2. Page start ----------
document.addEventListener('DOMContentLoaded', async () => {
    startCountdownTimer();
    updateUserDisplay();
    updateAdminButton();

    await loadProducts();
    await loadOrders();

    if (isAdminLoggedIn && window.location.hash === '#admin') {
        showAdminPanel();
    } else {
        showCustomerStore();
    }

    // refresh every 3 seconds so stock is live
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

// ---------- 3. Get data from backend ----------
async function loadProducts(silent = false) {
    try {
        const response = await fetch('/api/products');
        if (response.ok) {
            products = await response.json();
            renderStorefront();
            if (isAdminLoggedIn) renderAdminProducts();
        }
    } catch (error) {
        if (!silent) console.log('Running in local/offline demo mode.');
        loadSampleProducts();
    }
}

async function loadOrders(silent = false) {
    try {
        const response = await fetch('/api/admin/purchases');
        if (response.ok) {
            orders = await response.json();
            if (isAdminLoggedIn) renderAdminOrders();
        }
    } catch (error) {
        if (!silent) console.log('Orders fetch offline.');
    }
}

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
        // server is off, so count from the data we already have
        let totalRevenue = 0;
        orders.forEach(order => totalRevenue += (order.price || 0));
        let totalStock = 0;
        products.forEach(product => totalStock += (product.availableQuantity || 0));

        document.getElementById('stat-revenue').textContent = `₹${formatRupees(totalRevenue)}`;
        document.getElementById('stat-orders').textContent = orders.length;
        document.getElementById('stat-items').textContent = products.length;
        document.getElementById('stat-stock').textContent = `${totalStock} Units`;
    }
}

// sample products if the server is not running
function loadSampleProducts() {
    if (products.length > 0) return;

    products = [
        { id: 1, name: 'Air India Express: Delhi to Goa Flash Flight', description: 'Direct flight ticket with 15kg baggage allowance and free seat selection.', totalQuantity: 25, availableQuantity: 25, price: 2999.00, originalPrice: 6999.00, category: 'travel', imageUrl: 'flight' },
        { id: 2, name: 'Vande Bharat Express: Executive Chair Car (Delhi - Varanasi)', description: 'Superfast train ticket with complimentary hot breakfast and panoramic windows.', totalQuantity: 40, availableQuantity: 40, price: 1750.00, originalPrice: 3400.00, category: 'travel', imageUrl: 'train' },
        { id: 3, name: 'Sunburn Goa 2026: VIP Arena 3-Day Pass', description: 'All-access VIP pass with lounge access and festival merchandise kit.', totalQuantity: 30, availableQuantity: 30, price: 4499.00, originalPrice: 9999.00, category: 'travel', imageUrl: 'ticket' },
        { id: 4, name: 'Ultrahuman Ring AIR - Raw Titanium Smart Ring', description: 'Sleep, HRV, skin temperature, and recovery tracking titanium ring.', totalQuantity: 15, availableQuantity: 15, price: 14999.00, originalPrice: 28499.00, category: 'wearables', imageUrl: 'ring' },
        { id: 5, name: 'Titan Smart Pro 2 AMOLED Smartwatch', description: '1.43" AMOLED display, sapphire crystal glass, 14-day battery life.', totalQuantity: 50, availableQuantity: 50, price: 3999.00, originalPrice: 8995.00, category: 'wearables', imageUrl: 'smartwatch' },
        { id: 6, name: 'Noise ColorFit Ultra 3 Smartwatch', description: 'Metallic finish, Bluetooth calling, 100+ sports modes with 2-year warranty.', totalQuantity: 60, availableQuantity: 60, price: 1999.00, originalPrice: 4999.00, category: 'wearables', imageUrl: 'smartwatch' },
        { id: 7, name: 'Sony PlayStation 5 Digital Edition Flash Drop', description: '1TB Custom SSD, Ray Tracing, 4K Gaming with DualSense Wireless Controller.', totalQuantity: 10, availableQuantity: 10, price: 39990.00, originalPrice: 44990.00, category: 'electronics', imageUrl: 'console' }
    ];
    renderStorefront();
}

// ---------- 4. Customer store ----------
function renderStorefront() {
    renderSpotlightItem();
    renderProductsGrid();
}

// big featured deal on top
function renderSpotlightItem() {
    const item = products.find(product => product.id === selectedProductId) || products[0];
    if (!item) return;

    selectedProductId = item.id;

    document.getElementById('spotlight-title').textContent = item.name;
    document.getElementById('spotlight-desc').textContent = item.description;
    document.getElementById('spotlight-price').textContent = `₹${formatRupees(item.price)}`;
    document.getElementById('spotlight-original-price').textContent = `₹${formatRupees(item.originalPrice || item.price * 1.8)}`;
    document.getElementById('spotlight-qty-left').textContent = item.availableQuantity;
    document.getElementById('spotlight-qty-total').textContent = item.totalQuantity;

    const discount = Math.round(((item.originalPrice - item.price) / item.originalPrice) * 100);
    document.getElementById('spotlight-discount-badge').textContent = `-${discount > 0 ? discount : 45}% OFF`;

    document.getElementById('spotlight-category-tag').textContent = item.category === 'travel' ? 'Travel Ticket' : (item.category === 'wearables' ? 'Wearable' : 'Electronics');
    document.getElementById('spotlight-emoji').textContent = categoryIcons[item.imageUrl] || categoryIcons[item.category] || '⚡';

    const percent = item.totalQuantity > 0 ? Math.round((item.availableQuantity / item.totalQuantity) * 100) : 0;
    document.getElementById('spotlight-progress-bar').style.width = `${percent}%`;

    const buyButton = document.getElementById('btn-spotlight-buy');
    const buyText = document.getElementById('btn-spotlight-text');
    const stockBadge = document.getElementById('spotlight-stock-badge');

    if (item.availableQuantity <= 0) {
        stockBadge.textContent = 'Sold Out';
        stockBadge.className = 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800';
        buyButton.disabled = true;
        buyButton.className = 'w-full sm:w-auto px-6 py-3 bg-stone-300 text-stone-500 font-bold rounded-xl text-sm cursor-not-allowed';
        buyText.textContent = 'Sold Out';
    } else {
        stockBadge.textContent = percent < 20 ? 'Selling Fast' : 'In Stock';
        stockBadge.className = percent < 20
            ? 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-amber-100 text-amber-800'
            : 'px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800';
        buyButton.disabled = false;
        buyButton.className = 'w-full sm:w-auto px-6 py-3 bg-orange-600 hover:bg-orange-700 text-white font-bold rounded-xl text-sm transition shadow-sm flex items-center justify-center space-x-2';
        buyText.textContent = `Book Ticket (₹${formatRupees(item.price)})`;
    }
}

// grid of all products
function renderProductsGrid() {
    const grid = document.getElementById('products-grid');
    if (!grid) return;

    let list = products;
    if (currentCategory !== 'all') {
        list = products.filter(product => product.category === currentCategory);
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

function selectSpotlightItem(id) {
    selectedProductId = id;
    renderSpotlightItem();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function setCategory(category) {
    currentCategory = category;

    ['all', 'travel', 'wearables', 'electronics'].forEach(name => {
        const button = document.getElementById(`pill-${name}`);
        if (!button) return;
        if (name === category) {
            button.className = 'cat-btn px-3 py-1.5 text-xs font-semibold rounded-lg bg-white text-orange-600 shadow-sm';
        } else {
            button.className = 'cat-btn px-3 py-1.5 text-xs font-semibold rounded-lg text-stone-600 hover:text-stone-900';
        }
    });

    renderProductsGrid();
}

// switch to next user (to test many buyers)
function changeRandomUser() {
    const nextIndex = (studentUsers.indexOf(activeUser) + 1) % studentUsers.length;
    activeUser = studentUsers[nextIndex];
    updateUserDisplay();
    showToast(`Switched user to: ${activeUser.name}`, 'info');
}

function updateUserDisplay() {
    const userText = document.getElementById('current-user-name');
    if (userText) userText.textContent = `User #${activeUser.id} (${activeUser.name})`;
}

// ---------- 5. Buying ----------
async function buySpotlightProduct() {
    await buyProductById(selectedProductId);
}

async function buyProductById(id) {
    const item = products.find(product => product.id === id);
    if (!item || item.availableQuantity <= 0) {
        showToast('Sorry! This item is already sold out.', 'error');
        return;
    }

    const buyButton = document.getElementById('btn-spotlight-buy');
    if (buyButton) buyButton.disabled = true;

    // backend does: WorkerPool -> WAL (log) -> Database
    try {
        const response = await fetch('/api/purchase', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: activeUser.id, productId: item.id })
        });
        const result = await response.json();

        if (result.success) {
            triggerConfetti();

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

            item.availableQuantity = result.remaining;
            if (item.availableQuantity <= 0) item.status = 'SOLD OUT';

            showReceiptModal(newOrder, result.remaining, result.workerName);
            renderStorefront();
        } else {
            showToast(result.message || 'Ticket could not be booked.', 'error');
        }
    } catch (error) {
        // server is off, book it locally
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

    if (buyButton) buyButton.disabled = false;
}

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

// ---------- 6. Admin panel ----------
function handleAdminButtonClick() {
    if (isAdminLoggedIn) {
        showAdminPanel();
    } else {
        openAdminModal();
    }
}

function openAdminModal() {
    document.getElementById('modal-admin-login')?.classList.remove('hidden');
    const pinInput = document.getElementById('input-pin');
    if (pinInput) {
        pinInput.value = '';
        setTimeout(() => pinInput.focus(), 100);
    }
}

function closeAdminModal() {
    document.getElementById('modal-admin-login')?.classList.add('hidden');
}

async function verifyAdminPin(event) {
    event.preventDefault();
    const pin = document.getElementById('input-pin')?.value.trim();

    try {
        const response = await fetch('/api/admin/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pin })
        });
        const data = await response.json();
        if (data.authenticated) {
            loginAsAdmin();
            return;
        }
    } catch (error) {
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
    if (label) label.textContent = isAdminLoggedIn ? 'Admin Panel' : 'Admin Login';
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

// switch admin tabs: inventory / orders / algorithms
function switchAdminTab(tab) {
    currentAdminTab = tab;

    const sections = {
        inventory: document.getElementById('admin-panel-inventory'),
        orders: document.getElementById('admin-panel-orders'),
        algorithms: document.getElementById('admin-panel-algorithms')
    };
    const buttons = {
        inventory: document.getElementById('tab-btn-inventory'),
        orders: document.getElementById('tab-btn-orders'),
        algorithms: document.getElementById('tab-btn-algorithms')
    };

    const activeClass = 'px-4 py-2 text-xs font-bold rounded-xl bg-orange-600 text-white shadow-sm';
    const normalClass = 'px-4 py-2 text-xs font-bold rounded-xl text-stone-600 hover:text-stone-900 hover:bg-stone-100';

    // hide everything first
    for (const name in sections) {
        sections[name]?.classList.add('hidden');
        if (buttons[name]) buttons[name].className = normalClass;
    }

    // show the selected tab
    sections[tab]?.classList.remove('hidden');
    if (buttons[tab]) buttons[tab].className = activeClass;

    if (tab === 'inventory') renderAdminProducts();
    if (tab === 'orders') renderAdminOrders();
    if (tab === 'algorithms') loadAlgorithmTelemetry();
}

function renderAdminProducts() {
    const tableBody = document.getElementById('admin-products-table');
    if (!tableBody) return;

    tableBody.innerHTML = products.map(product => `
        <tr class="hover:bg-stone-50 text-xs">
            <td class="py-3 px-4 font-semibold text-stone-900">${escapeHtml(product.name)}</td>
            <td class="py-3 px-4 text-stone-600 uppercase text-[11px] font-semibold">${product.category}</td>
            <td class="py-3 px-4 font-bold text-orange-600">₹${formatRupees(product.price)}</td>
            <td class="py-3 px-4 text-stone-400 line-through">₹${formatRupees(product.originalPrice)}</td>
            <td class="py-3 px-4 font-medium">${product.availableQuantity} / ${product.totalQuantity}</td>
            <td class="py-3 px-4">
                <span class="px-2 py-0.5 rounded-full text-[10px] font-bold ${product.availableQuantity > 0 ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}">
                    ${product.availableQuantity > 0 ? 'ACTIVE' : 'SOLD OUT'}
                </span>
            </td>
            <td class="py-3 px-4 text-right space-x-1.5 whitespace-nowrap">
                <button onclick="restockItem(${product.id}, ${product.totalQuantity})" class="px-2.5 py-1 bg-stone-100 hover:bg-stone-200 text-stone-700 font-semibold rounded-lg">Restock</button>
                <button onclick="deleteItem(${product.id})" class="px-2.5 py-1 bg-rose-50 hover:bg-rose-100 text-rose-700 font-semibold rounded-lg">Delete</button>
            </td>
        </tr>
    `).join('');
}

function renderAdminOrders() {
    const tableBody = document.getElementById('admin-orders-table');
    if (!tableBody) return;

    if (orders.length === 0) {
        tableBody.innerHTML = '<tr><td colspan="6" class="py-6 text-center text-stone-400 text-xs">No orders recorded yet.</td></tr>';
        return;
    }

    tableBody.innerHTML = orders.map(order => `
        <tr class="hover:bg-stone-50 text-xs">
            <td class="py-3 px-4 font-bold text-stone-900">#ORD-${order.purchaseId}</td>
            <td class="py-3 px-4 text-orange-700 font-semibold">User #${order.userId}</td>
            <td class="py-3 px-4 text-stone-800">${escapeHtml(order.productName)}</td>
            <td class="py-3 px-4 font-bold text-stone-900">₹${formatRupees(order.price || 0)}</td>
            <td class="py-3 px-4">
                <span class="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-800">
                    ${order.status || 'SUCCESS'}
                </span>
            </td>
            <td class="py-3 px-4 text-right text-stone-500">${order.purchaseTime}</td>
        </tr>
    `).join('');
}

function searchOrders() {
    const searchText = document.getElementById('order-search-input')?.value.toLowerCase() || '';
    const rows = document.querySelectorAll('#admin-orders-table tr');
    rows.forEach(row => {
        row.style.display = row.textContent.toLowerCase().includes(searchText) ? '' : 'none';
    });
}

function openAddModal() {
    document.getElementById('modal-add-item')?.classList.remove('hidden');
}

function closeAddModal() {
    document.getElementById('modal-add-item')?.classList.add('hidden');
}

function handleCategoryChange(category) {
    const nameInput = document.getElementById('new-item-name');
    if (category === 'travel') {
        nameInput.placeholder = 'e.g. Goa Flight Ticket or Manali Volvo Pass';
    } else if (category === 'wearables') {
        nameInput.placeholder = 'e.g. Titan Smartwatch or Ultrahuman Ring';
    } else {
        nameInput.placeholder = 'e.g. Sony PlayStation 5 or Earbuds';
    }
}

async function handleAddNewItem(event) {
    event.preventDefault();

    const name = document.getElementById('new-item-name')?.value.trim();
    const description = document.getElementById('new-item-desc')?.value.trim();
    const category = document.getElementById('new-item-category')?.value;
    const price = parseFloat(document.getElementById('new-item-price')?.value);
    const originalPrice = parseFloat(document.getElementById('new-item-original')?.value);
    const quantity = parseInt(document.getElementById('new-item-qty')?.value, 10);

    if (!name || isNaN(price) || isNaN(quantity)) return;

    try {
        const response = await fetch('/api/admin/products', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description, price, originalPrice, quantity, category })
        });
        if (response.ok) {
            closeAddModal();
            await loadProducts();
            showToast('New item published successfully!', 'success');
            return;
        }
    } catch (error) {}

    // server is off, add it locally
    const newItem = {
        id: products.length + 1,
        name,
        description,
        price,
        originalPrice: originalPrice || price * 1.8,
        totalQuantity: quantity,
        availableQuantity: quantity,
        category,
        imageUrl: category === 'travel' ? 'flight' : (category === 'wearables' ? 'smartwatch' : 'tech')
    };
    products.push(newItem);
    closeAddModal();
    renderStorefront();
    renderAdminProducts();
    showToast('New item published successfully!', 'success');
}

async function restockItem(id, totalQuantity) {
    try {
        await fetch('/api/admin/reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productId: id, quantity: totalQuantity })
        });
    } catch (error) {}

    const item = products.find(product => product.id === id);
    if (item) {
        item.availableQuantity = totalQuantity;
        item.status = 'ACTIVE';
        renderStorefront();
        renderAdminProducts();
        showToast('Item stock has been reset to full.', 'success');
    }
}

async function deleteItem(id) {
    if (!confirm('Are you sure you want to remove this item?')) return;

    try {
        await fetch('/api/admin/products/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productId: id })
        });
    } catch (error) {}

    products = products.filter(product => product.id !== id);
    renderStorefront();
    renderAdminProducts();
    showToast('Item removed from catalog.', 'info');
}

function downloadOrdersCSV() {
    if (orders.length === 0) {
        showToast('No orders to download.', 'info');
        return;
    }

    let csv = 'Order ID,Customer ID,Item Name,Amount Paid (INR),Status,Timestamp\n';
    orders.forEach(order => {
        csv += `${order.purchaseId},${order.userId},"${order.productName.replace(/"/g, '""')}",${order.price},${order.status || 'SUCCESS'},"${order.purchaseTime}"\n`;
    });

    const file = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(file);
    const link = document.createElement('a');
    link.href = url;
    link.download = `orders_${Date.now()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
    showToast('Orders downloaded as CSV.', 'success');
}

// ---------- 7. WorkerPool, WAL and Wait-For Graph ----------
async function loadAlgorithmTelemetry(silent = false) {
    try {
        const [walResponse, poolResponse] = await Promise.all([
            fetch('/api/admin/wal'),
            fetch('/api/admin/workerpool')
        ]);

        if (walResponse.ok && poolResponse.ok) {
            const wal = await walResponse.json();
            const pool = await poolResponse.json();

            // WorkerPool
            document.getElementById('worker-queue-size').textContent = pool.queueSize || 0;
            const queuePercent = Math.min(100, Math.round(((pool.queueSize || 0) / (pool.queueCapacity || 2000)) * 100));
            document.getElementById('worker-queue-bar').style.width = `${queuePercent}%`;
            document.getElementById('worker-count-badge').textContent = `${pool.workerCount || 8} Workers Online`;

            const workerGrid = document.getElementById('worker-cards-grid');
            if (workerGrid && Array.isArray(pool.workers)) {
                workerGrid.innerHTML = pool.workers.map(worker => `
                    <div class="bg-stone-50 border border-stone-200 rounded-xl p-2.5 text-xs">
                        <div class="flex items-center justify-between font-semibold text-stone-800">
                            <span>${worker.name}</span>
                            <span class="w-2 h-2 rounded-full ${worker.status === 'IDLE' ? 'bg-emerald-500' : 'bg-orange-500 animate-pulse'}"></span>
                        </div>
                        <div class="text-[10px] text-stone-500 mt-1 flex justify-between">
                            <span>Status: ${worker.status}</span>
                            <span>Done: ${worker.tasksProcessed}</span>
                        </div>
                    </div>
                `).join('');
            }

            // WAL
            document.getElementById('wal-lsn-badge').textContent = `#${wal.currentLsn || 1000}`;
            const walTerminal = document.getElementById('wal-log-terminal');
            if (walTerminal && Array.isArray(wal.records)) {
                if (wal.records.length === 0) {
                    walTerminal.innerHTML = '<div class="text-stone-500">// WAL log is clean.</div>';
                } else {
                    walTerminal.innerHTML = wal.records.map(record => `
                        <div class="text-[11px] leading-relaxed">
                            <span class="text-orange-400 font-bold">[LSN #${record.lsn}]</span>
                            <span class="${record.opType === 'ORDER_COMMIT' ? 'text-emerald-400 font-bold' : (record.opType === 'STOCK_RESERVE' ? 'text-cyan-400' : 'text-stone-400')}">${record.opType}</span>
                            <span class="text-stone-300">| User #${record.userId}</span>
                            <span class="text-stone-200">| "${escapeHtml(record.productName)}"</span>
                            <span class="text-stone-400">| [${record.status}]</span>
                        </div>
                    `).join('');
                }
            }
        }
    } catch (error) {
        if (!silent) console.log('Offline telemetry.');
    }

    await loadWaitForGraph(silent);
}

async function triggerCheckpoint() {
    try {
        const response = await fetch('/api/admin/checkpoint', { method: 'POST' });
        if (response.ok) {
            const data = await response.json();
            showToast(`WAL Checkpoint created at LSN #${data.checkpointLsn}`, 'success');
            loadAlgorithmTelemetry();
            return;
        }
    } catch (error) {}

    showToast('WAL Checkpoint saved.', 'success');
}

// ---------- 8. Wait-For Graph ----------

// get the graph from backend and draw it
async function loadWaitForGraph(silent = false) {
    try {
        const response = await fetch('/api/admin/waitforgraph');
        if (!response.ok) return;
        const graph = await response.json();

        document.getElementById('wfg-total-waits').textContent = graph.totalWaits;
        document.getElementById('wfg-total-deadlocks').textContent = graph.totalDeadlocks;
        document.getElementById('wfg-last-deadlock').textContent = graph.lastDeadlock;

        const badge = document.getElementById('wfg-status-badge');
        if (graph.deadlock) {
            badge.textContent = 'DEADLOCK!';
            badge.className = 'bg-rose-100 text-rose-800 text-xs font-semibold px-2.5 py-1 rounded-full animate-pulse';
        } else {
            badge.textContent = 'No Deadlock';
            badge.className = 'bg-emerald-100 text-emerald-800 text-xs font-semibold px-2.5 py-1 rounded-full';
        }

        drawWaitForGraph(graph);
    } catch (error) {
        if (!silent) console.log('Wait-for graph offline.');
    }
}

// draw threads as circles and "waiting for" as arrows (SVG)
function drawWaitForGraph(graph) {
    const box = document.getElementById('wfg-graph');
    if (!box) return;

    if (graph.edges.length === 0) {
        box.innerHTML = '<span class="text-xs text-stone-400">No thread is waiting right now.</span>';
        return;
    }

    // every thread that is in some edge is a node
    const names = [];
    graph.edges.forEach(edge => {
        if (!names.includes(edge.from)) names.push(edge.from);
        if (!names.includes(edge.to)) names.push(edge.to);
    });

    // put the nodes on a circle
    const width = 460, height = 260, centerX = width / 2, centerY = height / 2, radius = 85, nodeSize = 30;
    const spots = {};
    names.forEach((name, index) => {
        const angle = (2 * Math.PI * index) / names.length - Math.PI / 2;
        spots[name] = { x: centerX + radius * Math.cos(angle), y: centerY + radius * Math.sin(angle) };
    });

    let svg = `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
        <defs>
            <marker id="arrow-normal" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#78716C"/></marker>
            <marker id="arrow-red" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#E11D48"/></marker>
        </defs>`;

    // arrows (red if the thread is in the deadlock cycle)
    graph.edges.forEach(edge => {
        const from = spots[edge.from];
        const to = spots[edge.to];
        const inCycle = graph.cycle.includes(edge.from) && graph.cycle.includes(edge.to);

        // start and end of arrow at the edge of the circles
        const dx = to.x - from.x, dy = to.y - from.y;
        const length = Math.sqrt(dx * dx + dy * dy) || 1;
        const startX = from.x + (dx / length) * nodeSize, startY = from.y + (dy / length) * nodeSize;
        const endX = to.x - (dx / length) * (nodeSize + 3), endY = to.y - (dy / length) * (nodeSize + 3);

        // bend the line a little so two opposite arrows do not overlap
        const bendX = (startX + endX) / 2 - (dy / length) * 22;
        const bendY = (startY + endY) / 2 + (dx / length) * 22;
        const color = inCycle ? '#E11D48' : '#78716C';

        svg += `<path d="M${startX},${startY} Q${bendX},${bendY} ${endX},${endY}" fill="none" stroke="${color}" stroke-width="2" marker-end="url(#arrow-${inCycle ? 'red' : 'normal'})"/>`;
        svg += `<text x="${bendX}" y="${bendY - 4}" font-size="10" text-anchor="middle" fill="${color}" font-weight="600">${escapeHtml(edge.lock)}</text>`;
    });

    // circles with thread names
    names.forEach(name => {
        const spot = spots[name];
        const inCycle = graph.cycle.includes(name);
        const shortName = name.replace('FlashWorker-', 'Worker-').replace('Demo-Thread-', 'T');
        svg += `<circle cx="${spot.x}" cy="${spot.y}" r="${nodeSize}" fill="${inCycle ? '#FFE4E6' : '#FFEDD5'}" stroke="${inCycle ? '#E11D48' : '#F97316'}" stroke-width="2"/>`;
        svg += `<text x="${spot.x}" y="${spot.y + 4}" font-size="11" text-anchor="middle" font-weight="700" fill="#1C1917">${escapeHtml(shortName)}</text>`;
    });

    box.innerHTML = svg + '</svg>';
}

// button: start two threads that wait for each other
async function runDeadlockDemo() {
    try {
        const response = await fetch('/api/admin/waitforgraph/demo', { method: 'POST' });
        const data = await response.json();
        if (!data.success) {
            showToast(data.message, 'info');
            return;
        }
        showToast('Deadlock demo started. Watch the graph!', 'success');

        // check the graph every second while the demo is running
        for (let second = 1; second <= 14; second++) {
            setTimeout(() => loadWaitForGraph(true), second * 1000);
        }
    } catch (error) {
        showToast('Server is not running, demo needs the Java backend.', 'error');
    }
}

// ---------- 9. Small helper functions ----------

// 1234.5 -> 1,234.50 (Indian format)
function formatRupees(amount) {
    if (isNaN(amount)) return '0.00';
    return Number(amount).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

function startCountdownTimer() {
    let hours = 2, minutes = 18, seconds = 45;
    setInterval(() => {
        seconds--;
        if (seconds < 0) {
            seconds = 59;
            minutes--;
            if (minutes < 0) {
                minutes = 59;
                hours--;
                if (hours < 0) hours = 2;
            }
        }
        document.getElementById('timer-hours').textContent = String(hours).padStart(2, '0');
        document.getElementById('timer-mins').textContent = String(minutes).padStart(2, '0');
        document.getElementById('timer-secs').textContent = String(seconds).padStart(2, '0');
    }, 1000);
}

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

function showToast(message, type = 'info') {
    const toastBox = document.getElementById('toast-box');
    if (!toastBox) return;

    const color = type === 'success' ? 'bg-emerald-600 text-white' : (type === 'error' ? 'bg-rose-600 text-white' : 'bg-stone-900 text-white');
    const toast = document.createElement('div');
    toast.className = `toast-message px-4 py-2.5 rounded-xl shadow-lg text-xs font-medium flex items-center space-x-2 ${color}`;
    toast.innerHTML = `<span>${type === 'success' ? '✓' : (type === 'error' ? '✕' : 'ℹ')}</span><span>${escapeHtml(message)}</span>`;

    toastBox.appendChild(toast);
    setTimeout(() => toast.remove(), 2800);
}

// so that text from the server cannot break the page
function escapeHtml(text) {
    if (!text) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
