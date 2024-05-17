Behaviour.specify(".jb-space-project-connection", "jb-space-project-connection", 0, function (el) {
    const fields = el.querySelector(".form-fields");
    const connectionIdInput = fields.querySelector("[name='spaceConnectionId']");
    const projectKeyInput = fields.querySelector("[name='projectKey']");

    function waitForReactApp(callback) {
        if (window.ReactApp) {
            callback();
        } else {
            setTimeout(() => { waitForReactApp(callback) }, 100);
        }
    }

    const reactRootElement = el.querySelector(".react-root");

    const checkbox = el.querySelector("input[type='checkbox']");
    if (checkbox) {
        var reactRoot = checkbox.checked
            ? waitForReactApp(() => ReactApp.mountSpaceProjectConnection(reactRootElement, {
                connectionIdInput,
                projectKeyInput
            }))
            : null;

        checkbox.onchange = (e) => {
            connectionIdInput.value = "";
            projectKeyInput.value = "";
            if (checkbox.checked) {
                reactRoot = ReactApp.mountSpaceProjectConnection(reactRootElement, {
                    connectionIdInput,
                    projectKeyInput
                });
            } else if (reactRoot) {
                reactRoot.unmount();
            }
        };
    } else {
        waitForReactApp(() => ReactApp.mountSpaceProjectConnection(reactRootElement, { connectionIdInput, projectKeyInput }));
    }
});
