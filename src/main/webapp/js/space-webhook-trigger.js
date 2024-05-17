Behaviour.specify(".jb-space-webhook-trigger-react-root", "jb-space-webhook-trigger-react-root", 0, function (el) {
    function waitForReactApp(callback) {
        if (window.ReactApp) {
            callback();
        } else {
            setTimeout(() => { waitForReactApp(callback) }, 100);
        }
    }

    function refreshRepoSelects() {
        el.parentNode.querySelectorAll(".jb-space-repo-select").forEach((repoSelect) => {
            const selectedValue = repoSelect.getAttribute('value');
            updateListBox(repoSelect, repoSelect.getAttribute("fillUrl"), {
                onSuccess: () => { repoSelect.value = selectedValue; }
            });
        });
    }

    waitForReactApp(() => ReactApp.mountSpaceProjectAppErrorsComponent(el, refreshRepoSelects));
});