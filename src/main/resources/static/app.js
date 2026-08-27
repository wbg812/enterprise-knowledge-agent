// ==================== 认证与请求 ====================
const loginScreen = document.getElementById('loginScreen');
let authToken = sessionStorage.getItem('authToken');

function showLoginScreen() {
    loginScreen.style.display = 'flex';
    document.querySelector('.layout').style.display = 'none';
}

function showMainScreen() {
    loginScreen.style.display = 'none';
    document.querySelector('.layout').style.display = 'flex';
}

// 统一 API 请求封装：自动携带 Token，401 时自动回到登录页
async function apiFetch(url, options) {
    options = options || {};
    options.headers = Object.assign({ 'X-Auth-Token': authToken || '' }, options.headers || {});
    const response = await fetch(url, options);
    if (response.status === 401) {
        authToken = null;
        sessionStorage.removeItem('authToken');
        showLoginScreen();
        throw new Error('未登录或登录已失效');
    }
    return response;
}

async function login() {
    const username = document.getElementById('loginUsername').value.trim();
    const password = document.getElementById('loginPassword').value;
    if (!username || !password) {
        showToast('请输入用户名和密码', 'error');
        return;
    }

    const btn = document.getElementById('loginBtn');
    btn.disabled = true;
    try {
        const response = await fetch('/api/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });
        const data = await response.json();
        if (data.success) {
            authToken = data.token;
            sessionStorage.setItem('authToken', authToken);
            sessionStorage.setItem('username', data.username);
            enterApp();
            showToast('登录成功，欢迎 ' + data.username, 'success');
        } else {
            showToast(data.error || '登录失败', 'error');
        }
    } catch (error) {
        showToast('登录失败：' + error.message, 'error');
    }
    btn.disabled = false;
}

// 密码框回车直接登录
document.getElementById('loginPassword').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') login();
});

async function logout() {
    try {
        await apiFetch('/api/logout', { method: 'POST' });
    } catch (e) {
        // Token 失效也照常退出
    }
    authToken = null;
    sessionStorage.removeItem('authToken');
    sessionStorage.removeItem('username');
    showLoginScreen();
}

// 登录成功后进入主界面：刷新用户标识与数据
function enterApp() {
    showMainScreen();
    const username = sessionStorage.getItem('username') || '管';
    const avatar = document.getElementById('userAvatar');
    avatar.textContent = username.charAt(0).toUpperCase();
    avatar.title = '当前用户：' + username;
    loadDocuments();
    loadStatus();
}

// 页面初始化：校验已有 Token，无效则停留在登录页
(async function initAuth() {
    if (!authToken) {
        showLoginScreen();
        return;
    }
    try {
        const response = await apiFetch('/api/me');
        const data = await response.json();
        if (data.success && data.username && data.username !== 'guest') {
            sessionStorage.setItem('username', data.username);
            enterApp();
        } else {
            showLoginScreen();
        }
    } catch (e) {
        showLoginScreen();
    }
})();

// ==================== 视图切换 ====================
const viewTitles = {
    chat: { title: '智能问答', desc: '基于企业文档的智能检索与问答' },
    documents: { title: '知识库管理', desc: '文档上传、切分与向量化' },
    settings: { title: '模型配置', desc: '大语言模型接口参数' }
};

document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', () => {
        const view = item.dataset.view;
        document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
        item.classList.add('active');
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        document.getElementById('view-' + view).classList.add('active');

        document.getElementById('pageTitle').textContent = viewTitles[view].title;
        document.getElementById('pageDesc').textContent = viewTitles[view].desc;

        if (view === 'documents') loadDocuments();
    });
});

// ==================== 智能问答 ====================
const messagesDiv = document.getElementById('messages');
const welcomeSection = document.getElementById('welcomeSection');
const chatInput = document.getElementById('chatInput');
const sendBtn = document.getElementById('sendBtn');

// 会话 ID：后端据此维护多轮对话记忆，新对话时重置
let sessionId = createSessionId();

