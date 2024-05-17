(function () {
    if (window.location.pathname.endsWith("/appInstalled")) {
        localStorage.setItem('app-created', 'true');
        window.close();
    }

    function fetchCrumb() {
        return fetch(`${rootURL}/crumbIssuer/api/json`)
            .then(response => response.json())
            .then(data => data.crumb);
    }

    let connectNewButton = document.getElementById("connect-space-org");
    connectNewButton.addEventListener("click", function () {
        let left = (screen.width - 1000) / 2;
        let top = (screen.height - 800) / 2;
        let endpointUrl = encodeURIComponent(window.location.origin + rootURL);
        let installWindow = window.open('', '_blank', 'popup=yes,width=1000,height=800,top=' + top + ',left=' + left);
        if (!installWindow) {
            // we probably ran right into a popup blocker
            alert("Could not open window, please allow pop-ups and reload this page.");
            return;
        }

        fetchCrumb().then(crumb => {
            var form = installWindow.document.createElement('form');
            form.id = 'prepareApplicationForm';
            form.action = `${rootURL}/manage/spacecode/createSpaceApp`;
            form.method = 'post';

            const input = installWindow.document.createElement('input');
            input.type = 'hidden';
            input.name = "Jenkins-Crumb";
            input.value = crumb;
            form.appendChild(input);

            installWindow.document.getElementsByTagName('body')[0].appendChild(form);
            form.submit();

            window.addEventListener('storage', function(event) {
                if (event.key == 'app-created') {
                    localStorage.removeItem('app-created');
                    window.location.reload();
                }
            });
        });
    });


    var deleteButtons = document.querySelectorAll('.delete-space-connection-btn');
    deleteButtons.forEach(function (button) {
        button.addEventListener('click', function (event) {
            if (confirm('Are you sure you want to delete the SpaceCode connection?')) {
                let connectionId = button.getAttribute('data-connection-id');
                fetchCrumb().then(crumb => {
                    fetch(`${rootURL}/manage/spacecode/deleteConnection`, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded',
                            'Jenkins-Crumb': crumb
                        },
                        body: 'connectionId=' + connectionId
                    }).then(function (response) {
                        if (response.ok) {
                            // Remove the element from the DOM or refresh the page
                            // button.closest('.repeated-container').remove();
                        } else {
                            alert('Failed to delete the SpaceCode connection.');
                        }
                    });
                });
            } else {
                event.stopImmediatePropagation();
            }
        });
    });
})();
