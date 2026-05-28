let hfHits = [];
let hfGguf = [];
let hfGgufGroups = [];
let hfMmprojGroups = [];
let hfSelected = null;
let hfTreeError = null;
let hfSearchQuery = '';
let hfSearchBase = 'mirror';
let hfNextStartPage = 0;
let hfMaxPagesPerFetch = 1;
let hfLoadingHits = false;
const HF_SEARCH_PAGE_SIZE = 30;
let hfDetailAbort = null;
let hfDetailGgufGroups = [];
let hfDetailMmprojGroups = [];
let hfDetailSelected = null;
let hfDetailGgufCollapsed = true;
let hfDetailReadmeAbort = null;
const HF_DETAIL_GGUF_COLLAPSE_LIMIT = 3;

function t(key, fallback) {
    if (window.I18N && typeof window.I18N.t === 'function') {
        return window.I18N.t(key, fallback);
    }
    return fallback == null ? key : fallback;
}

function tf(key, params, fallback) {
    const template = t(key, fallback);
    if (!params || template == null) return template;
    let out = String(template);
    for (const k of Object.keys(params)) {
        out = out.split(`{${k}}`).join(String(params[k]));
    }
    return out;
}

if (typeof window.showToast !== 'function') {
    window.showToast = function(title, msg, type = 'info') {
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const id = 'toast-' + Date.now();
        const icon = type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle';
        const html = `
            <div class="toast ${type}" id="${id}">
                <div class="toast-icon"><i class="fas ${icon}"></i></div>
                <div class="toast-content"><div class="toast-title">${title}</div><div class="toast-message">${msg}</div></div>
                <button class="toast-close" onclick="document.getElementById('${id}').remove()">&times;</button>
            </div>`;
        container.insertAdjacentHTML('beforeend', html);
        setTimeout(() => { const el = document.getElementById(id); if (el) el.remove(); }, 5000);
    };
}

async function copyToClipboard(text) {
    const value = text == null ? '' : String(text);
    if (!value) return false;
    if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
            await navigator.clipboard.writeText(value);
            return true;
        } catch (e) {
        }
    }
    try {
        const ta = document.createElement('textarea');
        ta.value = value;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
    } catch (e) {
        return false;
    }
}

function formatHfDate(iso) {
    if (typeof iso !== 'string' || !iso.trim()) return '';
    const d = new Date(iso.trim());
    if (isNaN(d.getTime())) return '';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return `${y}-${m}-${day} ${hh}:${mm}:${ss}`;
}