function createSessionId() {
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

// 开启新对话：清空前端消息 + 后端会话记忆，重置 sessionId
async function newConversation() {
    try {
        await apiFetch('/api/chat/' + sessionId, { method: 'DELETE' });
    } catch (e) {
        // 忽略清理失败，前端照常重置
    }
    sessionId = createSessionId();
    messagesDiv.innerHTML = '';
    messagesDiv.classList.remove('has-content');
    welcomeSection.style.display = '';
    showToast('已开启新对话，历史记忆已清空', 'success');
    chatInput.focus();
}

chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
});

chatInput.addEventListener('input', () => {
    chatInput.style.height = 'auto';
    chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
});

// 推荐问题点击
document.querySelectorAll('.suggestion-card').forEach(card => {
    card.addEventListener('click', () => {
        chatInput.value = card.dataset.q;
        sendMessage();
    });
});

function nowTime() {
    const d = new Date();
    return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
}

function addMessage(content, type, sources) {
    const msgDiv = document.createElement('div');
    msgDiv.className = `message ${type}`;

    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = type === 'user' ? '👤' : '🤖';

    const body = document.createElement('div');
    body.className = 'message-body';

    const meta = document.createElement('div');
    meta.className = 'message-meta';
    meta.textContent = (type === 'user' ? '我' : '知识库助手') + ' · ' + nowTime();

    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    contentDiv.textContent = content;

    body.appendChild(meta);
    body.appendChild(contentDiv);

    // 来源引用（仅助手消息）
    if (type === 'assistant' && Array.isArray(sources) && sources.length > 0) {
        const srcDiv = document.createElement('div');
        srcDiv.className = 'message-sources';
        const title = document.createElement('div');
        title.className = 'sources-title';
        title.textContent = '📎 参考来源';
        srcDiv.appendChild(title);
        sources.forEach(s => {
            const chip = document.createElement('span');
            chip.className = 'source-chip';
            chip.textContent = fileIcon(s.fileName) + ' ' + s.fileName;
            chip.title = '相关度：' + s.score;
            srcDiv.appendChild(chip);
        });
        body.appendChild(srcDiv);
    }

    msgDiv.appendChild(avatar);
    msgDiv.appendChild(body);
    messagesDiv.appendChild(msgDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;

    return contentDiv;
}

function showTypingIndicator() {
    const msgDiv = document.createElement('div');
    msgDiv.className = 'message assistant';
    msgDiv.id = 'typingIndicator';
    msgDiv.innerHTML = `
        <div class="message-avatar">🤖</div>
        <div class="message-body">
            <div class="message-content">
                <div class="typing-indicator"><span></span><span></span><span></span></div>
            </div>
        </div>`;
    messagesDiv.appendChild(msgDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function removeTypingIndicator() {
    const indicator = document.getElementById('typingIndicator');
    if (indicator) indicator.remove();
}

async function sendMessage() {
    const message = chatInput.value.trim();
    if (!message) return;

    // 首次提问：隐藏欢迎区，显示消息区
    welcomeSection.style.display = 'none';
    messagesDiv.classList.add('has-content');

    addMessage(message, 'user');
    chatInput.value = '';
    chatInput.style.height = 'auto';
    sendBtn.disabled = true;
    showTypingIndicator();

    try {
        const response = await apiFetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, sessionId })
        });

        const data = await response.json();
        removeTypingIndicator();

        if (data.success) {
            addMessage(data.answer, 'assistant', data.sources);
        } else {
            addMessage('抱歉，出现了错误：' + (data.error || '未知错误'), 'assistant');
        }
    } catch (error) {
        removeTypingIndicator();
        addMessage('抱歉，请求失败：' + error.message, 'assistant');
    }

    sendBtn.disabled = false;
    chatInput.focus();
}

// ==================== 知识库管理 ====================
const uploadArea = document.getElementById('uploadArea');
const fileInput = document.getElementById('fileInput');

function fileIcon(name) {
    const lower = name.toLowerCase();
    if (lower.endsWith('.pdf')) return '📕';
    if (lower.endsWith('.xlsx') || lower.endsWith('.xls')) return '📊';
    if (lower.endsWith('.doc') || lower.endsWith('.docx')) return '📘';
    return '📄';
}

uploadArea.addEventListener('click', () => fileInput.click());

uploadArea.addEventListener('dragover', (e) => {
    e.preventDefault();
    uploadArea.classList.add('dragover');
});

