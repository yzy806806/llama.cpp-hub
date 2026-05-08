(function () {
    const logEl = document.getElementById('consoleContent');
    const logContainer = document.getElementById('logContainer');
    const consoleStatusText = document.getElementById('consoleStatusText');
    const refreshConsoleBtn = document.getElementById('refreshConsoleBtn');

    let pendingLogs = [];
    let pendingLogsWithTs = [];
    let flushScheduled = false;
    let snapshotInFlight = false;

    function nearBottom() {
        if (!logContainer) return true;
        return Math.abs(logContainer.scrollHeight - logContainer.scrollTop - logContainer.clientHeight) < 50;
    }

    function scrollBottom() {
        if (!logContainer) return;
        logContainer.scrollTop = logContainer.scrollHeight;
    }

    async function fetchConsole() {
        if (consoleStatusText) consoleStatusText.textContent = '加载中...';
        snapshotInFlight = true;
        try {
            const res = await fetch('/api/sys/console');
            const text = await res.text();
            const atBottom = nearBottom();
            if (logEl) logEl.textContent = text;
            snapshotInFlight = false;
            flushPendingLogs();
            if (atBottom) scrollBottom();
            if (consoleStatusText) {
                consoleStatusText.textContent = '已更新 · ' + new Date().toLocaleTimeString() + ' · Size: ' + text.length;
            }
        } catch (e) {
            snapshotInFlight = false;
            if (consoleStatusText) consoleStatusText.textContent = '加载失败';
        }
    }

    function openConsoleModal() {
        initConsoleTabs();
        fetchConsole();
        var nodes = window.remoteNodes || [];
        nodes.forEach(function (n) {
            if (n.enabled !== false && n.nodeId) {
                fetchRemoteConsole(n.nodeId, n.baseUrl);
            }
        });
        setTimeout(function () {
            scrollBottom();
        }, 100);
    }

    function appendLogLine(line, timestamp) {
        if (!logEl) return;
        const clean = (line || '').replace(/\r/g, '');
        const withNl = clean.endsWith('\n') ? clean : clean + '\n';
        pendingLogs.push(withNl);
        pendingLogsWithTs.push({ text: withNl, ts: typeof timestamp === 'number' ? timestamp : 0 });
        if (snapshotInFlight) return;
        scheduleFlush();
    }

    function scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            requestAnimationFrame(function () {
                flushPendingLogs();
            });
        }
    }

    function flushPendingLogs() {
        flushScheduled = false;
        if (snapshotInFlight || !pendingLogs.length || !logEl) return;
        const atBottom = nearBottom();
        pendingLogsWithTs.sort(function (a, b) { return a.ts - b.ts; });
        var chunk = '';
        for (var i = 0; i < pendingLogsWithTs.length; i++) {
            chunk += pendingLogsWithTs[i].text;
        }
        pendingLogs = [];
        pendingLogsWithTs = [];
        logEl.textContent += chunk;
        if (atBottom) scrollBottom();
    }

    function startConsoleAuto() {
        stopConsoleAuto();
        const interval = Math.max(500, parseInt((intervalConsoleInput && intervalConsoleInput.value) || '2000', 10));
        consoleTimer = setInterval(fetchConsole, interval);
    }

    function stopConsoleAuto() {}

    function initConsoleTabs() {
        const tabBar = document.getElementById('consoleTabs');
        const container = document.getElementById('consoleTabContainer');
        if (!tabBar || !container) return;
        if (tabBar._initialized) return;
        tabBar._initialized = true;

        var nodes = window.remoteNodes || [];
        nodes.forEach(function (node) {
            if (node.enabled === false) return;
            var nid = node.nodeId;
            var tab = document.createElement('button');
            tab.className = 'console-tab-btn';
            tab.dataset.tab = nid;
            tab.textContent = node.name || nid;
            tabBar.appendChild(tab);

            var panel = document.createElement('div');
            panel.className = 'console-tab-panel';
            panel.dataset.panel = nid;
            panel.innerHTML = '<div id="remoteLogContainer-' + nid + '" style="height:100%;overflow:auto;padding:16px;font-family:\'Menlo\',\'Monaco\',\'Courier New\',monospace;background:#0f172a;color:#e5e7eb;font-size:0.875rem;"><pre id="remoteConsoleContent-' + nid + '" style="margin:0;white-space:pre-wrap;word-break:break-word;overflow-wrap:break-word;"></pre></div>';
            container.appendChild(panel);
        });

        tabBar.addEventListener('click', function (e) {
            var btn = e.target.closest('.console-tab-btn');
            if (!btn) return;
            tabBar.querySelectorAll('.console-tab-btn').forEach(function (b) { b.classList.remove('active'); });
            btn.classList.add('active');
            var id = btn.dataset.tab;
            container.querySelectorAll('.console-tab-panel').forEach(function (p) {
                p.classList.toggle('active', p.dataset.panel === id);
            });
        });
    }

    async function fetchRemoteConsole(nodeId, baseUrl) {
        try {
            var url = baseUrl.replace(/\/+$/, '') + '/api/sys/console';
            var resp = await fetch(url);
            var text = await resp.text();
            var pre = document.getElementById('remoteConsoleContent-' + nodeId);
            if (pre && text) pre.textContent = text;
        } catch (e) {}
    }

    function appendRemoteLogLine(nodeId, text) {
        var pre = document.getElementById('remoteConsoleContent-' + nodeId);
        if (!pre) return;
        var clean = (text || '').replace(/\r/g, '');
        var withNl = clean.endsWith('\n') ? clean : clean + '\n';
        pre.textContent += withNl;
        var container = document.getElementById('remoteLogContainer-' + nodeId);
        if (container) container.scrollTop = container.scrollHeight;
    }

    if (refreshConsoleBtn) refreshConsoleBtn.addEventListener('click', fetchConsole);

    window.openConsoleModal = openConsoleModal;
    window.appendLogLine = appendLogLine;
    window.stopConsoleAuto = stopConsoleAuto;
    window.initConsoleTabs = initConsoleTabs;
    window.appendRemoteLogLine = appendRemoteLogLine;
})();