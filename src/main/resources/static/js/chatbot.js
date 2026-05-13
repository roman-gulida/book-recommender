(function () {
    // Determine page context
    function getPageContext() {
        const path = window.location.pathname;
        const params = new URLSearchParams(window.location.search);

        if (path.includes('book.html') && params.get('id')) {
            return 'book_' + params.get('id');
        }
        if (path.includes('books.html')) {
            return 'books_list';
        }
        return 'home';
    }

    // Create chatbot HTML
    function createChatWidget() {
        // Toggle button
        const toggle = document.createElement('button');
        toggle.className = 'chat-toggle';
        toggle.id = 'chat-toggle';
        toggle.innerHTML = '💬';
        toggle.title = 'Open Chat';

        // Chat window
        const win = document.createElement('div');
        win.className = 'chat-window';
        win.id = 'chat-window';
        win.innerHTML = `
            <div class="chat-header">
                <span>Book Assistant</span>
                <button id="chat-close">&times;</button>
            </div>
            <div class="chat-starters" id="chat-starters"></div>
            <div class="chat-messages" id="chat-messages"></div>
            <div class="chat-input-area">
                <input type="text" id="chat-input" placeholder="Ask about books..." />
                <button id="chat-send">Send</button>
            </div>
        `;

        document.body.appendChild(toggle);
        document.body.appendChild(win);

        // Events
        toggle.addEventListener('click', () => {
            win.classList.toggle('open');
            if (win.classList.contains('open')) {
                loadStarters();
                document.getElementById('chat-input').focus();
            }
        });

        document.getElementById('chat-close').addEventListener('click', () => {
            win.classList.remove('open');
        });

        document.getElementById('chat-send').addEventListener('click', sendMessage);
        document.getElementById('chat-input').addEventListener('keydown', (e) => {
            if (e.key === 'Enter') sendMessage();
        });
    }

    function loadStarters() {
        const container = document.getElementById('chat-starters');
        if (container.children.length > 0) return; // already loaded

        const ctx = getPageContext();
        fetch('/api/chat/starters?context=' + encodeURIComponent(ctx))
            .then(r => r.json())
            .then(starters => {
                container.innerHTML = '';
                starters.forEach(text => {
                    const btn = document.createElement('button');
                    btn.textContent = text;
                    btn.addEventListener('click', () => {
                        document.getElementById('chat-input').value = text;
                        sendMessage();
                    });
                    container.appendChild(btn);
                });
            })
            .catch(() => {
                container.innerHTML = '';
            });
    }

    function sendMessage() {
        const input = document.getElementById('chat-input');
        const msg = input.value.trim();
        if (!msg) return;

        addMessage(msg, 'user');
        input.value = '';

        const loadingEl = addMessage('Thinking...', 'bot loading');

        fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: msg,
                pageContext: getPageContext()
            })
        })
            .then(r => r.json())
            .then(data => {
                loadingEl.remove();
                addMessage(data.response || data.error || 'No response', 'bot');
            })
            .catch(err => {
                loadingEl.remove();
                addMessage('Error: ' + err.message, 'bot');
            });
    }

    function addMessage(text, type) {
        const container = document.getElementById('chat-messages');
        const div = document.createElement('div');
        div.className = 'chat-msg ' + type;
        div.textContent = text;
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
        return div;
    }

    // Init on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', createChatWidget);
    } else {
        createChatWidget();
    }
})();