uploadArea.addEventListener('dragleave', () => {
    uploadArea.classList.remove('dragover');
});

uploadArea.addEventListener('drop', (e) => {
    e.preventDefault();
    uploadArea.classList.remove('dragover');
    const files = e.dataTransfer.files;
    if (files.length > 0) uploadFile(files[0]);
});

fileInput.addEventListener('change', () => {
    if (fileInput.files.length > 0) {
        uploadFile(fileInput.files[0]);
        fileInput.value = '';
    }
});

async function uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);

    showToast('正在上传并向量化...', 'success');

    try {
        const response = await apiFetch('/api/upload', { method: 'POST', body: formData });
        const data = await response.json();
        if (data.success) {
            showToast('文档上传成功并已建立索引', 'success');
            loadDocuments();
            loadStatus();
        } else {
            showToast('上传失败：' + data.error, 'error');
        }
    } catch (error) {
        showToast('上传失败：' + error.message, 'error');
    }
}

async function loadDocuments() {
    try {
        const response = await apiFetch('/api/documents');
        const data = await response.json();
        const tbody = document.getElementById('docListContent');

        if (data.success && Array.isArray(data.documents) && data.documents.length > 0) {
            document.getElementById('docCount').textContent = data.documents.length;
            tbody.innerHTML = data.documents.map(doc => `
                <tr>
                    <td><span class="doc-icon">${fileIcon(doc)}</span></td>
                    <td><span class="doc-name">${doc}</span></td>
                    <td><span class="doc-status">已向量化</span></td>
                    <td><button class="btn-danger" onclick="deleteDocument('${encodeURIComponent(doc)}')">删除</button></td>
                </tr>
            `).join('');
        } else {
            document.getElementById('docCount').textContent = '0';
            tbody.innerHTML = '<tr><td colspan="4" class="empty-row">知识库暂无文档，请上传文件</td></tr>';
        }
    } catch (error) {
        console.error('加载文档列表失败', error);
    }
}

async function deleteDocument(encodedName) {
    const filename = decodeURIComponent(encodedName);
    if (!confirm(`确定要从知识库中删除 "${filename}" 吗？`)) return;

    try {
        const response = await apiFetch(`/api/documents/${encodedName}`, { method: 'DELETE' });
        const data = await response.json();
        if (data.success) {
            showToast('文档已删除，索引已更新', 'success');
            loadDocuments();
            loadStatus();
        } else {
            showToast('删除失败：' + data.error, 'error');
        }
    } catch (error) {
        showToast('删除失败：' + error.message, 'error');
    }
}

// ==================== 模型配置 ====================
function loadSettings() {
    const baseUrl = localStorage.getItem('apiBaseUrl');
    const model = localStorage.getItem('modelName');
    if (baseUrl) document.getElementById('apiBaseUrl').value = baseUrl;
    if (model) document.getElementById('modelName').value = model;
}

async function saveSettings() {
    const baseUrl = document.getElementById('apiBaseUrl').value.trim();
    const apiKey = document.getElementById('apiKey').value.trim();
    const model = document.getElementById('modelName').value.trim();

    if (!apiKey) {
        showToast('API Key 不能为空', 'error');
        return;
    }

    try {
        const response = await apiFetch('/api/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ baseUrl, apiKey, model })
        });

        const data = await response.json();
        if (data.success) {
            localStorage.setItem('apiBaseUrl', baseUrl);
            localStorage.setItem('modelName', model);
            showToast('配置已保存并立即生效', 'success');
        } else {
            showToast('保存失败：' + data.error, 'error');
        }
    } catch (error) {
        showToast('保存失败：' + error.message, 'error');
    }
}

// ==================== 状态 ====================
async function loadStatus() {
    try {
        const response = await apiFetch('/api/status');
        const data = await response.json();
        if (data.success) {
            document.getElementById('sidebarDocCount').textContent = data.processedDocuments + ' 篇';
        }
    } catch (e) {
        document.getElementById('sidebarStatus').textContent = '● 离线';
        document.getElementById('statusPill').textContent = '● 服务异常';
    }
}

// ==================== Toast ====================
function showToast(message, type) {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

// ==================== 初始化 ====================
// 仅加载本地设置；文档列表与状态在登录成功后由 enterApp() 加载
loadSettings();