function formatFileSize(size) {
    const n = Number(size);
    if (!isFinite(n) || n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
    return (n / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}

function renderHits() {
    const container = document.getElementById('hfHitsList');
    if (!container) return;
    if (!hfHits || hfHits.length === 0) {
        container.innerHTML = `<div class="empty-state">${t('common.no_results', '未找到结果')}</div>`;
        setHitsFooterVisible(false);
        return;
    }
    container.innerHTML = hfHits.map(hit => {
        const downloads = hit.downloads != null ? `<span class="badge"><i class="fas fa-download"></i> ${hit.downloads}</span>` : '';
        const likes = hit.likes != null ? `<span class="badge"><i class="fas fa-thumbs-up"></i> ${hit.likes}</span>` : '';
        const params = hit.parameters ? `<span class="badge"><i class="fas fa-sliders-h"></i> ${hit.parameters}</span>` : '';
        const tag = hit.pipelineTag ? `<span class="badge"><i class="fas fa-tag"></i> ${hit.pipelineTag}</span>` : '';
        const modDate = formatHfDate(hit.lastModified);
        const mod = modDate ? `<span class="badge"><i class="fas fa-clock"></i> ${modDate}</span>` : '';
        return `
            <div class="list-item">
                <div>
                    <div class="list-item-title mono" onclick="showDetail('${escapeHtmlAttr(hit.repoId)}')">${escapeHtml(hit.repoId)}</div>
                    <div class="list-item-meta">
                        ${downloads}
                        ${likes}
                        ${params}
                        ${tag}
                        ${mod}
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

function setHitsFooterVisible(visible) {
    const footer = document.getElementById('hfHitsFooter');
    if (!footer) return;
    footer.style.display = visible ? '' : 'none';
}

function setLoadMoreState(enabled, text) {
    const btn = document.getElementById('hfLoadMoreBtn');
    if (!btn) return;
    btn.disabled = !enabled;
    btn.textContent = text || t('common.load_more', '加载更多');
}

function setLoadMoreVisible(visible) {
    const btn = document.getElementById('hfLoadMoreBtn');
    if (!btn) return;
    btn.style.display = visible ? '' : 'none';
}

function mergeHits(existing, incoming) {
    const list1 = Array.isArray(existing) ? existing : [];
    const list2 = Array.isArray(incoming) ? incoming : [];
    const seen = new Set();
    const out = [];
    for (const h of list1) {
        const id = h && h.repoId != null ? String(h.repoId) : '';
        if (!id || seen.has(id)) continue;
        seen.add(id);
        out.push(h);
    }
    for (const h of list2) {
        const id = h && h.repoId != null ? String(h.repoId) : '';
        if (!id || seen.has(id)) continue;
        seen.add(id);
        out.push(h);
    }
    return out;
}

async function fetchHitsPage(query, base, limit, startPage, maxPages) {
    const url = `/api/hf/search?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`
        + `&startPage=${encodeURIComponent(String(startPage))}&maxPages=${encodeURIComponent(String(maxPages))}`
        + `&base=${encodeURIComponent(base)}`;
    const resp = await fetch(url);
    const data = await resp.json();
    if (!data || data.success !== true) {
        throw new Error((data && data.error) ? data.error : t('hf.search.failed_detail', '搜索失败：hf-mirror.com存在访问频率限制，如果搜索失败，请稍等片刻重试；huggingface.co国内地区无法访问，需要科学上网。'));
    }
    const hits = data.data && data.data.hits ? data.data.hits : [];
    return Array.isArray(hits) ? hits : [];
}

function renderGguf() {
    const container = document.getElementById('hfGgufList');
    if (!container) return;
    if (!hfSelected) {
        container.innerHTML = `<div class="empty-state">${t('hf.gguf.select_repo', '选择一个模型以列出 GGUF 文件')}</div>`;
        return;
    }
    if (!hfGgufGroups || hfGgufGroups.length === 0) {
        container.innerHTML = `<div class="empty-state">${t('hf.gguf.not_found', '未找到 GGUF 文件')}</div>`;
        return;
    }
    container.innerHTML = hfGgufGroups.map((group, idx) => {
        const sizeText = group.totalSize != null ? formatFileSize(group.totalSize) : '';
        const sizeBadge = sizeText ? `<span class="badge"><i class="fas fa-hdd"></i> ${sizeText}</span>` : '';
        const lfsBadge = group.hasLfs ? `<span class="badge"><i class="fas fa-database"></i> LFS</span>` : '';
        const shardBadge = group.isSplit
            ? `<span class="badge"><i class="fas fa-th-large"></i> ${tf('hf.gguf.shard', { count: group.partCount, total: group.partTotal }, '分片 {count}/{total}')}</span>`
            : '';
        return `
            <div class="list-item">
                <div class="gguf-info">
                    <div class="file-path mono" onclick="copyGgufGroupLinks(${idx})">${escapeHtml(group.displayPath || '')}</div>
                    <div class="list-item-meta">
                        ${sizeBadge}${lfsBadge}${shardBadge}
                    </div>
                </div>
                <div class="file-actions">
                    <button class="btn btn-secondary btn-sm" onclick="copyGgufGroupLinks(${idx})">
                        <i class="fas fa-copy"></i> ${t('hf.gguf.copy_links', '复制链接')}
                    </button>
                    <button class="btn btn-primary btn-sm" onclick="downloadModel(${idx})">
                        <i class="fas fa-download"></i> ${t('hf.gguf.create_download', '创建下载')}
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function getSplitInfoFromPath(path) {
    const p = path == null ? '' : String(path);
    const m = p.match(/-(\d{5})-?of-(\d{5})\.gguf$/i);
    if (!m) return null;
    const partIndex = Number(m[1]);
    const partTotal = Number(m[2]);
    if (!isFinite(partIndex) || !isFinite(partTotal)) return null;
    const key = p.replace(/-(\d{5})-?of-(\d{5})\.gguf$/i, '');
    const displayPath = key + '.gguf';
    return { key, displayPath, partIndex, partTotal };
}

function groupGgufFiles(files) {
    const list = Array.isArray(files) ? files : [];
    const groups = new Map();
    const singles = [];
    for (const f of list) {
        const path = f && f.path != null ? String(f.path) : '';
        const info = getSplitInfoFromPath(path);
        if (!info) {
            singles.push({
                isSplit: false,
                key: path,
                displayPath: path,
                files: [f],
                partCount: 1,
                partTotal: 1
            });
            continue;
        }
        const existing = groups.get(info.key);
        if (!existing) {
            groups.set(info.key, {
                isSplit: true,
                key: info.key,
                displayPath: info.displayPath,
                files: [{ file: f, partIndex: info.partIndex }],
                partTotal: info.partTotal
            });
        } else {
            existing.files.push({ file: f, partIndex: info.partIndex });
            if (isFinite(info.partTotal) && info.partTotal > existing.partTotal) existing.partTotal = info.partTotal;
        }
    }

    const merged = [];
    for (const g of groups.values()) {
        g.files.sort((a, b) => (a.partIndex || 0) - (b.partIndex || 0));
        const orderedFiles = g.files.map(x => x.file);
        merged.push({
            isSplit: true,
            key: g.key,
            displayPath: g.displayPath,
            files: orderedFiles,
            partCount: orderedFiles.length,
            partTotal: g.partTotal || orderedFiles.length
        });
    }

    const all = singles.concat(merged).map(g => {
        let totalSize = null;
        let hasAnySize = false;
        let hasLfs = false;
        for (const f of g.files) {
            if (!f) continue;
            if (f.lfsOid) hasLfs = true;
            const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
            if (isFinite(s) && s > 0) {
                totalSize = (totalSize || 0) + s;
                hasAnySize = true;
            }
        }
        return {
            ...g,
            totalSize: hasAnySize ? totalSize : null,
            hasLfs
        };
    });

    all.sort((a, b) => String(a.displayPath || '').localeCompare(String(b.displayPath || ''), 'zh-CN'));
    return all;
}

async function copyGgufGroupLinks(groupIndex) {
    return copyGgufGroupLinksFrom(hfGgufGroups, groupIndex);
}

async function copyDetailGgufGroupLinks(groupIndex) {
    return copyGgufGroupLinksFrom(hfDetailGgufGroups, groupIndex);
}

async function copyGgufGroupLinksFrom(groups, groupIndex) {
    const g = groups && groups[groupIndex] ? groups[groupIndex] : null;
    if (!g) return;
    const links = (g.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl) : '').filter(Boolean);
    if (!links.length) {
        showToast(t('toast.info', '提示'), t('hf.toast.no_links_to_copy', '没有可复制的下载链接'), 'info');
        return;
    }
    const ok = await copyToClipboard(links.join('\n'));
    if (ok) showToast(t('toast.success', '成功'), tf('hf.toast.copied_links', { count: links.length }, '已复制 {count} 条链接'), 'success');
    else showToast(t('toast.error', '错误'), t('common.clipboard_write_failed', '无法写入剪贴板'), 'error');
}

function escapeHtml(str) {
    const s = str == null ? '' : String(str);
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function escapeHtmlAttr(str) {
    return escapeHtml(str).replace(/`/g, '&#96;');
}

function getFileNameFromPath(path) {
    const p = path == null ? '' : String(path);
    const idx = p.lastIndexOf('/');
    const name = idx >= 0 ? p.substring(idx + 1) : p;
    return name.trim();
}

function isMmprojFilePath(path) {
    const name = getFileNameFromPath(path);
    if (!name) return false;
    const lower = name.toLowerCase();
    return lower.endsWith('.gguf') && lower.includes('mmproj');
}

function isMmprojGroup(group) {
    if (!group) return false;
    const p = (group.displayPath || group.key || '');
    return isMmprojFilePath(p);
}

function getGroupTotalSize(group) {
    if (!group) return 0;
    const n = group.totalSize != null ? Number(group.totalSize) : NaN;
    if (isFinite(n) && n > 0) return n;
    let total = 0;
    for (const f of (group.files || [])) {
        if (!f) continue;
        const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
        if (isFinite(s) && s > 0) total += s;
    }
    return total;
}

function pickBestMmprojGroup(groups) {
    const list = Array.isArray(groups) ? groups : [];
    let best = null;
    let bestSize = -1;
    for (const g of list) {
        if (!isMmprojGroup(g)) continue;
        const size = getGroupTotalSize(g);
        if (size > bestSize) {
            best = g;
            bestSize = size;
        }
    }
    return best;
}

function parseRepoId(repoId) {
    const s = repoId == null ? '' : String(repoId).trim();
    const idx = s.indexOf('/');
    if (idx <= 0 || idx === s.length - 1) return null;
    return { author: s.substring(0, idx), modelId: s.substring(idx + 1) };
}

async function downloadModel(groupIndex) {
    return createHfDownloadTasks(hfSelected, hfGgufGroups, hfMmprojGroups, groupIndex);
}

async function downloadDetailModel(groupIndex) {
    return createHfDownloadTasks(hfDetailSelected, hfDetailGgufGroups, hfDetailMmprojGroups, groupIndex);
}

async function createHfDownloadTasks(repoId, ggufGroups, mmprojGroups, groupIndex) {
    const g = ggufGroups && ggufGroups[groupIndex] ? ggufGroups[groupIndex] : null;
    if (!g) return;
    const repo = parseRepoId(repoId);
    if (!repo) {
        showToast(t('toast.error', '错误'), t('hf.error.repo_id_invalid', 'RepoId 无效，无法解析 author/modelId'), 'error');
        return;
    }
    const downloadUrl = (g.files || [])
        .map(f => f && f.downloadUrl ? String(f.downloadUrl).trim() : '')
        .filter(Boolean);
    if (!downloadUrl.length) {
        showToast(t('toast.info', '提示'), t('hf.error.empty_download_urls', '下载链接为空'), 'info');
        return;
    }
    const bestMmproj = pickBestMmprojGroup(mmprojGroups);
    if (bestMmproj && bestMmproj.files && bestMmproj.files.length) {
        const mmprojUrls = (bestMmproj.files || [])
            .map(f => f && f.downloadUrl ? String(f.downloadUrl).trim() : '')
            .filter(Boolean);
        if (mmprojUrls.length) {
            const merged = new Set(downloadUrl);
            for (const u of mmprojUrls) merged.add(u);
            downloadUrl.length = 0;
            downloadUrl.push(...merged);
        }
    }
    const ggufPath = (g.displayPath || '') || ((g.files && g.files[0] && g.files[0].path != null) ? String(g.files[0].path) : '') || (g.key || '');
    const fileName = getFileNameFromPath(ggufPath || (g.displayPath || g.key || ''));
    const payload = { author: repo.author, modelId: repo.modelId, downloadUrl };
    if (fileName) payload.name = fileName;
    if (ggufPath) payload.path = ggufPath;

    try {
        const resp = await fetch('/api/downloads/model/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!resp.ok) {
            const text = await resp.text();
            throw new Error(text || `请求失败(${resp.status})`);
        }
        const data = await resp.json();
        if (!data || data.success !== true) {
            throw new Error((data && data.error) ? data.error : t('download.toast.create_failed', '创建下载任务失败'));
        }
        const count = Array.isArray(data.tasks) ? data.tasks.length : downloadUrl.length;
        const withMmproj = bestMmproj && bestMmproj.files && bestMmproj.files.length;
        showToast(
            t('toast.success', '成功'),
            withMmproj
                ? tf('hf.toast.tasks_created_with_mmproj', { count }, '已创建 {count} 个下载任务（已自动包含 mmproj）')
                : tf('hf.toast.tasks_created', { count }, '已创建 {count} 个下载任务'),
            'success'
        );
        if (window.DownloadManager && typeof window.DownloadManager.refreshDownloads === 'function') {
            window.DownloadManager.refreshDownloads();
        }
    } catch (e) {
        showToast(t('toast.error', '错误'), e && e.message ? e.message : t('common.network_request_failed', '网络请求失败'), 'error');
    }
}

async function copyLink(url) {
    const ok = await copyToClipboard(url);
    if (ok) showToast(t('toast.success', '成功'), t('common.link_copied_to_clipboard', '下载链接已复制到剪贴板'), 'success');
    else showToast(t('toast.error', '错误'), t('common.clipboard_write_failed', '无法写入剪贴板'), 'error');
}

async function copyAllGgufLinks() {
    if (!hfGguf || hfGguf.length === 0) {
        showToast(t('toast.info', '提示'), t('hf.toast.no_links_in_list', '当前没有可复制的链接'), 'info');
        return;
    }
    const text = hfGguf
        .filter(f => f && !isMmprojFilePath(f.path))
        .map(f => f && f.downloadUrl ? String(f.downloadUrl) : '')
        .filter(Boolean)
        .join('\n');
    const ok = await copyToClipboard(text);
    if (ok) showToast(t('toast.success', '成功'), tf('hf.toast.copied_links', { count: text ? text.split('\n').length : 0 }, '已复制 {count} 条链接'), 'success');
    else showToast(t('toast.error', '错误'), t('common.clipboard_write_failed', '无法写入剪贴板'), 'error');
}

async function hfSearch() {
    const input = document.getElementById('hfSearchInput');
    const limitEl = document.getElementById('hfLimit');
    const baseEl = document.getElementById('hfBaseSelect');
    const query = input ? String(input.value || '').trim() : '';
    if (!query) {
        showToast(t('toast.info', '提示'), t('hf.search.keyword_required', '请输入搜索关键字'), 'info');
        return;
    }
    const limit = limitEl ? String(limitEl.value || '30') : '30';
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    hfSearchQuery = query;
    hfSearchBase = base;
    const n = Number(limit);
    const safeLimit = isFinite(n) && n > 0 ? Math.min(200, Math.floor(n)) : 30;
    hfMaxPagesPerFetch = Math.max(1, Math.ceil(safeLimit / HF_SEARCH_PAGE_SIZE));
    hfNextStartPage = 0;
    hfHits = [];
    document.getElementById('hfHitsList').innerHTML = `<div class="empty-state">${t('hf.search.searching', '正在搜索...')}</div>`;
    setHitsFooterVisible(false);
    setLoadMoreVisible(false);
    try {
        hfLoadingHits = true;
        setLoadMoreState(false, t('common.loading', '加载中...'));
        const newHits = await fetchHitsPage(hfSearchQuery, hfSearchBase, safeLimit, hfNextStartPage, hfMaxPagesPerFetch);
        hfHits = mergeHits(hfHits, newHits);
        hfNextStartPage += hfMaxPagesPerFetch;
        renderHits();
        if (newHits.length < safeLimit) {
            setHitsFooterVisible(false);
        } else {
            setLoadMoreVisible(true);
            setHitsFooterVisible(true);
            setLoadMoreState(true, t('common.load_more', '加载更多'));
        }
    } catch (e) {
        hfHits = [];
        document.getElementById('hfHitsList').innerHTML = `<div class="empty-state">${t('hf.search.failed', '搜索失败')}</div>`;
        setHitsFooterVisible(false);
        showToast(t('toast.error', '错误'), e && e.message ? e.message : t('common.network_request_failed', '网络请求失败'), 'error');
    } finally {
        hfLoadingHits = false;
    }
}

async function hfLoadMore() {
    if (hfLoadingHits) return;
    const query = hfSearchQuery == null ? '' : String(hfSearchQuery).trim();
    if (!query) return;
    const limitEl = document.getElementById('hfLimit');
    const n = Number(limitEl ? String(limitEl.value || '30') : '30');
    const safeLimit = isFinite(n) && n > 0 ? Math.min(200, Math.floor(n)) : 30;
    hfMaxPagesPerFetch = Math.max(1, Math.ceil(safeLimit / HF_SEARCH_PAGE_SIZE));

    setHitsFooterVisible(true);
    setLoadMoreVisible(true);
    setLoadMoreState(false, t('common.loading', '加载中...'));
    try {
        hfLoadingHits = true;
        const before = hfHits.length;
        const newHits = await fetchHitsPage(query, hfSearchBase, safeLimit, hfNextStartPage, hfMaxPagesPerFetch);
        hfHits = mergeHits(hfHits, newHits);
        hfNextStartPage += hfMaxPagesPerFetch;
        renderHits();
        const added = hfHits.length - before;
        if (added <= 0 || newHits.length < safeLimit) {
            setHitsFooterVisible(false);
            if (added <= 0) {
                showToast(t('toast.info', '提示'), t('hf.search.no_more', '没有更多结果了'), 'info');
            }
            return;
        }
        setLoadMoreVisible(true);
        setHitsFooterVisible(true);
        setLoadMoreState(true, t('common.load_more', '加载更多'));
    } catch (e) {
        setLoadMoreVisible(true);
        setHitsFooterVisible(true);
        setLoadMoreState(true, t('common.load_more', '加载更多'));
        showToast(t('toast.error', '错误'), e && e.message ? e.message : t('common.network_request_failed', '网络请求失败'), 'error');
    } finally {
        hfLoadingHits = false;
    }
}

function showDetail(repoId) {
    const id = repoId == null ? '' : String(repoId).trim();
    if (!id) return;
    const hit = (hfHits || []).find(h => h && String(h.repoId || '') === id);
    if (!hit) return;
    cancelDetailGguf();
    hfDetailSelected = id;

    document.getElementById('hfDetailEmpty').style.display = 'none';
    const content = document.getElementById('hfDetailContent');
    content.style.display = 'block';

    document.getElementById('hfDetailName').textContent = id;
    const modelHomeLink = document.getElementById('hfDetailModelHomeLink');
    if (modelHomeLink) {
        const modelUrl = hit.modelUrl ? String(hit.modelUrl).trim() : '';
        if (modelUrl) {
            modelHomeLink.href = modelUrl;
            modelHomeLink.style.display = '';
        } else {
            modelHomeLink.style.display = 'none';
            modelHomeLink.removeAttribute('href');
        }
    }

    const parts = [];
    if (hit.downloads != null) parts.push(`<span><i class="fas fa-download"></i> ${hit.downloads}</span>`);
    if (hit.likes != null) parts.push(`<span><i class="fas fa-thumbs-up"></i> ${hit.likes}</span>`);
    const detailDate = formatHfDate(hit.lastModified);
    if (detailDate) parts.push(`<span><i class="fas fa-clock"></i> ${detailDate}</span>`);
    if (hit.pipelineTag) parts.push(`<span><i class="fas fa-tag"></i> ${hit.pipelineTag}</span>`);
    if (hit.parameters) parts.push(`<span><i class="fas fa-sliders-h"></i> ${hit.parameters}</span>`);
    document.getElementById('hfDetailStats').innerHTML = parts.join('');

    resetDetailReadmePlaceholder(`<div class="hf-detail-placeholder"><i class="fas fa-spinner fa-spin"></i> ${t('common.loading', '加载中...')}</div>`);
    fetchDetailGguf(id);
    fetchDetailReadme(id);
}

function cancelDetailGguf() {
    if (hfDetailAbort) {
        hfDetailAbort.abort();
        hfDetailAbort = null;
    }
    if (hfDetailReadmeAbort) {
        hfDetailReadmeAbort.abort();
        hfDetailReadmeAbort = null;
    }
    hfDetailGgufGroups = [];
    hfDetailMmprojGroups = [];
    hfDetailSelected = null;
    hfDetailGgufCollapsed = true;
    updateDetailGgufToolbar();
    resetDetailReadmePlaceholder(`<div class="hf-detail-placeholder">${t('page.hf.detail_readme_placeholder', '留空占位 — README 解析')}</div>`);
}
window.cancelDetailGguf = cancelDetailGguf;

function resetDetailReadmePlaceholder(html) {
    const body = document.getElementById('hfDetailReadmeBody');
    if (body) body.innerHTML = html;
    const link = document.getElementById('hfDetailReadmeLink');
    if (link) {
        link.style.display = 'none';
        link.removeAttribute('href');
    }
}

function renderDetailReadmeMarkdown(markdown, readmeUrl) {
    const body = document.getElementById('hfDetailReadmeBody');
    if (!body) return;
    const input = markdown == null ? '' : String(markdown);
    if (!input.trim()) {
        body.innerHTML = `<div class="hf-detail-placeholder">${t('hf.readme.empty', 'README 为空')}</div>`;
        return;
    }
    if (window.marked && typeof window.marked.parse === 'function') {
        try {
            const rawHtml = window.marked.parse(input, {
                gfm: true,
                breaks: false,
                mangle: false,
                headerIds: false
            });
            const safeHtml = sanitizeReadmeHtml(rawHtml);
            body.innerHTML = `<div class="hf-detail-markdown">${safeHtml}</div>`;
            rewriteDetailReadmeLinks(body, readmeUrl);
            highlightDetailReadmeCode(body);
            return;
        } catch (e) {
        }
    }
    body.innerHTML = `<pre class="hf-detail-readme-plain">${escapeHtml(input)}</pre>`;
}

function sanitizeReadmeHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = html == null ? '' : String(html);
    const blockedTags = ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'meta', 'link', 'base'];
    blockedTags.forEach(tag => {
        template.content.querySelectorAll(tag).forEach(node => node.remove());
    });
    const allNodes = template.content.querySelectorAll('*');
    allNodes.forEach(node => {
        Array.from(node.attributes).forEach(attr => {
            const name = String(attr.name || '').toLowerCase();
            const value = String(attr.value || '').trim();
            if (name.startsWith('on')) {
                node.removeAttribute(attr.name);
                return;
            }
            if (name === 'srcdoc') {
                node.removeAttribute(attr.name);
                return;
            }
            if ((name === 'href' || name === 'src' || name === 'xlink:href') && !isSafeReadmeUrl(value, node.tagName)) {
                node.removeAttribute(attr.name);
                return;
            }
            if (name === 'style' && /expression\s*\(|url\s*\(\s*['"]?\s*javascript:/i.test(value)) {
                node.removeAttribute(attr.name);
            }
        });
    });
    return template.innerHTML;
}

function isSafeReadmeUrl(rawUrl, tagName) {
    const value = rawUrl == null ? '' : String(rawUrl).trim();
    if (!value || value.startsWith('#') || value.startsWith('/') || value.startsWith('./') || value.startsWith('../')) {
        return true;
    }
    const lower = value.toLowerCase();
    if (lower.startsWith('javascript:') || lower.startsWith('vbscript:')) {
        return false;
    }
    if (lower.startsWith('data:')) {
        return String(tagName || '').toUpperCase() === 'IMG' && /^data:image\//i.test(value);
    }
    return /^(https?:|mailto:|tel:)/i.test(value);
}

function highlightDetailReadmeCode(container) {
    if (!container || !window.hljs || typeof window.hljs.highlightElement !== 'function') return;
    const blocks = container.querySelectorAll('pre code');
    blocks.forEach(block => window.hljs.highlightElement(block));
}

function rewriteDetailReadmeLinks(container, readmeUrl) {
    if (!container || !readmeUrl) return;
    const nodes = container.querySelectorAll('a[href], img[src]');
    nodes.forEach(node => {
        const attr = node.tagName === 'IMG' ? 'src' : 'href';
        const raw = node.getAttribute(attr);
        if (!raw || raw.startsWith('#') || raw.startsWith('data:') || raw.startsWith('javascript:')) return;
        try {
            node.setAttribute(attr, new URL(raw, readmeUrl).toString());
        } catch (e) {
        }
        if (node.tagName === 'A') {
            node.setAttribute('target', '_blank');
            node.setAttribute('rel', 'noopener noreferrer');
        }
    });
}

async function fetchDetailReadme(repoId) {
    const selected = repoId == null ? '' : String(repoId).trim();
    if (!selected) return;
    if (hfDetailReadmeAbort) hfDetailReadmeAbort.abort();
    const controller = new AbortController();
    hfDetailReadmeAbort = controller;
    const baseEl = document.getElementById('hfBaseSelect');
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    resetDetailReadmePlaceholder(`<div class="hf-detail-placeholder"><i class="fas fa-spinner fa-spin"></i> ${t('common.loading', '加载中...')}</div>`);
    try {
        const resp = await fetch(`/api/hf/readme?model=${encodeURIComponent(selected)}&base=${encodeURIComponent(base)}`, {
            signal: controller.signal
        });
        const data = await resp.json();
        if (hfDetailSelected !== selected) return;
        if (!data || data.success !== true || !data.data) {
            throw new Error((data && data.error) ? data.error : t('hf.readme.failed', 'README 加载失败'));
        }
        renderDetailReadmeMarkdown(data.data.markdown, data.data.readmeUrl);
        const link = document.getElementById('hfDetailReadmeLink');
        if (link && data.data.readmeUrl) {
            link.href = data.data.readmeUrl;
            link.style.display = '';
        }
    } catch (e) {
        if (e && e.name === 'AbortError') return;
        if (hfDetailSelected !== selected) return;
        resetDetailReadmePlaceholder(`<div class="hf-detail-placeholder">${escapeHtml((e && e.message) ? e.message : t('hf.readme.failed', 'README 加载失败'))}</div>`);
    } finally {
        if (hfDetailReadmeAbort === controller) hfDetailReadmeAbort = null;
    }
}

function updateDetailGgufToolbar() {
    const summaryEl = document.getElementById('hfDetailGgufSummary');
    const toggleBtn = document.getElementById('hfDetailGgufToggleBtn');
    const toggleText = document.getElementById('hfDetailGgufToggleText');
    const toggleIcon = toggleBtn ? toggleBtn.querySelector('i') : null;
    const total = Array.isArray(hfDetailGgufGroups) ? hfDetailGgufGroups.length : 0;
    const visible = hfDetailGgufCollapsed ? Math.min(total, HF_DETAIL_GGUF_COLLAPSE_LIMIT) : total;
    const mmprojCount = Array.isArray(hfDetailMmprojGroups) ? hfDetailMmprojGroups.length : 0;
    if (summaryEl) {
        if (total <= 0) {
            summaryEl.textContent = '';
        } else if (mmprojCount > 0) {
            summaryEl.textContent = tf(
                'hf.detail.gguf_summary_with_mmproj',
                { visible, total, mmproj: mmprojCount },
                '显示 ' + visible + '/' + total + ' 个 GGUF，下载时自动附带 ' + mmprojCount + ' 个 mmproj'
            );
        } else {
            summaryEl.textContent = tf(
                'hf.detail.gguf_summary',
                { visible, total },
                '显示 ' + visible + '/' + total + ' 个 GGUF'
            );
        }
    }
    if (toggleBtn) {
        toggleBtn.style.display = total > HF_DETAIL_GGUF_COLLAPSE_LIMIT ? '' : 'none';
    }
    if (toggleText) {
        toggleText.textContent = hfDetailGgufCollapsed ? t('common.expand', '展开') : t('common.collapse', '收起');
    }
    if (toggleIcon) {
        toggleIcon.className = 'fas ' + (hfDetailGgufCollapsed ? 'fa-chevron-down' : 'fa-chevron-up');
    }
}

function toggleDetailGgufCollapse() {
    if (!hfDetailGgufGroups || hfDetailGgufGroups.length <= HF_DETAIL_GGUF_COLLAPSE_LIMIT) return;
    hfDetailGgufCollapsed = !hfDetailGgufCollapsed;
    renderDetailGguf();
}

function renderDetailGguf() {
    const container = document.getElementById('hfDetailGgufList');
    if (!container) return;
    if (!hfDetailGgufGroups || hfDetailGgufGroups.length === 0) {
        updateDetailGgufToolbar();
        container.innerHTML = `<div class="hf-detail-placeholder">${t('hf.gguf.not_found', '未找到 GGUF 文件')}</div>`;
        return;
    }
    const indexedGroups = hfDetailGgufGroups.map((g, index) => ({ g, index }));
    const visibleGroups = hfDetailGgufCollapsed
        ? indexedGroups.slice(0, HF_DETAIL_GGUF_COLLAPSE_LIMIT)
        : indexedGroups;
    updateDetailGgufToolbar();
    container.innerHTML = visibleGroups.map(({ g, index }) => {
        const sizeText = g.totalSize != null ? formatFileSize(g.totalSize) : '';
        const sizeBadge = sizeText ? `<span class="hf-detail-gguf-badge"><i class="fas fa-hdd"></i> ${sizeText}</span>` : '';
        const lfsBadge = g.hasLfs ? `<span class="hf-detail-gguf-badge"><i class="fas fa-database"></i> LFS</span>` : '';
        const shardBadge = g.isSplit
            ? `<span class="hf-detail-gguf-badge"><i class="fas fa-th-large"></i> ${tf('hf.gguf.shard', { count: g.partCount, total: g.partTotal }, '分片 {count}/{total}')}</span>`
            : '';
        return `
            <div class="hf-detail-gguf-item">
                <div class="hf-detail-gguf-main">
                    <span class="hf-detail-gguf-name">${escapeHtml(g.displayPath || '')}</span>
                    <span class="hf-detail-gguf-meta">${sizeBadge}${lfsBadge}${shardBadge}</span>
                </div>
                <div class="hf-detail-gguf-actions">
                    <button class="btn btn-secondary btn-sm" type="button" onclick="copyDetailGgufGroupLinks(${index})">
                        <i class="fas fa-copy"></i> ${t('hf.gguf.copy_links', '复制链接')}
                    </button>
                    <button class="btn btn-primary btn-sm" type="button" onclick="downloadDetailModel(${index})">
                        <i class="fas fa-download"></i> ${t('hf.gguf.create_download', '创建下载')}
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

async function fetchDetailGguf(repoId) {
    cancelDetailGguf();
    hfDetailSelected = repoId == null ? null : String(repoId).trim();
    const container = document.getElementById('hfDetailGgufList');
    if (container) {
        container.innerHTML = `<div class="hf-detail-placeholder"><i class="fas fa-spinner fa-spin"></i> ${t('hf.gguf.parsing', '正在解析 GGUF 文件...')}</div>`;
    }
    updateDetailGgufToolbar();

    const baseEl = document.getElementById('hfBaseSelect');
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    const controller = new AbortController();
    hfDetailAbort = controller;

    try {
        const resp = await fetch(`/api/hf/gguf?model=${encodeURIComponent(repoId)}&base=${encodeURIComponent(base)}`, {
            signal: controller.signal
        });
        const data = await resp.json();
        if (!data || data.success !== true) {
            throw new Error((data && data.error) ? data.error : t('hf.gguf.parse_failed', '解析失败'));
        }
        if (controller.signal.aborted) return;
        const result = data.data || {};
        const ggufFiles = result.ggufFiles || [];
        const allGroups = groupGgufFiles(ggufFiles);
        hfDetailMmprojGroups = allGroups.filter(isMmprojGroup);
        hfDetailGgufGroups = allGroups.filter(g => !isMmprojGroup(g));
        hfDetailGgufCollapsed = hfDetailGgufGroups.length > HF_DETAIL_GGUF_COLLAPSE_LIMIT;
        if (controller.signal.aborted) return;
        renderDetailGguf();
    } catch (e) {
        if (controller.signal.aborted) return;
        hfDetailGgufGroups = [];
        hfDetailMmprojGroups = [];
        hfDetailGgufCollapsed = true;
        updateDetailGgufToolbar();
        if (container) {
            container.innerHTML = `<div class="hf-detail-placeholder">${t('hf.gguf.parse_failed', '解析失败')}</div>`;
        }
    } finally {
        if (hfDetailAbort === controller) hfDetailAbort = null;
    }
}

async function selectRepo(repoId) {
    const baseEl = document.getElementById('hfBaseSelect');
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    const id = repoId == null ? '' : String(repoId).trim();
    if (!id) return;
    hfSelected = id;
    hfGguf = [];
    hfTreeError = null;
    const repoLabel = document.getElementById('hfGgufModalRepo');
    if (repoLabel) repoLabel.textContent = hfSelected;
    document.getElementById('hfGgufList').innerHTML = `<div class="empty-state">${t('hf.gguf.parsing', '正在解析 GGUF 文件...')}</div>`;
    try {
        const resp = await fetch(`/api/hf/gguf?model=${encodeURIComponent(hfSelected)}&base=${encodeURIComponent(base)}`);
        const data = await resp.json();
        if (!data || data.success !== true) {
            throw new Error((data && data.error) ? data.error : t('hf.gguf.parse_failed', '解析失败'));
        }
        const result = data.data || {};
        hfTreeError = result.treeError || null;
        hfGguf = result.ggufFiles || [];
        const allGroups = groupGgufFiles(hfGguf);
        hfMmprojGroups = allGroups.filter(isMmprojGroup);
        hfGgufGroups = allGroups.filter(g => !isMmprojGroup(g));
        renderGguf();
        if (hfTreeError) showToast(t('toast.info', '提示'), hfTreeError, 'info');
    } catch (e) {
        hfGguf = [];
        hfGgufGroups = [];
        hfMmprojGroups = [];
        document.getElementById('hfGgufList').innerHTML = `<div class="empty-state">${t('hf.gguf.parse_failed', '解析失败')}</div>`;
        showToast(t('toast.error', '错误'), e && e.message ? e.message : t('common.network_request_failed', '网络请求失败'), 'error');
    }
}

if (typeof window.openModal !== 'function') {
    window.openModal = function(id) {
        const el = document.getElementById(id);
        if (el) el.classList.add('show');
    };
}

if (typeof window.closeModal !== 'function') {
    window.closeModal = function(id) {
        const el = document.getElementById(id);
        if (el) el.classList.remove('show');
    };
}

function selectRepoAndOpen(repoId) {
    openModal('hfGgufModal');
    selectRepo(repoId);
}

if (typeof window.shutdownService !== 'function') {
    window.shutdownService = function() {
        if (!confirm(t('confirm.shutdown', '确定要停止服务吗？'))) return;
        fetch('/api/shutdown', { method: 'POST' })
            .then(r => r.json())
            .then(data => {
                if (data && data.success) {
                    showToast(t('toast.success', '成功'), t('page.shutdown.stopping', '服务正在停止'), 'success');
                } else {
                    showToast(t('toast.error', '错误'), (data && data.error) ? data.error : t('page.shutdown.failed', '停止服务失败'), 'error');
                }
            })
            .catch(() => showToast(t('toast.error', '错误'), t('common.network_request_failed', '网络请求失败'), 'error'));
    };
}

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('hfSearchInput');
    const baseEl = document.getElementById('hfBaseSelect');
    if (input) {
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                hfSearch();
            }
        });
    }
    const params = new URLSearchParams(window.location.search || '');
    const q = params.get('q');
    const base = params.get('base');
    if (q) {
        input.value = q;
    }
    if (baseEl && base) {
        const b = String(base).trim().toLowerCase();
        if (b === 'official' || b === 'huggingface' || b === 'huggingface.co') baseEl.value = 'official';
        if (b === 'mirror' || b === 'hf-mirror' || b === 'hf-mirror.com') baseEl.value = 'mirror';
    }
    if (q) {
        hfSearch();
    }
});

window.addEventListener('click', (e) => {
    const t = e && e.target ? e.target : null;
    if (t && t.classList && t.classList.contains('modal')) t.classList.remove('show');
});

document.addEventListener('keydown', (e) => {
    if (e && e.key === 'Escape') closeModal('hfGgufModal');
});
