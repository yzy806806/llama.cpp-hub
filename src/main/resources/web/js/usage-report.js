(function () {
    const PAGE_SIZE = 30;

    let tokenSummaryData = [];
    let requestLogsData = [];
    let dailyTokenData = [];
    let requestLogTotal = 0;
    let currentPage = 1;
    let selectedModelId = '';

    // Tooltip state for each chart
    let tokenBars = [];
    let dailyBars = [];
    let tokenHoverIdx = -1;
    let dailyHoverIdx = -1;

    function t(key, fallback) {
        if (window.I18N && typeof window.I18N.t === 'function') return window.I18N.t(key, fallback);
        return fallback || key;
    }

    function tf(key, params, fallback) {
        const template = t(key, fallback);
        if (!params || template == null) return template;
        let out = String(template);
        for (const k of Object.keys(params)) {
            out = out.split('{' + k + '}').join(String(params[k]));
        }
        return out;
    }

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    async function load() {
        init();
        await initMonthSelectors();
        await Promise.all([fetchTokenSummary(), fetchRequestLogs(), fetchDailyTokens()]);

        // Bind change events for month selectors
        const yearSel = document.getElementById('dailyYearSelect');
        const monthSel = document.getElementById('dailyMonthSelect');
        if (yearSel) yearSel.onchange = fetchDailyTokens;
        if (monthSel) monthSel.onchange = fetchDailyTokens;
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
        buildModelFilter();
    }

    async function fetchDailyTokens() {
        const year = document.getElementById('dailyYearSelect').value;
        const month = document.getElementById('dailyMonthSelect').value;
        let url = '/api/report/daily-tokens?year=' + year + '&month=' + month;
        if (selectedModelId) {
            url += '&modelId=' + encodeURIComponent(selectedModelId);
        }
        try {
            const resp = await fetch(url);
            const result = await resp.json();
            if (!result || !result.success) {
                dailyTokenData = [];
            } else {
                dailyTokenData = result.data || [];
            }
        } catch (e) {
            dailyTokenData = [];
        }
        updateDailyTitle(year, month);
        renderDailyChart();
    }

    async function initMonthSelectors() {
        const yearSel = document.getElementById('dailyYearSelect');
        const monthSel = document.getElementById('dailyMonthSelect');
        if (!yearSel || !monthSel) return;

        // Populate months
        monthSel.innerHTML = '';
        const monthSuffix = t('page.usage_report.chart.month_suffix', '月');
        for (let m = 1; m <= 12; m++) {
            const opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m + monthSuffix;
            monthSel.appendChild(opt);
        }

        // Fetch available years
        try {
            const resp = await fetch('/api/report/available-years');
            const result = await resp.json();
            if (result && result.success && Array.isArray(result.data)) {
                yearSel.innerHTML = '';
                const yearSuffix = t('page.usage_report.chart.year_suffix', '年');
                for (const y of result.data) {
                    const opt = document.createElement('option');
                    opt.value = y;
                    opt.textContent = y + yearSuffix;
                    yearSel.appendChild(opt);
                }
            }
        } catch (e) {
            // Fallback to current year
            yearSel.innerHTML = '';
            const opt = document.createElement('option');
            opt.value = new Date().getFullYear();
            opt.textContent = new Date().getFullYear() + '年';
            yearSel.appendChild(opt);
        }

        // Default to current year/month
        const now = new Date();
        yearSel.value = now.getFullYear();
        monthSel.value = now.getMonth() + 1;
    }

    function updateDailyTitle(year, month) {
        const titleEl = document.getElementById('dailyChartTitle');
        if (titleEl) {
            const base = tf('page.usage_report.chart.daily_title', { year: String(year), month: String(month) }, year + '年' + month + '月用量');
            if (selectedModelId) {
                titleEl.textContent = '[' + escapeHtml(selectedModelId) + '] ' + base;
            } else {
                titleEl.textContent = base;
            }
        }
    }

    async function fetchRequestLogs() {
        let url = '/api/report/request-logs?page=' + currentPage + '&pageSize=' + PAGE_SIZE;
        if (selectedModelId) {
            url += '&modelId=' + encodeURIComponent(selectedModelId);
        }
        try {
            const resp = await fetch(url);
            const result = await resp.json();
            if (!result || !result.success) {
                requestLogsData = [];
            } else {
                requestLogsData = (result.data && result.data.records) || [];
                requestLogTotal = result.data ? (result.data.total || 0) : 0;
            }
        } catch (e) {
            requestLogsData = [];
        }
        renderRequestLogs();
    }

    // --- Shared tooltip draw helper (overlaid bars) ---
    function drawBarsChart(ctx, w, h, pad, cw, ch, bars, maxV, colors, tc, bc, hoverIdx, labelFn, valFn) {
        const barW = bars[0] ? bars[0].barW : 0;
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

        // bars (overlaid: larger drawn behind, smaller on top)
        for (let i = 0; i < count; i++) {
            const b = bars[i];
            const pf = valFn(b, 0);
            const dg = valFn(b, 1);
            const x = b.x;
            const pfH = (pf / maxV) * ch;
            const dgH = (dg / maxV) * ch;

            // Skip drawing bars when both values are 0
            if (pf === 0 && dg === 0) {
                ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
                ctx.fillText(labelFn(i), x + gw / 2, pad.top + ch + 4);
                continue;
            }

            // Highlight bar group on hover
            if (i === hoverIdx) {
                ctx.fillStyle = 'rgba(99,102,241,0.15)';
                ctx.fillRect(x - 2, pad.top, gw + 4, ch);
            }

            // Draw larger bar behind, smaller on top (both solid color + border)
            if (pf >= dg) {
                ctx.fillStyle = colors[0];
                ctx.fillRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.strokeStyle = colors[0].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.fillStyle = colors[1];
                ctx.fillRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.strokeStyle = colors[1].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - dgH, barW, dgH);
            } else {
                ctx.fillStyle = colors[1];
                ctx.fillRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.strokeStyle = colors[1].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - dgH, barW, dgH);
                ctx.fillStyle = colors[0];
                ctx.fillRect(x, pad.top + ch - pfH, barW, pfH);
                ctx.strokeStyle = colors[0].replace('0.8', '1');
                ctx.lineWidth = 1.5;
                ctx.strokeRect(x, pad.top + ch - pfH, barW, pfH);
            }

            ctx.fillStyle = tc; ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
            ctx.fillText(labelFn(i), x + gw / 2, pad.top + ch + 4);
        }

        // legend
        ctx.font = '10px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
        ctx.fillStyle = colors[0]; ctx.fillRect(w - 92, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText(t('report.prompt_label', '输入'), w - 79, 1);
        ctx.fillStyle = colors[1]; ctx.fillRect(w - 42, 2, 9, 9);
        ctx.fillStyle = tc; ctx.fillText(t('report.output_label', '输出'), w - 29, 1);

        // tooltip
        if (hoverIdx >= 0 && hoverIdx < count) {
            const b = bars[hoverIdx];
            const lines = [b.label, t('report.prompt_label', '输入') + ': ' + b.promptVal.toLocaleString(), t('report.output_label', '输出') + ': ' + b.predictedVal.toLocaleString()];
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
        sorted.forEach((m, idx) => {
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
            const isActive = selectedModelId === (m.modelId || '');
            cardHtml += '<div class="token-card' + (isActive ? ' token-card-selected' : '') + '" data-model-id="' + escapeAttr(m.modelId || '') + '" style="cursor:pointer;" title="' + (isActive ? '点击取消选择' : '点击选择此模型') + '">'
                + '<div class="tk-model"><span class="tk-badge">' + (idx + 1) + '</span>' + escapeHtml(m.modelId || '') + (isActive ? ' <i class="fas fa-check-circle" style="color:var(--primary-color);margin-left:4px;font-size:12px;"></i>' : '') + '</div>'
                + '<div class="tk-tokens">'
                + '<span>' + t('report.prompt_label', '输入') + ' ' + (m.totalPromptTokens || 0).toLocaleString() + '</span>'
                + '<span>' + t('report.output_label', '输出') + ' ' + (m.totalPredictedTokens || 0).toLocaleString() + '</span>'
                + '<span class="tk-cache">' + t('report.cache_label', '缓存') + ' ' + (m.totalCacheTokens || 0).toLocaleString() + '</span>'
                + draftHtml
                + '</div>'
                + '</div>';
        });
        if (!cardHtml) {
            cardHtml = '<div class="empty">' + t('page.usage_report.chart_empty', '暂无数据') + '</div>';
        }
        body.innerHTML = cardHtml;

        // Bind click handlers for model selection
        const cards = body.querySelectorAll('.token-card');
        for (const card of cards) {
            card.onclick = function () {
                const modelId = this.dataset.modelId || '';
                if (selectedModelId === modelId) {
                    selectedModelId = '';
                } else {
                    selectedModelId = modelId;
                }
                renderTokenSummary();
                fetchDailyTokens();
            };
        }

        stats.innerHTML = ''
            + '<div class="stat-card"><div class="stat-value">' + totalModels + '</div><div class="stat-label">' + t('page.usage_report.stats.models_with_records', '有记录的模型') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPrompt.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_prompt', '总输入 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalPredicted.toLocaleString() + '</div><div class="stat-label">' + t('page.usage_report.stats.total_predicted', '总输出 Token') + '</div></div>'
            + '<div class="stat-card"><div class="stat-value">' + totalCache.toLocaleString() + '</div><div class="stat-label">' + t('report.total_cache', '总缓存命中 Token') + '</div></div>'
            + (totalDraftTokens > 0 ? '<div class="stat-card"><div class="stat-value">' + totalDraftAccepted.toLocaleString() + '/' + totalDraftTokens.toLocaleString() + '</div><div class="stat-label">' + t('report.total_draft', '总投机解码') + '</div></div>' : '');

        if (!canvasEl || !emptyEl || !wrapEl) return;

        const data = tokenSummaryData.slice().sort((a, b) => (b.totalTokens || 0) - (a.totalTokens || 0));
        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width, h = rect.height;
        if (w <= 0 || h <= 0) return;
        canvasEl.width = w * dpr; canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);

        if (!tokenSummaryData.length) {
            ctx.clearRect(0, 0, w, h);
            emptyEl.style.display = 'flex';
            tokenBars = [];
            canvasEl.onmousemove = null;
            canvasEl.onmouseleave = null;
            return;
        }
        emptyEl.style.display = 'none';

        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.45, 28);
        const gw = barW;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.totalPromptTokens || 0, d.totalPredictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const style = getComputedStyle(wrapEl);
        const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
        const bc = style.getPropertyValue('--border-color').trim() || '#333';
        const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];

        // Build bar geometry (overlaid)
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
                barW: barW, gw: gw,
                label: d.modelId || ('#' + (i + 1)),
                promptVal: pf, predictedVal: dg
            });
        }

        // Draw
        drawBarsChart(ctx, w, h, pad, cw, ch, tokenBars, maxV, colors, tc, bc, tokenHoverIdx,
            function(i) { return i + 1; },
            function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
        );

        // Mouse events (hover entire column slot for easier hit)
        canvasEl.onmousemove = function (e) {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            const slotW = cw / count;
            for (let i = 0; i < tokenBars.length; i++) {
                const slotX = pad.left + slotW * i;
                if (mx >= slotX && mx <= slotX + slotW && my >= pad.top && my <= pad.top + ch) {
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

        const ctx = canvasEl.getContext('2d');
        const rect = wrapEl.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        const w = rect.width, h = rect.height;
        if (w <= 0 || h <= 0) return;
        canvasEl.width = w * dpr; canvasEl.height = h * dpr;
        canvasEl.style.width = w + 'px'; canvasEl.style.height = h + 'px';
        ctx.scale(dpr, dpr);

        if (!dailyTokenData.length) {
            ctx.clearRect(0, 0, w, h);
            emptyEl.style.display = 'flex';
            dailyBars = [];
            canvasEl.onmousemove = null;
            canvasEl.onmouseleave = null;
            return;
        }

        const hasData = dailyTokenData.some(d => d.promptTokens > 0 || d.predictedTokens > 0);
        if (!hasData) {
            ctx.clearRect(0, 0, w, h);
            emptyEl.style.display = 'flex';
            dailyBars = [];
            canvasEl.onmousemove = null;
            canvasEl.onmouseleave = null;
            return;
        }
        emptyEl.style.display = 'none';

        const data = dailyTokenData;
        const count = data.length;
        const pad = { top: 14, bottom: 30, left: 64, right: 14 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;
        const barW = Math.min(cw / count * 0.25, 18);
        const gw = barW;

        let maxV = 0;
        data.forEach(d => maxV = Math.max(maxV, d.promptTokens || 0, d.predictedTokens || 0));
        maxV = Math.ceil(Math.max(maxV * 1.15, 100));

        const style = getComputedStyle(wrapEl);
        const tc = style.getPropertyValue('--text-secondary').trim() || '#999';
        const bc = style.getPropertyValue('--border-color').trim() || '#333';
        const colors = ['rgba(99,102,241,0.8)', 'rgba(16,185,129,0.8)'];

        // Build bar geometry (overlaid)
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
                barW: barW, gw: gw,
                label: d.date ? d.date.substring(5) : '',
                promptVal: pf, predictedVal: dg
            });
        }

        // X-axis label: only first, middle, last
        const midIdx = Math.floor(count / 2);
        function dailyLabelFn(i) {
            if (i === 0 || i === midIdx || i === count - 1) return dailyBars[i].label;
            return '';
        }

        // Draw
        drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
            dailyLabelFn,
            function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
        );

        // Mouse events (hover entire column slot for easier hit)
        canvasEl.onmousemove = function (e) {
            const r = canvasEl.getBoundingClientRect();
            const mx = e.clientX - r.left;
            const my = e.clientY - r.top;
            let found = -1;
            const slotW = cw / count;
            for (let i = 0; i < dailyBars.length; i++) {
                const slotX = pad.left + slotW * i;
                if (mx >= slotX && mx <= slotX + slotW && my >= pad.top && my <= pad.top + ch) {
                    found = i;
                    break;
                }
            }
            if (found !== dailyHoverIdx) {
                dailyHoverIdx = found;
                drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
                    dailyLabelFn,
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
        canvasEl.onmouseleave = function () {
            if (dailyHoverIdx !== -1) {
                dailyHoverIdx = -1;
                drawBarsChart(ctx, w, h, pad, cw, ch, dailyBars, maxV, colors, tc, bc, dailyHoverIdx,
                    dailyLabelFn,
                    function(b, which) { return which === 0 ? b.promptVal : b.predictedVal; }
                );
            }
        };
    }

    function buildModelFilter() {
        const select = document.getElementById('requestLogModelFilter');
        const modelSet = new Set();
        for (const r of tokenSummaryData) {
            if (r.modelId) modelSet.add(r.modelId);
        }
        const models = Array.from(modelSet).sort();
        select.innerHTML = '<option value="">' + t('page.model.filter.all', '全部') + '</option>';
        for (const m of models) {
            select.innerHTML += '<option value="' + escapeAttr(m) + '">' + escapeHtml(m) + '</option>';
        }
    }

    function filterByModel() {
        const modelFilter = document.getElementById('requestLogModelFilter').value;
        if (selectedModelId !== modelFilter) {
            selectedModelId = modelFilter;
            currentPage = 1;
            fetchRequestLogs();
        }
    }

    function renderRequestLogs() {
        const body = document.getElementById('requestLogBody');
        const pageInfo = document.getElementById('reqLogPageInfo');
        const prevBtn = document.getElementById('reqLogPrev');
        const nextBtn = document.getElementById('reqLogNext');

        const totalPages = Math.ceil(requestLogTotal / PAGE_SIZE) || 1;
        if (currentPage > totalPages) currentPage = totalPages;
        const pageData = requestLogsData;

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

        const observer = new ResizeObserver(() => {
            if (document.getElementById('panel-token-summary')?.classList.contains('active')) {
                renderTokenSummary();
                renderDailyChart();
            }
        });
        const tw = document.getElementById('tokenChartWrap');
        const dw = document.getElementById('dailyChartWrap');
        if (tw) observer.observe(tw);
        if (dw) observer.observe(dw);
    }

    window.UsageReport = {
        init: init,
        load: load,
        switchTab: switchTab,
        filterByModel: filterByModel,
        prevPage: function() { if (currentPage > 1) { currentPage--; fetchRequestLogs(); } },
        nextPage: function() { currentPage++; fetchRequestLogs(); }
    };
})();
