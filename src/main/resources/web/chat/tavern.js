/**
 * 酒馆（SillyTavern）兼容模块 — Easy Chat
 *
 * 职责：
 * 1. 角色卡导入：解析 PNG（tEXt chunk）或 JSON，转成 card 对象存入 assistant
 * 2. 世界书导入/清除：worldBook JSON 存入 assistant
 * 3. 回复选项（CYOA）：调后端 /api/chat/suggestions 生成选项，渲染按钮，点击代入
 * 4. 上下文压缩：历史超限时调后端 /api/chat/summarize 生成摘要，替换旧消息
 *
 * 挂载到 window.Tavern，由 index.html 在初始化后调用 window.Tavern.init()。
 */
(function () {
  'use strict';

  const SUGGESTIONS_ENDPOINT = '/api/chat/suggestions';
  const SUMMARIZE_ENDPOINT = '/api/chat/summarize';

  // 上下文压缩阈值：对话历史估算 token 超过该比例即触发（对 ctxSize 的上限）
  const COMPRESS_THRESHOLD_RATIO = 0.7;
  // 压缩后保留的最近消息条数
  const COMPRESS_KEEP_RECENT = 20;
  // 压缩间隔保护：两次压缩之间至少间隔的消息增量，避免反复触发
  const COMPRESS_MIN_DELTA = 6;

  function stringifyPlain(value) {
    if (value == null) return '';
    if (typeof value === 'string') return value;
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  }

  /** 粗略 token 估算（与后端 TavernAuxRequests.estimateTokens 同逻辑） */
  function estimateTokens(text) {
    if (!text) return 0;
    let cjk = 0;
    let other = 0;
    for (let i = 0; i < text.length; i++) {
      const ch = text.charCodeAt(i);
      if ((ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3000 && ch <= 0x303F) || (ch >= 0xFF00 && ch <= 0xFFEF)) {
        cjk++;
      } else {
        other++;
      }
    }
    return cjk + Math.ceil(other / 4);
  }

  /** 解析 PNG 的 tEXt chunk，返回 {keyword: value} 映射 */
  function parsePngTextChunks(bytes) {
    const chunks = {};
    if (!bytes || bytes.length < 8) return chunks;
    // PNG signature: 89 50 4E 47 0D 0A 1A 0A
    for (let i = 0; i < 8; i++) {
      if (bytes[i] !== [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A][i]) return chunks;
    }
    let offset = 8;
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    while (offset + 8 <= bytes.length) {
      const length = view.getUint32(offset, false);
      const typeOffset = offset + 4;
      let type = '';
      for (let i = 0; i < 4; i++) {
        type += String.fromCharCode(bytes[typeOffset + i]);
      }
      const dataStart = typeOffset + 4;
      const dataEnd = dataStart + length;
      if (dataEnd + 4 > bytes.length) break;
      if (type === 'tEXt') {
        // keyword\0text
        let nulIndex = -1;
        for (let i = dataStart; i < dataEnd; i++) {
          if (bytes[i] === 0) {
            nulIndex = i;
            break;
          }
        }
        if (nulIndex >= dataStart && nulIndex < dataEnd) {
          const keyword = String.fromCharCode.apply(null, bytes.slice(dataStart, nulIndex));
          const textBytes = bytes.slice(nulIndex + 1, dataEnd);
          chunks[keyword] = new TextDecoder().decode(textBytes);
        }
      }
      offset = dataEnd + 4; // skip CRC
    }
    return chunks;
  }

  /** base64 解码（宽容处理 URL-safe 与补位） */
  function base64Decode(str) {
    if (!str) return null;
    let s = String(str).trim();
    if (s.includes(',')) {
      s = s.split(',')[1] || '';
    }
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4 !== 0) {
      s += '=';
    }
    try {
      const binary = atob(s);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
      }
      return bytes;
    } catch (e) {
      return null;
    }
  }

  /** 从字节解析角色卡 JSON（兼容 PNG tEXt / 直接 JSON） */
  function parseCharacterCardBytes(bytes) {
    if (!bytes || bytes.length === 0) return null;
    // 尝试直接 UTF-8 JSON
    try {
      const text = new TextDecoder('utf-8').decode(bytes);
      const trimmed = text.trim();
      if (trimmed.startsWith('{')) {
        const parsed = JSON.parse(trimmed);
        if (parsed && (parsed.data || parsed.name || parsed.description)) {
          return normalizeCard(parsed);
        }
      }
    } catch (e) {
      // not json, fall through to PNG
    }
    // PNG tEXt chunk: keyword "chara" (V2) / "ccv3" (V3)
    const chunks = parsePngTextChunks(bytes);
    let payload = chunks['chara'] || chunks['ccv3'];
    if (!payload) return null;
    const decoded = base64Decode(payload);
    if (!decoded) return null;
    try {
      const parsed = JSON.parse(new TextDecoder('utf-8').decode(decoded));
      return normalizeCard(parsed);
    } catch (e) {
      return null;
    }
  }

  /** 归一化角色卡：兼容 V1（平铺）/ V2（data 块）/ V3 */
  function normalizeCard(parsed) {
    const data = parsed.data && typeof parsed.data === 'object' ? parsed.data : parsed;
    const card = {
      name: data.name || parsed.name || '',
      description: data.description || '',
      personality: data.personality || '',
      scenario: data.scenario || '',
      firstMes: data.first_mes || data.firstMes || '',
      mesExample: data.mes_example || data.mesExample || '',
      systemPrompt: data.system_prompt || data.systemPrompt || ''
    };
    // 内嵌世界书（character_book）
    if (data.character_book && typeof data.character_book === 'object') {
      try {
        card.characterBook = JSON.stringify(data.character_book);
      } catch (e) {
        card.characterBook = '';
      }
    }
    return card;
  }

  /** 从文件读取字节 */
  function readFileAsBytes(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const arrayBuffer = reader.result;
          resolve(new Uint8Array(arrayBuffer));
        } catch (e) {
          reject(e);
        }
      };
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(file);
    });
  }

  /**
   * 获取当前 UI 状态访问器。
   * index.html 通过 setStateAccessor 注入，解耦本模块与主页面变量作用域。
   */
  let stateAccessor = null;
  function setStateAccessor(accessor) {
    stateAccessor = accessor;
  }

  function getState() {
    if (!stateAccessor || typeof stateAccessor.getState !== 'function') {
      throw new Error('Tavern state accessor not initialized');
    }
    return stateAccessor.getState();
  }

  function getCurrentAssistant() {
    if (stateAccessor && typeof stateAccessor.getCurrentAssistant === 'function') {
      return stateAccessor.getCurrentAssistant();
    }
    const state = getState();
    if (!state || !Array.isArray(state.assistants)) return null;
    return state.assistants.find(a => a.id === state.currentAssistantId) || state.assistants[0] || null;
  }

  function getCurrentConversation() {
    if (stateAccessor && typeof stateAccessor.getCurrentConversation === 'function') {
      return stateAccessor.getCurrentConversation();
    }
    const state = getState();
    if (!state || !Array.isArray(state.conversations)) return null;
    return state.conversations.find(c => c.id === state.currentConversationId) || null;
  }

  function toast(type, message) {
    if (stateAccessor && typeof stateAccessor.toast === 'function') {
      stateAccessor.toast(type, message);
    } else if (window.showToast) {
      window.showToast(type, message);
    } else if (typeof showToast === 'function') {
      showToast(type, message);
    } else {
      console.log('[' + type + '] ' + message);
    }
  }

  async function apiPost(url, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (window.currentApiKey) {
      headers['Authorization'] = 'Bearer ' + window.currentApiKey;
    }
    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || ('HTTP ' + response.status));
    }
    return response.json();
  }

  /* ---- 角色卡 ---- */

  async function importCard(file) {
    const bytes = await readFileAsBytes(file);
    const card = parseCharacterCardBytes(bytes);
    if (!card) {
      throw new Error('无法解析角色卡：不是有效的 PNG/JSON 角色卡');
    }
    const assistant = getCurrentAssistant();
    if (!assistant) {
      throw new Error('当前没有助手，无法导入角色卡');
    }
    // 角色卡 name 覆盖助手名（若卡里有）
    if (card.name) {
      assistant.name = card.name;
    }
    assistant.card = card;
    if (card.characterBook && !assistant.worldBook) {
      assistant.worldBook = card.characterBook;
    }
    if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
      stateAccessor.onAssistantChanged(assistant);
    }
    return card;
  }

  function clearCard() {
    const assistant = getCurrentAssistant();
    if (!assistant) return;
    delete assistant.card;
    if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
      stateAccessor.onAssistantChanged(assistant);
    }
  }

  /* ---- 世界书 ---- */

  async function importWorldBook(file) {
    const text = await file.text();
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (e) {
      throw new Error('世界书文件不是有效的 JSON');
    }
    // 校验结构：{entries:...} 或条目数组
    if (!parsed || (!parsed.entries && !Array.isArray(parsed) && typeof parsed !== 'object')) {
      throw new Error('世界书格式无效：缺少 entries 结构');
    }
    const assistant = getCurrentAssistant();
    if (!assistant) {
      throw new Error('当前没有助手，无法导入世界书');
    }
    assistant.worldBook = text;
    if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
      stateAccessor.onAssistantChanged(assistant);
    }
    return parsed;
  }

  function clearWorldBook() {
    const assistant = getCurrentAssistant();
    if (!assistant) return;
    delete assistant.worldBook;
    if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
      stateAccessor.onAssistantChanged(assistant);
    }
  }

  /* ---- 回复选项（CYOA） ---- */

  function isSuggestionsEnabled() {
    const assistant = getCurrentAssistant();
    return !!(assistant && assistant.tavernSuggestions);
  }

  function buildSuggestionsContext() {
    const conversation = getCurrentConversation();
    const history = Array.isArray(conversation?.messages) ? conversation.messages : [];
    const lines = [];
    for (const item of history) {
      const content = typeof item?.content === 'string' ? item.content : '';
      if (content.trim()) {
        const roleLabel = item.role === 'user' ? '用户' : '角色';
        lines.push(roleLabel + '：' + content);
      }
    }
    return lines.slice(-40).join('\n');
  }

  async function generateSuggestions(count = 3) {
    const conversation = getCurrentConversation();
    const assistant = getCurrentAssistant();
    if (!conversation || !assistant) return [];
    const compositeKey = getState().model || '';
    const sep = String(compositeKey).lastIndexOf('::');
    const bareModelId = sep >= 0 ? String(compositeKey).substring(0, sep) : String(compositeKey);
    const nd = sep >= 0 ? String(compositeKey).substring(sep + 2) : '';
    const body = {
      model: bareModelId,
      conversationId: conversation.id,
      assistantName: assistant.name || '',
      count
    };
    if (nd) {
      body.nodeId = nd;
    }
    const result = await apiPost(SUGGESTIONS_ENDPOINT, body);
    const suggestions = Array.isArray(result?.data?.suggestions) ? result.data.suggestions : [];
    return suggestions.filter(s => typeof s === 'string' && s.trim()).slice(0, count);
  }

  async function onSuggestionPicked(text, pickFn) {
    if (typeof pickFn === 'function') {
      pickFn(text);
    }
  }

  /* ---- 上下文压缩 ---- */

  function isCompressEnabled() {
    const assistant = getCurrentAssistant();
    return !!(assistant && assistant.tavernCompress);
  }

  function getContextLimit() {
    const state = getState();
    // model_context_length 或 fallback 32k
    return Number(state?.model_context_length) || 32768;
  }

  function estimateHistoryTokens() {
    const conversation = getCurrentConversation();
    const messages = Array.isArray(conversation?.messages) ? conversation.messages : [];
    let total = 0;
    for (const item of messages) {
      total += estimateTokens(typeof item?.content === 'string' ? item.content : '');
    }
    return total;
  }

  /**
   * 检查是否需要压缩，必要时生成摘要并替换历史。
   * @returns {Promise<boolean>} 是否执行了压缩
   */
  async function maybeCompress(conversation) {
    if (!isCompressEnabled()) return false;
    if (!conversation) return false;
    const messages = Array.isArray(conversation.messages) ? conversation.messages : [];
    if (messages.length < COMPRESS_KEEP_RECENT + 4) return false;

    // 间隔保护：压缩后至少新增 N 条消息才再次触发
    const lastCompressAt = Number(conversation.tavernCompressedAt || 0);
    if (lastCompressAt > 0 && messages.length - lastCompressAt < COMPRESS_MIN_DELTA) return false;

    const limit = getContextLimit();
    const tokens = estimateHistoryTokens();
    if (tokens <= limit * COMPRESS_THRESHOLD_RATIO) return false;

    // 触发压缩：摘要最早的除最近 COMPRESS_KEEP_RECENT 之外的消息
    const keepCount = Math.min(COMPRESS_KEEP_RECENT, messages.length - 2);
    const compressible = messages.slice(0, messages.length - keepCount);
    const recent = messages.slice(messages.length - keepCount);
    const transcript = compressible
      .map(item => (typeof item?.content === 'string' ? item.content : ''))
      .filter(t => t.trim())
      .join('\n');
    if (!transcript.trim()) return false;

    try {
      const assistant = getCurrentAssistant();
      const compositeKey = getState().model || '';
      const sep = String(compositeKey).lastIndexOf('::');
      const bareModelId = sep >= 0 ? String(compositeKey).substring(0, sep) : String(compositeKey);
      const nd = sep >= 0 ? String(compositeKey).substring(sep + 2) : '';
      const body = { model: bareModelId, text: transcript };
      if (nd) {
        body.nodeId = nd;
      }
      const result = await apiPost(SUMMARIZE_ENDPOINT, body);
      const summary = stringifyPlain(result?.data?.summary).trim();
      if (!summary) return false;

      // 用摘要消息替换被压缩的历史
      const summaryMessage = {
        id: 'summary-' + Date.now(),
        role: 'assistant',
        type: 'summary',
        content: '[早期剧情摘要]\n' + summary,
        reasoning: '',
        variants: [{ content: { content: '[早期剧情摘要]\n' + summary } }],
        timestamp: Date.now()
      };
      conversation.messages = [summaryMessage].concat(recent);
      conversation.tavernCompressedAt = conversation.messages.length;
      if (stateAccessor && typeof stateAccessor.onConversationChanged === 'function') {
        stateAccessor.onConversationChanged(conversation);
      }
      toast('info', '上下文过长，已自动压缩早期剧情');
      return true;
    } catch (e) {
      console.warn('[Tavern] 自动压缩失败', e);
      return false;
    }
  }

  /* ---- UI 绑定 ---- */

  function bindUi() {
    const cardInput = document.getElementById('tavernCardInput');
    const cardImportBtn = document.getElementById('tavernCardImportBtn');
    const cardClearBtn = document.getElementById('tavernCardClearBtn');
    const cardStatus = document.getElementById('tavernCardStatus');
    const wbInput = document.getElementById('tavernWorldBookInput');
    const wbImportBtn = document.getElementById('tavernWorldBookImportBtn');
    const wbClearBtn = document.getElementById('tavernWorldBookClearBtn');
    const wbStatus = document.getElementById('tavernWorldBookStatus');
    const sugToggle = document.getElementById('tavernSuggestionsToggle');
    const compToggle = document.getElementById('tavernCompressToggle');

    if (cardImportBtn && cardInput) {
      cardImportBtn.addEventListener('click', () => cardInput.click());
      cardInput.addEventListener('change', async () => {
        const file = cardInput.files && cardInput.files[0];
        if (!file) return;
        try {
          const card = await importCard(file);
          cardStatus.textContent = '已导入角色卡：' + (card.name || '（未命名）');
          cardStatus.style.color = 'inherit';
          if (cardClearBtn) cardClearBtn.disabled = false;
          toast('success', '角色卡已导入');
        } catch (e) {
          toast('error', e.message || '角色卡导入失败');
        }
        cardInput.value = '';
      });
    }
    if (cardClearBtn) {
      cardClearBtn.addEventListener('click', async () => {
        clearCard();
        cardStatus.textContent = '';
        cardClearBtn.disabled = true;
        try {
          await persist();
        } catch (e) {
          // ignore
        }
        toast('success', '角色卡已移除');
      });
    }

    if (wbImportBtn && wbInput) {
      wbImportBtn.addEventListener('click', () => wbInput.click());
      wbInput.addEventListener('change', async () => {
        const file = wbInput.files && wbInput.files[0];
        if (!file) return;
        try {
          const parsed = await importWorldBook(file);
          const entries = parsed && parsed.entries
            ? (Array.isArray(parsed.entries) ? parsed.entries.length : Object.keys(parsed.entries).length)
            : 0;
          wbStatus.textContent = '已导入世界书（' + entries + ' 条）';
          wbStatus.style.color = 'inherit';
          if (wbClearBtn) wbClearBtn.disabled = false;
          toast('success', '世界书已导入');
        } catch (e) {
          toast('error', e.message || '世界书导入失败');
        }
        wbInput.value = '';
      });
    }
    if (wbClearBtn) {
      wbClearBtn.addEventListener('click', async () => {
        clearWorldBook();
        wbStatus.textContent = '';
        wbClearBtn.disabled = true;
        try {
          await persist();
        } catch (e) {
          // ignore
        }
        toast('success', '世界书已移除');
      });
    }

    if (sugToggle) {
      sugToggle.addEventListener('change', () => {
        const assistant = getCurrentAssistant();
        if (assistant) {
          assistant.tavernSuggestions = sugToggle.checked;
          if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
            stateAccessor.onAssistantChanged(assistant);
          }
        }
      });
    }
    if (compToggle) {
      compToggle.addEventListener('change', () => {
        const assistant = getCurrentAssistant();
        if (assistant) {
          assistant.tavernCompress = compToggle.checked;
          if (stateAccessor && typeof stateAccessor.onAssistantChanged === 'function') {
            stateAccessor.onAssistantChanged(assistant);
          }
        }
      });
    }
  }

  /** 从当前 assistant 同步 UI 状态（切换助手时调用） */
  function syncUiFromAssistant() {
    const assistant = getCurrentAssistant();
    const sugToggle = document.getElementById('tavernSuggestionsToggle');
    const compToggle = document.getElementById('tavernCompressToggle');
    const cardClearBtn = document.getElementById('tavernCardClearBtn');
    const cardStatus = document.getElementById('tavernCardStatus');
    const wbClearBtn = document.getElementById('tavernWorldBookClearBtn');
    const wbStatus = document.getElementById('tavernWorldBookStatus');
    if (sugToggle) sugToggle.checked = !!(assistant && assistant.tavernSuggestions);
    if (compToggle) compToggle.checked = !!(assistant && assistant.tavernCompress);
    if (cardClearBtn) cardClearBtn.disabled = !(assistant && assistant.card);
    if (cardStatus) {
      cardStatus.textContent = assistant && assistant.card
        ? '角色卡：' + (assistant.card.name || '（未命名）')
        : '';
      cardStatus.style.color = 'inherit';
    }
    if (wbClearBtn) wbClearBtn.disabled = !(assistant && assistant.worldBook);
    if (wbStatus) {
      wbStatus.textContent = assistant && assistant.worldBook ? '世界书已启用' : '';
      wbStatus.style.color = 'inherit';
    }
  }

  async function persist() {
    if (stateAccessor && typeof stateAccessor.persist === 'function') {
      return stateAccessor.persist();
    }
    if (window.persistState) {
      return window.persistState({ immediate: true });
    }
    return null;
  }

  /* ---- 导出 ---- */

  window.Tavern = {
    init: () => {
      bindUi();
      syncUiFromAssistant();
    },
    syncUiFromAssistant,
    importCard,
    clearCard,
    importWorldBook,
    clearWorldBook,
    isSuggestionsEnabled,
    generateSuggestions,
    onSuggestionPicked,
    isCompressEnabled,
    maybeCompress,
    estimateTokens,
    parseCharacterCardBytes,
    setStateAccessor,
    normalizeCard
  };
})();