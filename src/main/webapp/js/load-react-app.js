fetch(`${rootURL}/jb-space-projects/reactAppFiles`)
    .then((response) => response.json())
    .then((data) => {
        if (data.js) {
            const script = document.createElement('script');
            script.type = 'text/javascript';
            script.src = data.js;
            document.head.append(script)
        }
        if (data.css) {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = data.css;
            document.head.appendChild(link);

        }
    });
