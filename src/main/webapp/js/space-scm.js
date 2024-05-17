Behaviour.specify(".jb-space-scm-react-root", "jb-space-scm-react-root", 0, function (el) {
    if (!window.location.pathname.endsWith("/pipeline-syntax")) {
        function waitForReactApp(callback) {
            if (window.ReactApp) {
                callback();
            } else {
                setTimeout(() => {
                    waitForReactApp(callback)
                }, 100);
            }
        }

        function refreshRepoSelects() {
            el.parentNode.querySelectorAll(".jb-space-repo-select").forEach((repoSelect) => {
                const selectedValue = repoSelect.getAttribute('value');
                updateListBox(repoSelect, repoSelect.getAttribute("fillUrl"), {
                    onSuccess: () => {
                        repoSelect.value = selectedValue;
                    }
                });
            });
        }

        waitForReactApp(() => ReactApp.mountSpaceProjectAppErrorsComponent(el, refreshRepoSelects));
    }
});