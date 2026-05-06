(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    function t(key, fallback) {
        if (typeof window.t === 'function') return window.t(key, fallback);
        return fallback || key;
    }

    function switchTab(tabName) {
        document.querySelectorAll('.settings-tab').forEach(btn => {
            btn.classList.toggle('active', btn.getAttribute('data-tab') === tabName);
        });
        document.querySelectorAll('.settings-tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.getAttribute('data-tab-panel') === tabName);
        });
    }

    async function loadSettings() {
        try {
            const resp = await fetch('/api/sys/setting', { method: 'GET' });
            const result = await resp.json();
            if (!result || !result.success || !result.data) return;
            populate(result.data);
        } catch (e) {
        }
    }

    let _populating = false;

    function populate(data) {
        _populating = true;
        // Server
        const s = data.server;
        if (s) {
            const webPort = byId('webPortInput');
            if (webPort && s.webPort) webPort.value = s.webPort;
        }

        // Compatibility
        const c = data.compat;
        if (c) {
            const ollamaToggle = byId('toggleOllamaCompat');
            if (ollamaToggle && c.ollama) ollamaToggle.checked = !!c.ollama.enabled;
            const ollamaPort = byId('ollamaCompatPortInput');
            if (ollamaPort && c.ollama && c.ollama.port) ollamaPort.value = c.ollama.port;

            const lmstudioToggle = byId('toggleLmstudioCompat');
            if (lmstudioToggle && c.lmstudio) lmstudioToggle.checked = !!c.lmstudio.enabled;
            const lmstudioPort = byId('lmstudioCompatPortInput');
            if (lmstudioPort && c.lmstudio && c.lmstudio.port) lmstudioPort.value = c.lmstudio.port;

            const mcpToggle = byId('toggleMcpServer');
            if (mcpToggle && c.mcpServer) mcpToggle.checked = !!c.mcpServer.enabled;
        }

        // Security
        const sec = data.security;
        if (sec) {
            const apiKeyToggle = byId('toggleApiKeyEnabled');
            if (apiKeyToggle) apiKeyToggle.checked = !!sec.apiKeyEnabled;
            const apiKey = byId('apiKeyInput');
            if (apiKey && sec.apiKey) apiKey.value = sec.apiKey;
            updateApiKeyInputState();
        }

        // HTTPS
        const https = data.https;
        if (https) {
            const httpsToggle = byId('toggleHttpsEnabled');
            if (httpsToggle) httpsToggle.checked = !!https.enabled;
            const certPath = byId('httpsCertPathInput');
            if (certPath && https.keystorePath) certPath.value = https.keystorePath;
            const password = byId('httpsPasswordInput');
            if (password && https.keystorePassword) password.value = https.keystorePassword;
            updateHttpsInputState();
        }

        // Logging
        const log = data.logging;
        if (log) {
            const url = byId('toggleLogRequestUrl');
            if (url) url.checked = !!log.logRequestUrl;
            const header = byId('toggleLogRequestHeader');
            if (header) header.checked = !!log.logRequestHeader;
            const body = byId('toggleLogRequestBody');
            if (body) body.checked = !!log.logRequestBody;
        }

        // Download
        const dl = data.download;
        if (dl) {
            const dir = byId('downloadDirInput');
            if (dir && dl.directory) dir.value = dl.directory;
        }
        _populating = false;
    }

    // API Key toggle — enable/disable the input field
    function updateApiKeyInputState() {
        const toggle = byId('toggleApiKeyEnabled');
        const input = byId('apiKeyInput');
        if (toggle && input) {
            input.disabled = !toggle.checked;
        }
    }

    // HTTPS toggle — enable/disable the fields
    function updateHttpsInputState() {
        const toggle = byId('toggleHttpsEnabled');
        const pathInput = byId('httpsCertPathInput');
        const passInput = byId('httpsPasswordInput');
        if (toggle && pathInput && passInput) {
            pathInput.disabled = !toggle.checked;
            passInput.disabled = !toggle.checked;
        }
    }

    async function saveServerPorts() {
        const webPort = byId('webPortInput');
        const payload = {};
        if (webPort && webPort.value) payload.webPort = Number(webPort.value);
        if (!payload.webPort) {
            toast(t('toast.error', '错误'), '请填写端口', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveCompatPorts() {
        const ollamaPort = byId('ollamaCompatPortInput');
        const lmstudioPort = byId('lmstudioCompatPortInput');
        const payload = {};
        if (ollamaPort && ollamaPort.value) payload.ollamaPort = Number(ollamaPort.value);
        if (lmstudioPort && lmstudioPort.value) payload.lmstudioPort = Number(lmstudioPort.value);
        if (!payload.ollamaPort && !payload.lmstudioPort) {
            toast(t('toast.error', '错误'), '请至少填写一个端口', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function setCompatService(type, enable, toggleEl) {
        const endpoint = type === 'ollama' ? '/api/sys/ollama' : '/api/sys/lmstudio';
        const prev = !enable;
        if (toggleEl) toggleEl.disabled = true;
        try {
            const body = { enable: !!enable };
            const portInput = type === 'ollama' ? byId('ollamaCompatPortInput') : byId('lmstudioCompatPortInput');
            if (portInput && portInput.value) body.port = Number(portInput.value);
            const resp = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (toggleEl) toggleEl.checked = prev;
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.operation_failed', '操作失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), enable ? t('common.enabled', '已开启') : t('common.disabled', '已关闭'), 'success');
            loadSettings();
        } catch (e) {
            if (toggleEl) toggleEl.checked = prev;
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (toggleEl) toggleEl.disabled = false;
        }
    }

    async function setMcpServer(enable) {
        const toggleEl = byId('toggleMcpServer');
        const prev = !enable;
        if (toggleEl) toggleEl.disabled = true;
        try {
            const resp = await fetch('/api/sys/mcp', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enable: enable })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                if (toggleEl) toggleEl.checked = prev;
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.operation_failed', '操作失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), enable ? t('common.enabled', '已开启') : t('common.disabled', '已关闭'), 'success');
        } catch (e) {
            if (toggleEl) toggleEl.checked = prev;
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (toggleEl) toggleEl.disabled = false;
        }
    }

    async function saveSecurity() {
        const toggle = byId('toggleApiKeyEnabled');
        const keyInput = byId('apiKeyInput');
        const payload = {};
        if (toggle) payload.apiKeyEnabled = toggle.checked;
        if (keyInput && keyInput.value) payload.apiKey = keyInput.value;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveHttps() {
        const toggle = byId('toggleHttpsEnabled');
        const certPath = byId('httpsCertPathInput');
        const password = byId('httpsPasswordInput');
        const payload = {};
        if (toggle) payload.httpsEnabled = toggle.checked;
        if (certPath && certPath.value) payload.httpsCertPath = certPath.value;
        if (password && password.value) payload.httpsPassword = password.value;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function loadHttpsSetupGuide() {
        const container = byId('httpsSetupDoc');
        if (!container) return;
        try {
            const lang = (window.I18N && window.I18N.lang) || 'zh-CN';
            const docPath = lang.startsWith('en') ? 'docs/HTTPS_SETUP.en.md' : 'docs/HTTPS_SETUP.zh.md';
            const resp = await fetch(docPath);
            if (!resp.ok) { container.textContent = 'Failed to load setup guide'; return; }
            const md = await resp.text();
            if (typeof marked !== 'undefined' && typeof marked.parse === 'function') {
                container.innerHTML = marked.parse(md);
            } else {
                container.textContent = md;
            }
        } catch (e) {
            container.textContent = 'Failed to load setup guide';
        }
    }

    async function saveLogging() {
        const url = byId('toggleLogRequestUrl');
        const header = byId('toggleLogRequestHeader');
        const body = byId('toggleLogRequestBody');
        const payload = {};
        if (url) payload.logRequestUrl = url.checked;
        if (header) payload.logRequestHeader = header.checked;
        if (body) payload.logRequestBody = body.checked;
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function saveDownload() {
        const dir = byId('downloadDirInput');
        if (!dir || !dir.value.trim()) {
            toast(t('toast.error', '错误'), '请填写下载目录路径', 'error');
            return;
        }
        try {
            const resp = await fetch('/api/sys/setting', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ downloadDirectory: dir.value.trim() })
            });
            const data = await resp.json();
            if (!data || !data.success) {
                toast(t('toast.error', '错误'), (data && data.error) ? data.error : t('common.save_failed', '保存失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), t('common.saved', '已保存'), 'success');
            loadSettings();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    // --- Node management ---
    let _editingNodeId = null;
    let _isMaster = false;

    function escHtml(s) {
        if (!s) return '';
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    async function loadNodes() {
        const listEl = byId('nodeList');
        const emptyEl = byId('nodeEmptyState');
        const bannerEl = byId('nodeMasterBanner');
        const toolbarEl = byId('nodeToolbar');
        if (!listEl) return;
        try {
            var infoResp = await fetch('/api/node/info', { method: 'GET' });
            var infoResult = await infoResp.json();
            _isMaster = infoResult && infoResult.success && infoResult.data && infoResult.data.isMaster === true;

            if (bannerEl) bannerEl.style.display = _isMaster ? 'none' : '';
            if (toolbarEl) toolbarEl.style.display = _isMaster ? '' : 'none';
            if (emptyEl) emptyEl.style.display = 'none';

            if (!_isMaster) {
                listEl.innerHTML = '';
                return;
            }

            const resp = await fetch('/api/node/list', { method: 'GET' });
            const result = await resp.json();
            if (!result || !result.success) {
                listEl.innerHTML = '';
                if (emptyEl) emptyEl.style.display = '';
                return;
            }
            const nodes = result.data || [];
            listEl.innerHTML = nodes.map(function (n) { return buildNodeRow(n, _isMaster); }).join('');
            if (emptyEl) emptyEl.style.display = nodes.length === 0 ? '' : 'none';
        } catch (e) {
            listEl.innerHTML = '<div style="color:red;padding:1rem;text-align:center;">加载失败</div>';
        }
    }

    function buildNodeRow(node, isMaster) {
        const statusClass = (node.status || 'PENDING').toLowerCase();
        const statusLabels = { online: t('page.settings.nodes.status.online', '在线'), offline: t('page.settings.nodes.status.offline', '离线'), pending: t('page.settings.nodes.status.pending', '待定') };
        const statusLabel = statusLabels[statusClass] || t('page.settings.nodes.status.pending', '待定');
        const tagsHtml = (node.tags || []).map(function (t) { return '<span class="node-tag">' + escHtml(t) + '</span>'; }).join('');
        var nid = escHtml(node.nodeId);
        var actionsHtml = '';
        if (isMaster) {
            actionsHtml = '<div style="display:flex;align-items:center;gap:0.25rem;">'
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.testNode(\'' + nid + '\',this)" title="测试连通性"><i class="fas fa-plug"></i></button>'
                + '<button class="btn btn-sm btn-secondary" onclick="SettingsPage.editNode(\'' + nid + '\')" title="编辑"><i class="fas fa-edit"></i></button>'
                + '<button class="btn btn-sm btn-danger" onclick="SettingsPage.removeNode(\'' + nid + '\')" title="删除"><i class="fas fa-trash"></i></button>'
                + '<input type="checkbox" ' + (node.enabled ? 'checked' : '') + ' onchange="SettingsPage.toggleNode(\'' + nid + '\',this.checked)" style="margin-left:0.25rem;">'
                + '</div>';
        }
        return '<div class="settings-toggle-row" style="flex-wrap:wrap;">'
            + '<div style="display:flex;align-items:center;gap:0.5rem;flex:1;min-width:0;">'
            + '<span class="status-dot ' + statusClass + '" title="' + statusLabel + '"></span>'
            + '<div style="min-width:0;">'
            + '<div style="font-weight:600;font-size:0.8rem;">' + escHtml(node.name || node.nodeId) + ' <span style="font-weight:400;color:var(--text-secondary);">(' + nid + ')</span></div>'
            + '<div style="font-size:0.7rem;color:var(--text-secondary);word-break:break-all;">' + escHtml(node.baseUrl) + '</div>'
            + '</div></div>'
            + '<div style="display:flex;align-items:center;gap:0.35rem;flex-wrap:wrap;">' + tagsHtml + '</div>'
            + actionsHtml + '</div>';
    }

    function openNodeForm(data) {
        _editingNodeId = null;
        var titleEl = byId('nodeFormTitle');
        if (titleEl) titleEl.textContent = t('modal.node.add_title', '添加节点');
        byId('nodeFormId').value = '';
        byId('nodeFormId').disabled = false;
        byId('nodeFormName').value = '';
        byId('nodeFormUrl').value = '';
        byId('nodeFormKey').value = '';
        byId('nodeFormTags').value = '';
        byId('nodeFormEnabled').checked = true;
        if (data) {
            _editingNodeId = data.nodeId;
            if (titleEl) titleEl.textContent = t('modal.node.edit_title', '编辑节点');
            byId('nodeFormId').value = data.nodeId;
            byId('nodeFormId').disabled = true;
            byId('nodeFormName').value = data.name || '';
            byId('nodeFormUrl').value = data.baseUrl || '';
            byId('nodeFormKey').value = data.apiKey || '';
            byId('nodeFormTags').value = (data.tags || []).join(', ');
            byId('nodeFormEnabled').checked = data.enabled !== false;
        }
        var m = document.getElementById('nodeFormModal');
        if (m) m.classList.add('show');
    }

    async function saveNodeForm() {
        var nodeId = byId('nodeFormId').value.trim();
        var name = byId('nodeFormName').value.trim();
        var baseUrl = byId('nodeFormUrl').value.trim();
        var apiKey = byId('nodeFormKey').value;
        var tagsStr = byId('nodeFormTags').value.trim();
        var enabled = byId('nodeFormEnabled').checked;

        if (!nodeId) { toast(t('toast.error', '错误'), '节点 ID 不能为空', 'error'); return; }
        if (!baseUrl) { toast(t('toast.error', '错误'), '地址不能为空', 'error'); return; }
        if (baseUrl.indexOf('http://') !== 0 && baseUrl.indexOf('https://') !== 0) {
            toast(t('toast.error', '错误'), '地址必须以 http:// 或 https:// 开头', 'error'); return;
        }

        var tags = tagsStr ? tagsStr.split(/\s*,\s*/).filter(Boolean) : [];
        var payload = { nodeId: nodeId, name: name, baseUrl: baseUrl, apiKey: apiKey, tags: tags, enabled: enabled };
        var isEdit = !!_editingNodeId;
        var endpoint = isEdit ? '/api/node/update' : '/api/node/add';

        try {
            var resp = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            var result = await resp.json();
            if (!result || !result.success) {
                toast(t('toast.error', '错误'), (result && result.error) ? result.error : (isEdit ? '更新失败' : '添加失败'), 'error');
                return;
            }
            toast(t('toast.success', '成功'), isEdit ? '已更新' : '已添加', 'success');
            closeModal('nodeFormModal');
            loadNodes();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function editNode(nodeId) {
        try {
            var resp = await fetch('/api/node/list', { method: 'GET' });
            var result = await resp.json();
            if (result && result.success) {
                var nodes = result.data || [];
                for (var i = 0; i < nodes.length; i++) {
                    if (nodes[i].nodeId === nodeId) { openNodeForm(nodes[i]); return; }
                }
            }
            toast(t('toast.error', '错误'), '节点不存在', 'error');
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function removeNode(nodeId) {
        if (!confirm(t('confirm.node.delete', '确认删除节点 "' + nodeId + '" ？').replace('{nodeId}', nodeId))) return;
        try {
            var resp = await fetch('/api/node/remove', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId })
            });
            var result = await resp.json();
            if (!result || !result.success) {
                toast(t('toast.error', '错误'), (result && result.error) ? result.error : '删除失败', 'error');
                return;
            }
            toast(t('toast.success', '成功'), '已删除', 'success');
            loadNodes();
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        }
    }

    async function testNode(nodeId, btn) {
        if (btn) btn.disabled = true;
        try {
            var resp = await fetch('/api/node/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId })
            });
            var result = await resp.json();
            if (result && result.success && result.data) {
                var d = result.data;
                if (d.connected) {
                    toast(t('toast.success', '成功'), nodeId + ' 连通成功 (' + d.latency + 'ms) 版本: ' + (d.version || '未知'), 'success');
                } else {
                    toast(t('toast.error', '错误'), nodeId + ' 连接失败 (' + (d.statusCode || '超时') + ')', 'error');
                }
            } else {
                toast(t('toast.error', '错误'), '测试失败', 'error');
            }
        } catch (e) {
            toast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    async function toggleNode(nodeId, enabled) {
        try {
            await fetch('/api/node/update', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId: nodeId, enabled: enabled })
            });
            loadNodes();
        } catch (e) {}
    }

    function init() {
        // Tab switching
        document.querySelectorAll('.settings-tab').forEach(tab => {
            tab.addEventListener('click', function () {
                switchTab(this.getAttribute('data-tab'));
            });
        });

        // Server tab
        const saveServerBtn = byId('saveServerPortsBtn');
        if (saveServerBtn) saveServerBtn.addEventListener('click', saveServerPorts);

        // Compatibility tab
        const saveCompatBtn = byId('saveCompatPortsBtn');
        if (saveCompatBtn) saveCompatBtn.addEventListener('click', saveCompatPorts);

        const ollamaToggle = byId('toggleOllamaCompat');
        if (ollamaToggle) ollamaToggle.addEventListener('change', () => { if (!_populating) setCompatService('ollama', ollamaToggle.checked, ollamaToggle); });

        const lmstudioToggle = byId('toggleLmstudioCompat');
        if (lmstudioToggle) lmstudioToggle.addEventListener('change', () => { if (!_populating) setCompatService('lmstudio', lmstudioToggle.checked, lmstudioToggle); });

        const mcpToggle = byId('toggleMcpServer');
        if (mcpToggle) mcpToggle.addEventListener('change', () => { if (!_populating) setMcpServer(mcpToggle.checked); });

        // Security tab
        const saveSecurityBtn = byId('saveSecurityBtn');
        if (saveSecurityBtn) saveSecurityBtn.addEventListener('click', saveSecurity);

        const apiKeyToggle = byId('toggleApiKeyEnabled');
        if (apiKeyToggle) apiKeyToggle.addEventListener('change', updateApiKeyInputState);

        // HTTPS tab
        const saveHttpsBtn = byId('saveHttpsBtn');
        if (saveHttpsBtn) saveHttpsBtn.addEventListener('click', saveHttps);

        const httpsToggle = byId('toggleHttpsEnabled');
        if (httpsToggle) httpsToggle.addEventListener('change', updateHttpsInputState);

        loadHttpsSetupGuide();

        // Logging tab
        const saveLoggingBtn = byId('saveLoggingBtn');
        if (saveLoggingBtn) saveLoggingBtn.addEventListener('click', saveLogging);

        // Download tab
        const saveDownloadBtn = byId('saveDownloadBtn');
        if (saveDownloadBtn) saveDownloadBtn.addEventListener('click', saveDownload);

        // Nodes tab
        const nodesTab = document.querySelector('.settings-tab[data-tab="nodes"]');
        if (nodesTab) nodesTab.addEventListener('click', loadNodes);
    }

    let _initialized = false;
    function load() {
        if (!_initialized) {
            init();
            _initialized = true;
        }
        loadSettings();
        loadNodes();
    }

    document.addEventListener('DOMContentLoaded', function () {
        init();
        _initialized = true;
    });

    window.SettingsPage = { init, load, openNodeForm, saveNodeForm, editNode, removeNode, testNode, toggleNode };
})();
