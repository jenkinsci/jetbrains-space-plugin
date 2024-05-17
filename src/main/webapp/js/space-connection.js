Behaviour.specify(".jb-space-connection", "jb-space-connection", 0, function (el) {
    const fields = el.querySelector(".form-fields");
    const connectionId = fields.querySelector("[name='id']").value;
    const baseUrl = fields.querySelector("[name='baseUrl']").value;

    function waitForReactApp(callback) {
        if (window.ReactApp) {
            callback();
        } else {
            setTimeout(() => { waitForReactApp(callback) }, 100);
        }
    }

    const reactRootElement = el.querySelector(".react-root");
    waitForReactApp(() => ReactApp.mountSpaceConnection(reactRootElement, connectionId, baseUrl));
});
