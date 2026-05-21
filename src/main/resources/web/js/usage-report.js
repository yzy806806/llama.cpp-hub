(function () {
    const PAGE_SIZE = 50;

    let tokenSummaryData = [];
    let requestLogsData = [];
    let dailyTokenData = [];
    let filteredLogs = [];
    let currentPage = 1;

    // Tooltip state for each chart
    let tokenBars = [];
    let dailyBars = [];
    let tokenHoverIdx = -1;
    let dailyHoverIdx = -1;

    function t(key, fallback) {
        if (typeof window.t === 'function') return window.t(key, fallback);
        return fallback || key;
    }

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    async function load() {
        await Promise.all([fetchTokenSummary(), fetchRequestLogs(), fetchDailyTokens()]);
    }

    async function fetchTokenSummary() {
        try {
            const resp = await fetch('/api/report/token-summary');
            const result = await resp.json();
            if (!result || !result.success) {
                tokenSummaryData = [];
            } else {
                tokenSummaryData = result.data || [];
            }
        } catch (e) {
            tokenSummaryData = [];
        }
        renderTokenSummary();
    }

    async function fetchDailyTokens() {
        try {
            const resp = await fetch('/api/report/daily-tokens');
            const result = await resp.json();
            if (!result || !result.success) {
                dailyTokenData = [];
            } else {
                dailyTokenData = result.data || [];
            }
        } catch (e) {
            dailyTokenData = [];
        }
        renderDailyChart();
    }

    async function fetchRequestLogs() {
        try {
            const resp = await fetch('/api/report/request-logs');
            const result = await resp.json();
            if (!result || !result.success) {
                requestLogsData = [];
            } else {
                requestLogsData = result.data || [];
            }
        } catch (e) {
            requestLogsData = [];
        }
        buildModelFilter();
        applyFilter();
    }

    // --- Shared tooltip draw helper ---
    function drawBarsChart(ctx, w, h, pad, cw, ch, bars, maxV, colors, tc, bc, hoverIdx, labelFn, valFn) {
        const barW = bars[0] ? bars[0].barW : 0;
        const gap = bars[0] ? bars[0].gap : 0;
        const gw = bars[0] ? bars[0].gw : 0;
        const count = bars.length;

        ctx.clearRect(0, 0, w, h);

        // axis
        ctx.strokeStyle = bc; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(pad.left, pad.top); ctx.lineTo(pad.left, pad.top + ch); ctx.lineTo(pad.left + cw, pad.top + ch); ctx.stroke();

        // y labels
        ctx.fillStyle = tc; ctx.font = '10px sans-serif'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
        for (let i = 0; i <= 4; i++) {
            const val = Math.round((maxV / 4) * i);
            const y = pad.top + ch - (ch / 4) * i;
            ctx.fillText(val.toLocaleString(), pad.left - 6, y);
            ctx.globalAlpha = 0.3; ctx.strokeStyle = bc; ctx.lineWidth = 0.5;
            ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(pad.left + cw, y); ctx.stroke();
            ctx.globalAlpha = 1;
        }

        // bars
        for (let i = 0; i < count; i++) {
            const b = bars[i];
            const pf = valFn(b, 0);
            const dg = valFn(b, 1);
            const x = b.x;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;

            // Highlight bar group on hover
            if (i === hoverIdx) {
                ctx.fillStyle = 'rgba(99,102,241,0.15)';
                ctx.fillRect(x - 2, pad.top, gw + 4, ch);
            }

            ctx.fillStyle = colors[0];
            ctx.fillRect(x, pad.top + ch - pfH, barW, pfH);
            ctx.fillStyle = colors[1];
            ctx.fillRect(x + barW + gap, pad.top + ch - dgH, barW, dgH);

            ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
            ctx.fillText(labelFn(i), x + gw / 2, pad.top + ch + 4);
        }

        // legend
        ctx.font = '10px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
        ctx.fillStyle = colors[0]; ctx.fillRect(w - 92, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('Prompt', w - 79, 1);
        ctx.fillStyle = colors[1]; ctx.fillRect(w - 42, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText('Predicted', w - 29, 1);

        // tooltip
        if (hoverIdx >= 0 && hoverIdx < count) {
            const b = bars[hoverIdx];
            const lines = [b.label, 'Prompt: ' + b.promptVal.toLocaleString(), 'Predicted: ' + b.predictedVal.toLocaleString()];
            ctx.font = '11px sans-serif';
            const lineH = 15;
            const padX = 8, padY = 5;
            let maxTW = 0;
            for (const l of lines) {
                const tw = ctx.measureText(l).width;
                if (tw > maxTW) maxTW = tw;
            }
            const tw = maxTW + padX * 2;
            const th = lineH * lines.length + padY * 2;
            let tx = b.x + b.barW + 10;
            let ty = b.y - th - 6;
            if (tx + tw > w - 4) tx = b.x - tw - 6;
            if (ty < 2) ty = b.y + 10;

            ctx.fillStyle = 'rgba(0,0,0,0.82)';
            ctx.beginPath();
            roundRect(ctx, tx, ty, tw, th, 4);
            ctx.fill();
            ctx.strokeStyle = 'rgba(255,255,255,0.15)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            roundRect(ctx, tx, ty, tw, th, 4);
            ctx.stroke();

            ctx.fillStyle = '#fff';
            ctx.font = 'bold 11px sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            ctx.fillText(lines[0], tx + padX, ty + padY);
            ctx.font = '10px sans-serif';
            ctx.fillStyle = colors[0].replace('0.8', '1');
            ctx.fillText('P: ' + b.promptVal.toLocaleString(), tx + padX, ty + padY + lineH);
            ctx.fillStyle = colors[1].replace('0.8', '1');
            ctx.fillText('O: ' + b.predictedVal.toLocaleString(), tx + padX, ty + padY + lineH * 2);
        }
    }

    function roundRect(ctx, x, y, w, h, r) {
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    }

    // --- Per-model chart ---
    function renderTokenSummary() {
        const canvasEl = document.getElementById('tokenChartCanvas');
        const emptyEl = document.getElementById('tokenChartEmpty');
        const wrapEl = document.getElementById('tokenChartWrap');
        const stats = document.getElementById('tokenSummaryStats');
        const body = document.getElementById('tokenSummaryBody');
        if (!body) return;

        let totalModels = tokenSummaryData.length;
        let totalPrompt = 0;
        let totalPredicted = 0;
        let totalCache = 0;
        let totalDraftTokens = 0;
        let totalDraftAccepted = 0;
        let cardHtml = '';
        const sorted = tokenSummaryData.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        for (const m of sorted) {
            totalPrompt += m.totalPromptTokens || 0;
            totalPredicted += m.totalPredictedTokens || 0;
            totalCache += m.totalCacheTokens || 0;
            totalDraftTokens += m.totalDraftTokens || 0;
            totalDraftAccepted += m.totalDraftAccepted || 0;
            let draftHtml = '';
            if (m.totalDraftTokens > 0) {
                const draftPct = m.totalDraftTokens > 0 ? ((m.totalDraftAccepted || 0) / m.totalDraftTokens * 100).toFixed(1) : '0.0';
                draftHtml = '<span class="tk-draft">' + t('report.draft_label', '投机') + ' ' + (m.totalDraftAccepted || 0) + '/' + m.totalDraftTokens + ' (' + draftPct + '%)</span>';
            }
            cardHtml += '<div class="token-card">'
                + '<div class="tk-model">' + escapeHtml(m.modelId || '') + '</div>'
                + '<div class="tk-tokens">'
                + '<span>' + t('report.prompt_label', '输入') + ' ' + (m.totalPromptTokens || 0).toLocaleString() + '</span>'
                + '<span>' + t('report.output_label', '输出') + ' ' + (m.totalPredictedTokens || 0).toLocaleString() + '</span>'
                + '<span class="tk-cache">' + t('report.cache_label', '缓存') + ' ' + (m.totalCacheTokens || 0).toLocaleString() + '</span>'
                + draftHtml
                + '</div>'
                + '</div>';
        }
        if (!cardHtml) {
            cardHtml = '<div class="empty">' + t('page.usage_report.chart_empty', '暂无数据') + '</div>';
        }
        body.innerHTML = cardHtml;

        stats.innerHTML = ''
            + '<div class="stat-card"><div class="stat-value">' + totalModels + '</div><div class="stat-label">' + t('page.usage_report.stats.models_with_records', '有记录的模型') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPrompt.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_prompt', '总输入 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPredicted.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_predicted', '总输出 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalCache.toLocaleString() + '</div><div class="stat-label">' + t('report.total_cache', '总缓存命中 Token') + '</div></div>'
            + (totalDraftTokens > 0 ? '<div class="stat-card"><div class="stat-value">' + totalDraftAccepted.toLocaleString() + '/' + totalDraftTokens.toLocaleString() + '</div><div class="stat-label">' + t('report.total_draft', '总投机解码') + '</div></div>' : '');

        if (!canvasEl || !emptyEl || !wrapEl) return;
        if (!tokenSummaryData.length) {
            emptyEl.style.display = 'flex';
            tokenBars = [];
            return;
        }
        emptyEl.style.display = 'none';

        const data = tokenSummaryData.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width - 16, h = rect.height - 16;
        if (w <= 0 || h <= 0) return;
        canvasEl.width = w * dpr; canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);

        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.28, 28);
        const gap = barW * 0.4;
        const gw = barW * 2 + gap;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.totalPromptTokens || 0, d.totalPredictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const style = getComputedStyle(wrapEl);
        const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
        const bc = style.getPropertyValue('--border-color').trim() || '#333';
        const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];

        // Build bar geometry
        tokenBars = [];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = d.totalPromptTokens || 0;
            const dg = d.totalPredictedTokens || 0;
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;
            const barTop = pad.top + ch - Math.max(pfH, dgH);
            tokenBars.push({
                x: x, y: barTop, w: gw, h: Math.max(pfH, dgH),
                barW: barW, gap: gap, gw: gw,
                label: d.modelId || ('#' + (i + 1)),
                promptVal: pf, predictedVal: dg
            });
        }

        // Draw
        drawBarsChart(ctx, w, h, pad, cw, ch, tokenBars, maxV, colors, tc, bc, tokenHoverIdx,
            function(i) { return i + 1; },
            function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
        );

        // Marker legend
        const parentCol = wrapEl.parentNode;
        let legendEl = parentCol.querySelector('.chart-marker-legend');
        if (!legendEl) {
            legendEl = document.createElement('div');
            legendEl.className = 'chart-marker-legend';
            parentCol.appendChild(legendEl);
        }
        let legendHtml = '';
        for (let i = 0; i < count; i++) {
            legendHtml += '<span class="ml-item"><span class="ml-marker">' + (i + 1) + '</span> ' + escapeHtml(data[i].modelId || '#' + (i + 1)) + '</span>';
        }
        legendEl.innerHTML = legendHtml;

        // Mouse events
        canvasEl.onmousemove = function (e) {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            for (let i = 0; i < tokenBars.length; i++) {
                const b = tokenBars[i];
                if (mx >= b.x - 2 && mx <= b.x + b.w + 2 && my >= b.y - 2 && my <= b.y + b.h + 2) {
                    found = i;
                    break;
                }
            }
            if (found !== tokenHoverIdx) {
                tokenHoverIdx = found;
                drawBarsChart(ctx, w, h, pad, cw, ch, tokenBars, maxV, colors, tc, bc, tokenHoverIdx,
                    function(i) { return i + 1; },
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
        canvasEl.onmouseleave = function () {
            if (tokenHoverIdx !== -1) {
                tokenHoverIdx = -1;
                drawBarsChart(ctx, w, h, pad, cw, ch, tokenBars, maxV, colors, tc, bc, tokenHoverIdx,
                    function(i) { return i + 1; },
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
    }

    // --- Daily chart ---
    function renderDailyChart() {
        const canvasEl = document.getElementById('dailyChartCanvas');
        const emptyEl = document.getElementById('dailyChartEmpty');
        const wrapEl = document.getElementById('dailyChartWrap');
        if (!canvasEl || !emptyEl || !wrapEl) return;

        if (!dailyTokenData.length) {
            emptyEl.style.display = 'flex';
            dailyBars = [];
            return;
        }

        const hasData = dailyTokenData.some(d => d.promptTokens > 0 || d.predictedTokens > 0);
        if (!hasData) {
            emptyEl.style.display = 'flex';
            dailyBars = [];
            return;
        }
        emptyEl.style.display = 'none';

        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width - 16, h = rect.height - 16;
        if (w <= 0 || h <= 0) return;
        canvasEl.width = w * dpr; canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);

        const data = dailyTokenData;
        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.28, 28);
        const gap = barW * 0.4;
        const gw = barW * 2 + gap;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.promptTokens || 0, d.predictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const style = getComputedStyle(wrapEl);
        const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
        const bc = style.getPropertyValue('--border-color').trim() || '#333';
        const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];

        // Build bar geometry
        dailyBars = [];
        for (let i = 0; i < count; i++) {
            const d = data[i];
            const pf = d.promptTokens || 0;
            const dg = d.predictedTokens || 0;
            const x = pad.left + (cw / count) * i + (cw / count - gw) / 2;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;
            const barTop = pad.top + ch - Math.max(pfH, dgH);
            dailyBars.push({
                x: x, y: barTop, w: gw, h: Math.max(pfH, dgH),
                barW: barW, gap: gap, gw: gw,
                label: d.date ? d.date.substring(5) : '',
                promptVal: pf, predictedVal: dg
            });
        }

        // Draw
        drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
            function(i) { return dailyBars[i].label; },
            function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
        );

        // Mouse events
        canvasEl.onmousemove = function (e) {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            for (let i = 0; i < dailyBars.length; i++) {
                const b = dailyBars[i];
                if (mx >= b.x - 2 && mx <= b.x + b.w + 2 && my >= b.y - 2 && my <= b.y + b.h + 2) {
                    found = i;
                    break;
                }
            }
            if (found !== dailyHoverIdx) {
                dailyHoverIdx = found;
                drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
                    function(i) { return dailyBars[i].label; },
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
        canvasEl.onmouseleave = function () {
            if (dailyHoverIdx !== -1) {
                dailyHoverIdx = -1;
                drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
                    function(i) { return dailyBars[i].label; },
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
    }

    function buildModelFilter() {
        const select = document.getElementById('requestLogModelFilter');
        const modelSet = new Set();
        for (const r of requestLogsData) {
            if (r.modelId) modelSet.add(r.modelId);
        }
        const models = Array.from(modelSet).sort();
        select.innerHTML = '<option value="">' + t('page.model.filter.all', '全部') + '</option>';
        for (const m of models) {
            select.innerHTML += '<option value="' + escapeAttr(m) + '">' + escapeHtml(m) + '</option>';
        }
    }

    function filterByModel() {
        applyFilter();
    }

    function applyFilter() {
        const modelFilter = document.getElementById('requestLogModelFilter').value;
        if (modelFilter) {
            filteredLogs = requestLogsData.filter(r => r.modelId === modelFilter);
        } else {
            filteredLogs = requestLogsData.slice();
        }
        currentPage = 1;
        renderRequestLogs();
    }

    function prevPage() {
        if (currentPage > 1) {
            currentPage--;
            renderRequestLogs();
        }
    }

    function nextPage() {
        const totalPages = Math.ceil(filteredLogs.length / PAGE_SIZE) || 1;
        if (currentPage < totalPages) {
            currentPage++;
            renderRequestLogs();
        }
    }

    function renderRequestLogs() {
        const body = document.getElementById('requestLogBody');
        const pageInfo = document.getElementById('reqLogPageInfo');
        const prevBtn = document.getElementById('reqLogPrev');
        const nextBtn = document.getElementById('reqLogNext');

        const totalPages = Math.ceil(filteredLogs.length / PAGE_SIZE) || 1;
        if (currentPage > totalPages) currentPage = totalPages;
        const start = (currentPage - 1) * PAGE_SIZE;
        const end = Math.min(start + PAGE_SIZE, filteredLogs.length);
        const pageData = filteredLogs.slice(start, end);

        pageInfo.textContent = t('page.usage_report.page_info', '第 {current} / {total} 页').replace('{current}', currentPage).replace('{total}', totalPages);
        prevBtn.disabled = currentPage <= 1;
        nextBtn.disabled = currentPage >= totalPages;

        let html = '';
        for (const r of pageData) {
            const draftDisplay = r.draftTokens > 0 ? (r.draftAccepted || 0) + '/' + r.draftTokens : '-';
            html += '<tr>'
                + '<td>' + formatTime(r.startTime) + '</td>'
                + '<td>' + escapeHtml(r.modelId || '') + '</td>'
                + '<td>' + escapeHtml(r.endpoint || '') + '</td>'
                + '<td>' + (r.cacheTokens || 0).toLocaleString() + '</td>'
                + '<td>' + (r.promptTokens || 0).toLocaleString() + '</td>'
                + '<td>' + (r.predictedTokens || 0).toLocaleString() + '</td>'
                + '<td><strong>' + (r.totalTokens || 0).toLocaleString() + '</strong></td>'
                + '<td>' + (r.elapsedMs || 0).toLocaleString() + '</td>'
                + '<td>' + formatNum(r.promptPerSecond) + '</td>'
                + '<td>' + formatNum(r.predictedPerSecond) + '</td>'
                + '<td>' + escapeHtml(draftDisplay) + '</td>'
                + '</tr>';
        }
        if (!html) {
            html = '<tr><td class="empty" colspan="11">' + t('page.usage_report.no_request_logs', '暂无请求记录') + '</td></tr>';
        }
        body.innerHTML = html;
    }

    function switchTab(tabName) {
        document.querySelectorAll('#main-usage-report .tab-bar button').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tabName);
        });
        document.querySelectorAll('#main-usage-report .tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.id === 'panel-' + tabName);
        });
        if (tabName === 'token-summary') {
            setTimeout(() => { renderTokenSummary(); renderDailyChart(); }, 50);
        }
    }

    function formatNum(val) {
        if (val == null || isNaN(val)) return '0';
        if (typeof val === 'number') {
            if (Number.isInteger(val)) return val.toLocaleString();
            return val.toFixed(2);
        }
        return String(val);
    }

    function formatTime(wallTime) {
        if (!wallTime) return '-';
        const d = new Date(wallTime);
        const pad2 = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }

    function escapeHtml(str) {
        return String(str).replace(/[&<>"']/g, function(m) {
            return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]);
        });
    }

    function escapeAttr(str) {
        return escapeHtml(str).replace(/`/g, '&#96;');
    }

    let _initialized = false;
    function init() {
        if (_initialized) return;
        _initialized = true;
    }

    window.UsageReport = {
        init: init,
        load: load,
        switchTab: switchTab,
        filterByModel: filterByModel,
        prevPage: prevPage,
        nextPage: nextPage
    };
})();
