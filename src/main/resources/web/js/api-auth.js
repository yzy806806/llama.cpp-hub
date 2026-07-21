// API Key auto-injection: monkey-patch fetch to add Bearer token from localStorage
(function () {
    var apiKey = localStorage.getItem('lh-api-key');
    if (!apiKey) return;
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
        init = init || {};
        init.headers = init.headers || {};
        // Skip if Authorization already set
        if (typeof init.headers === 'string') {
            return origFetch(input, init);
        }
        if (init.headers instanceof Headers) {
            if (init.headers.get('Authorization')) return origFetch(input, init);
            init.headers.set('Authorization', 'Bearer ' + apiKey);
        } else {
            if (init.headers['Authorization']) return origFetch(input, init);
            init.headers['Authorization'] = 'Bearer ' + apiKey;
        }
        return origFetch(input, init);
    };
})();
