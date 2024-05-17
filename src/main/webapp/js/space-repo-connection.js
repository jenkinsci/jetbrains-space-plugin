Behaviour.specify(".jb-space-repo-connection", "jb-space-repo-connection", 0, function (el) {
    const fields = el.querySelector(".form-fields");
    const connectionIdInput = fields.querySelector("[name='spaceConnectionId']");
    const projectKeyInput = fields.querySelector("[name='projectKey']");
    const repositoryInput = fields.querySelector("[name='repository']");

    function waitForReactApp(callback) {
        if (window.ReactApp) {
            callback();
        } else {
            setTimeout(() => {
                waitForReactApp(callback)
            }, 100);
        }
    }

    const reactRootElement = el.querySelector(".react-root");
    waitForReactApp(() => ReactApp.mountSpaceRepoConnection(reactRootElement, {connectionIdInput, projectKeyInput, repositoryInput}));
});
